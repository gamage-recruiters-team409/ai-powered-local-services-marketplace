package com.gamagerecruiters.marketplace.modules.review.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.review.domain.ModerationAction;
import com.gamagerecruiters.marketplace.modules.review.domain.ModerationFlag;
import com.gamagerecruiters.marketplace.modules.review.domain.RemovalReason;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;

/** One moderation history entry. */
public record ModerationEntryResponse(
        ModerationAction action,
        ReviewStatus fromStatus,
        ReviewStatus toStatus,
        List<ModerationFlag> flags,
        RemovalReason reasonCode,
        String note,
        String actorId,
        Instant at
) {
}
