package com.gamagerecruiters.marketplace.modules.messaging.dto;

import java.time.Instant;
import java.util.List;

/**
 * Cursor-based response for incremental message polling.
 *
 * <p>Clients must send {@code nextSince} and {@code nextMessageId} together as {@code since} and
 * {@code afterId} when continuing from a prior response. The pair is ordered by
 * {@code (createdAt, id)}, so messages that share a persisted timestamp are not skipped. When
 * {@code content} is empty, both next-cursor fields are null and clients keep their prior cursor.</p>
 */
public record MessagePollingResponse(
        List<MessageResponse> content,
        int size,
        long totalElements,
        int totalPages,
        boolean hasMore,
        Instant nextSince,
        String nextMessageId
) {
}
