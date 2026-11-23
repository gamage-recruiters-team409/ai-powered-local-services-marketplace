package com.gamagerecruiters.marketplace.modules.messaging.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.messaging.domain.FlagSource;
import com.gamagerecruiters.marketplace.modules.messaging.domain.MessageStatus;

public record ModerationMessageResponse(
        String id,
        String conversationId,
        String bookingId,
        String listingId,
        String senderId,
        String body,
        MessageStatus status,
        boolean flagged,
        FlagSource flagSource,
        String hideReason,
        String hiddenBy,
        Instant hiddenAt,
        Instant createdAt
) {
}
