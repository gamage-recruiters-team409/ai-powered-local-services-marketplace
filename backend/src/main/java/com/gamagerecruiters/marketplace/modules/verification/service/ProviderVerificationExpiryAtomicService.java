
package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;

@Service
public class ProviderVerificationExpiryAtomicService {

    private final MongoTemplate mongoTemplate;

    public ProviderVerificationExpiryAtomicService(
            MongoTemplate mongoTemplate
    ) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Atomically expires a provider only when:
     * - Verification is still APPROVED.
     * - The submitted document snapshot has not changed.
     *
     * This prevents stale expiry checks from overwriting
     * a concurrent verification submission or decision.
     */
    public Optional<ProviderVerification> expireApprovedIfUnchanged(
            String providerId,
            List<String> expectedSubmittedDocumentIds,
            List<String> expiredDocumentIds,
            Instant expiredAt,
            Instant gracePeriodEndsAt
    ) {

        if (providerId == null || providerId.isBlank()
                || expectedSubmittedDocumentIds == null
                || expectedSubmittedDocumentIds.isEmpty()
                || expiredDocumentIds == null
                || expiredDocumentIds.isEmpty()) {

            return Optional.empty();
        }

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),

                        Criteria.where("status")
                                .is(ProviderVerificationStatus.APPROVED),

                        Criteria.where("submittedDocumentIds")
                                .is(List.copyOf(expectedSubmittedDocumentIds))
                )
        );

        Update update = new Update()
                .set(
                        "status",
                        ProviderVerificationStatus.EXPIRED
                )
                .set("expiredAt", expiredAt)
                .set("gracePeriodEndsAt", gracePeriodEndsAt)
                .set(
                        "expiredDocumentIds",
                        List.copyOf(expiredDocumentIds)
                )
                .set("updatedAt", expiredAt)
                .inc("coordinationRevision", 1);

        ProviderVerification updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                ProviderVerification.class
        );

        return Optional.ofNullable(updated);
    }

    /**
     * Clears previous expiry information after the provider
     * has successfully completed re-verification.
     */
    public boolean clearExpiryMetadataIfApproved(String providerId) {

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),

                        Criteria.where("status")
                                .is(ProviderVerificationStatus.APPROVED),

                        Criteria.where("expiredAt").ne(null)
                )
        );

        Update update = new Update()
                .unset("expiredAt")
                .unset("gracePeriodEndsAt")
                .unset("expiredDocumentIds");

        return mongoTemplate.updateFirst(
                query,
                update,
                ProviderVerification.class
        ).getModifiedCount() > 0;
    }
}
