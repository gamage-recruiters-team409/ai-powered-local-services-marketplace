package com.gamagerecruiters.marketplace.modules.notification.dto;
import java.time.Instant;
import java.util.Map;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;
public record NotificationResponse(String id, NotificationType type, String title, String message,
        String bookingId, Map<String, String> data, Instant createdAt, Instant readAt, boolean read) {}
