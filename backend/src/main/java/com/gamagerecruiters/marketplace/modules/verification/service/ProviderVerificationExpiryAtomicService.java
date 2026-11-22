package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    public ProviderVerificationExpiryAtomicService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Status change and pending event marker are persisted atomically in one MongoDB document. */
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
                || expiredDocumentIds == null || expiredDocumentIds.isEmpty()) {
            return Optional.empty();
        }

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("providerId").is(providerId),
                Criteria.where("status").is(ProviderVerificationStatus.APPROVED),
                Criteria.where("submittedDocumentIds").is(List.copyOf(expectedSubmittedDocumentIds))
        ));

        Update update = new Update()
                .set("status", ProviderVerificationStatus.EXPIRED)
                .set("expiredAt", expiredAt)
                .set("gracePeriodEndsAt", gracePeriodEndsAt)
                .set("expiredDocumentIds", List.copyOf(expiredDocumentIds))
                .set("updatedAt", expiredAt)
                .set("expiryEventPending", true)
                .unset("expiryEventPublishedAt")
                .unset("expiryEventClaimToken")
                .unset("expiryEventLeaseUntil")
                .inc("coordinationRevision", 1);

        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                ProviderVerification.class
        ));
    }

    /** Claims one pending expiry event for this worker. Safe with concurrent job instances. */
    public Optional<ProviderVerification> claimNextPendingExpiryEvent(
            Instant now,
            Duration lease
    ) {
        String claimToken = UUID.randomUUID().toString();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("expiryEventPending").is(true),
                new Criteria().orOperator(
                        Criteria.where("expiryEventLeaseUntil").is(null),
                        Criteria.where("expiryEventLeaseUntil").lte(now)
                )
        ));

        Update update = new Update()
                .set("expiryEventClaimToken", claimToken)
                .set("expiryEventLeaseUntil", now.plus(lease));

        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                ProviderVerification.class
        ));
    }

    /** Acknowledges the event only if this worker still owns the claim. */
    public boolean markExpiryEventPublished(
            String providerId,
            String claimToken,
            Instant publishedAt
    ) {
        Query query = claimedEventQuery(providerId, claimToken);
        Update update = new Update()
                .set("expiryEventPending", false)
                .set("expiryEventPublishedAt", publishedAt)
                .unset("expiryEventLeaseUntil")
                .unset("expiryEventClaimToken");
        return mongoTemplate.updateFirst(query, update, ProviderVerification.class)
                .getModifiedCount() > 0;
    }

    /** Retry on a later scheduled run; avoid an immediate infinite retry loop. */
    public void deferExpiryEvent(
            String providerId,
            String claimToken,
            Instant retryAfter
    ) {
        Query query = claimedEventQuery(providerId, claimToken);
        Update update = new Update()
                .set("expiryEventLeaseUntil", retryAfter)
                .unset("expiryEventClaimToken");
        mongoTemplate.updateFirst(query, update, ProviderVerification.class);
    }

    private Query claimedEventQuery(String providerId, String claimToken) {
        if (providerId == null || providerId.isBlank()
                || claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("Expiry event claim identifiers are required");
        }
        return new Query(new Criteria().andOperator(
                Criteria.where("providerId").is(providerId),
                Criteria.where("expiryEventPending").is(true),
                Criteria.where("expiryEventClaimToken").is(claimToken)
        ));
    }

    /** Clears old expiry information only after a successful re-verification. */
    public boolean clearExpiryMetadataIfApproved(String providerId) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("providerId").is(providerId),
                Criteria.where("status").is(ProviderVerificationStatus.APPROVED),
                Criteria.where("expiredAt").ne(null),
                Criteria.where("expiryEventPending").ne(true)
        ));
        Update update = new Update()
                .unset("expiredAt")
                .unset("gracePeriodEndsAt")
                .unset("expiredDocumentIds");
        return mongoTemplate.updateFirst(query, update, ProviderVerification.class)
                .getModifiedCount() > 0;
    }
}
