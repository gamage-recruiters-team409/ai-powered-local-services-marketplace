package com.gamagerecruiters.marketplace.modules.notification.service;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.notification.domain.Notification;
import com.gamagerecruiters.marketplace.modules.notification.dto.NotificationPageResponse;
import com.gamagerecruiters.marketplace.modules.notification.dto.NotificationResponse;
import com.gamagerecruiters.marketplace.modules.notification.repository.NotificationRepository;

@Service
public class NotificationInboxServiceImpl implements NotificationInboxService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private final NotificationRepository repository;
    private final MongoTemplate mongoTemplate;
    private final NotificationTextBuilder textBuilder;
    public NotificationInboxServiceImpl(NotificationRepository repository, MongoTemplate mongoTemplate,
            NotificationTextBuilder textBuilder) { this.repository = repository; this.mongoTemplate = mongoTemplate; this.textBuilder = textBuilder; }

    @Override public NotificationPageResponse listMine(boolean unreadOnly, int page, int size) {
        String userId = requireUser();
        if (page < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Page must not be negative");
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Notification> result = unreadOnly ? repository.findByUserIdAndReadAtIsNull(userId, pageable) : repository.findByUserId(userId, pageable);
        List<NotificationResponse> content = result.getContent().stream().map(this::response).toList();
        return new NotificationPageResponse(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
    @Override public NotificationResponse markRead(String id) {
        String userId = requireUser();
        Query query = Query.query(Criteria.where("_id").is(id).and("userId").is(userId).and("readAt").is(null));
        if (mongoTemplate.updateFirst(query, new Update().set("readAt", Instant.now()), Notification.class).getModifiedCount() > 0) {
            Notification updated = mongoTemplate.findOne(Query.query(Criteria.where("_id").is(id).and("userId").is(userId)), Notification.class);
            if (updated != null) return response(updated);
        }
        Notification existing = mongoTemplate.findOne(Query.query(Criteria.where("_id").is(id).and("userId").is(userId)), Notification.class);
        if (existing == null) throw new ResourceNotFoundException("NOTIFICATION_NOT_FOUND", "Notification not found");
        return response(existing);
    }
    @Override public long markAllRead() {
        return mongoTemplate.updateMulti(Query.query(Criteria.where("userId").is(requireUser()).and("readAt").is(null)),
                new Update().set("readAt", Instant.now()), Notification.class).getModifiedCount();
    }
    @Override public long unreadCount() { return mongoTemplate.count(Query.query(Criteria.where("userId").is(requireUser()).and("readAt").is(null)), Notification.class); }
    private String requireUser() {
        String userId = CurrentUser.getUserId();
        if (userId == null || userId.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        return userId;
    }
    private NotificationResponse response(Notification notification) {
        NotificationText text = textBuilder.build(notification.getType(), notification.getData());
        return new NotificationResponse(notification.getId(), notification.getType(), text.title(), text.message(), notification.getBookingId(),
                notification.getData(), notification.getCreatedAt(), notification.getReadAt(), notification.getReadAt() != null);
    }
}
