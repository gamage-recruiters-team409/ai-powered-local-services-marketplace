package com.gamagerecruiters.marketplace.modules.notification.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
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

    public NotificationServiceImpl(UserInternalService userInternalService, NotificationRepository notificationRepository,
            NotificationDataSanitizer sanitizer, @Qualifier("notificationExecutor") Executor executor) {
        this.userInternalService = userInternalService;
        this.notificationRepository = notificationRepository;
        this.sanitizer = sanitizer;
        this.executor = executor;
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
                save(notification);
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
        } catch (Exception ex) {
            log.warn("Notification task rejected");
        }
    }

    private void save(Notification notification) {
        try { notificationRepository.save(notification); }
        catch (Exception ex) { log.warn("Notification save failed for type {}", notification.getType().name()); }
    }
}
