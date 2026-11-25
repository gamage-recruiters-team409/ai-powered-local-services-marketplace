package com.gamagerecruiters.marketplace.modules.notification.service;
import java.util.Map;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;
public record NotificationEvent(NotificationType type, String recipientUserId, String bookingId,
        Map<String, String> data) {}
