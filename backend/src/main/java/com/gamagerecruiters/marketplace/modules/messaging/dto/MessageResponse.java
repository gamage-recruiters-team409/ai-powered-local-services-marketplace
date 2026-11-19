package com.gamagerecruiters.marketplace.modules.messaging.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.messaging.domain.MessageStatus;

public record MessageResponse(
        String id,
        String conversationId,
        String senderId,
        String body,
        MessageStatus status,
        Instant createdAt
) {
}
