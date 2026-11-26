package com.gamagerecruiters.marketplace.modules.notification.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.notification.domain.Notification;
import com.gamagerecruiters.marketplace.modules.notification.repository.NotificationRepository;

@Service
public class NotificationServiceImpl implements NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);
    private final UserInternalService userInternalService;
    private final NotificationRepository notificationRepository;
    private final NotificationDataSanitizer sanitizer;
    private final Executor executor;
    private final Executor emailExecutor;
    private final EmailSender emailSender;
    private final NotificationTextBuilder textBuilder;
    private final MongoTemplate mongoTemplate;
    private final AtomicLong rejectedNotifications = new AtomicLong();
    private final AtomicLong rejectedEmails = new AtomicLong();

    public NotificationServiceImpl(UserInternalService userInternalService, NotificationRepository notificationRepository,
            NotificationDataSanitizer sanitizer, @Qualifier("notificationExecutor") Executor executor,
            @Qualifier("notificationEmailExecutor") Executor emailExecutor, EmailSender emailSender,
            NotificationTextBuilder textBuilder, MongoTemplate mongoTemplate) {
        this.userInternalService = userInternalService;
        this.notificationRepository = notificationRepository;
        this.sanitizer = sanitizer;
        this.executor = executor;
        this.emailExecutor = emailExecutor;
        this.emailSender = emailSender;
        this.textBuilder = textBuilder;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void notify(NotificationEvent event) {
        try {
            if (event == null || event.type() == null || event.recipientUserId() == null || event.recipientUserId().isBlank()) {
                log.warn("Notification event ignored");
                return;
            }
            if (userInternalService.getContactById(event.recipientUserId()).status() != UserStatus.ACTIVE) {
                log.warn("Notification recipient inactive for type {}", event.type().name());
                return;
            }
            Notification notification = new Notification();
            notification.setUserId(event.recipientUserId());
            notification.setType(event.type());
            notification.setBookingId(event.bookingId());
            Map<String, String> data = sanitizer.sanitize(event.data(), key -> log.warn("Notification data key dropped: {}", key));
            notification.setData(data.isEmpty() ? null : data);
            notification.setCreatedAt(Instant.now());
            notification.setReadAt(null);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { submitSave(notification); }
                });
            } else {
                submitSave(notification);
            }
        } catch (Exception ex) {
            String type = event == null || event.type() == null ? "UNKNOWN" : event.type().name();
            log.warn("Notification delivery failed for type {}", type);
        }
    }

    private void submitSave(Notification notification) {
        try {
            // A failed insert could abort the caller's Mongo transaction, and a committed session cannot be reused.
            executor.execute(() -> save(notification));
        } catch (RejectedExecutionException ex) {
            // Best-effort contract: a rejected save is not persisted and never fails the caller.
            rejectedNotifications.incrementAndGet();
            log.warn("Notification save task rejected for type {}", notification.getType().name());
        } catch (Exception ex) {
            rejectedNotifications.incrementAndGet();
            log.warn("Notification save task rejected for type {}", notification.getType().name());
        }
    }

    /**
     * The single-thread save executor serialises this check and save, so it is race-free within one
     * backend instance. With several instances, duplicate unread NEW_MESSAGE notifications for one
     * conversation remain possible and harmless; a database unique partial index plus upsert is
     * deferred until multi-instance deployment is in scope.
     */
    private void save(Notification notification) {
        try {
            if (notification.getType() == com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType.NEW_MESSAGE
                    && mongoTemplate.exists(newMessageQuery(notification), Notification.class)) {
                log.warn("Notification duplicate skipped for type {}", notification.getType().name());
                return;
            }
            Notification saved = notificationRepository.save(notification);
            submitEmail(saved == null ? notification : saved);
        }
        catch (Exception ex) { log.warn("Notification save failed for type {}", notification.getType().name()); }
    }

    private void submitEmail(Notification notification) {
        try {
            emailExecutor.execute(() -> sendEmail(notification));
        } catch (RejectedExecutionException ex) {
            rejectedEmails.incrementAndGet();
            recordRejectedEmailStatus(notification);
            log.warn("Notification email task rejected for type {}", notification.getType().name());
        } catch (Exception ex) {
            rejectedEmails.incrementAndGet();
            recordRejectedEmailStatus(notification);
            log.warn("Notification email task rejected for type {}", notification.getType().name());
        }
    }

    private void recordRejectedEmailStatus(Notification notification) {
        try {
            updateEmailStatus(notification.getId(), "FAILED");
        } catch (Exception ex) {
            log.warn("Notification email status update failed for type {}", notification.getType().name());
        }
    }

    private Query newMessageQuery(Notification notification) {
        return Query.query(Criteria.where("type").is(notification.getType()).and("userId").is(notification.getUserId())
                .and("readAt").is(null).and("data.conversationId").is(notification.getData() == null ? null : notification.getData().get("conversationId")));
    }

    private void sendEmail(Notification notification) {
        try {
            if (!NotificationEmailPolicy.allows(notification.getType())) {
                updateEmailStatus(notification.getId(), "SKIPPED");
                return;
            }
            UserInternalService.UserContact contact = userInternalService.getContactById(notification.getUserId());
            if (contact == null || contact.email() == null || contact.email().isBlank()) {
                updateEmailStatus(notification.getId(), "SKIPPED");
                return;
            }
            NotificationText text = textBuilder.build(notification.getType(), notification.getData());
            Map<String, Object> data = new HashMap<>();
            data.put("name", contact.fullName()); data.put("title", text.title()); data.put("message", text.message());
            EmailResult result = emailSender.sendEmail(EmailType.NOTIFICATION, contact.email(), data);
            updateEmailStatus(notification.getId(), result.success() ? "SENT" : "FAILED");
        } catch (Exception ex) {
            updateEmailStatus(notification.getId(), "FAILED");
            log.warn("Notification email failed for type {}", notification.getType().name());
        }
    }

    private void updateEmailStatus(String id, String status) {
        if (id != null) mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("emailStatus", status), Notification.class);
    }

    public long getRejectedNotifications() {
        return rejectedNotifications.get();
    }

    public long getRejectedEmails() {
        return rejectedEmails.get();
    }
}
