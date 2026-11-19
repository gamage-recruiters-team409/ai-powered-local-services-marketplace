
package com.gamagerecruiters.marketplace.modules.verification.event;

import java.time.Instant;
import java.util.List;

public record ProviderVerificationExpiredEvent(

        String providerId,

        String providerUserId,

        String verificationId,

        Instant expiredAt,

        Instant gracePeriodEndsAt,

        List<String> expiredDocumentIds

) {

    public ProviderVerificationExpiredEvent {
        expiredDocumentIds = expiredDocumentIds == null
                ? List.of()
                : List.copyOf(expiredDocumentIds);
    }
}
