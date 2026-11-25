package com.gamagerecruiters.marketplace.modules.messaging.dto;

import java.time.Instant;

public record ConversationResponse(
        String id,
        String bookingId,
        String customerId,
        String providerId,
        Instant lastMessageAt,
        Instant updatedAt
) {
}
