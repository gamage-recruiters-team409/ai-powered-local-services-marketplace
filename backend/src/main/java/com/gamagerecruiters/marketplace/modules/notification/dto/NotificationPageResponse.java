package com.gamagerecruiters.marketplace.modules.notification.dto;
import java.util.List;
public record NotificationPageResponse(List<NotificationResponse> content, int page, int size,
        long totalElements, int totalPages) {}
