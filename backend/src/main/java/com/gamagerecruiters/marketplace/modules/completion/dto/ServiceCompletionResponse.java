package com.gamagerecruiters.marketplace.modules.completion.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.completion.domain.CompletionConfirmationType;

public record ServiceCompletionResponse(
        String id,
        String bookingId,
        Instant startedAt,
        String completionNote,
        List<String> photoUrls,
        Instant markedCompletedAt,
        Instant confirmedAt,
        CompletionConfirmationType confirmationType,
        Instant issueRaisedAt,
        String disputeId
) {
}
