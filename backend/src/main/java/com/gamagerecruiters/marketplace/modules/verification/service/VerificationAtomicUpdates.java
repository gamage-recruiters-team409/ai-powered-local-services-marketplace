package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.mongodb.client.result.UpdateResult;

import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

@Service
public class VerificationAtomicUpdates {

    private static final List<ProviderVerificationStatus> UPLOADABLE_STATUSES =
            List.of(
                    ProviderVerificationStatus.NOT_SUBMITTED,
                    ProviderVerificationStatus.REJECTED,
                    ProviderVerificationStatus.RESUBMISSION_REQUESTED,
                    ProviderVerificationStatus.EXPIRED
            );

    private static final List<ProviderVerificationStatus> SUBMITTABLE_STATUSES =
            List.of(
                    ProviderVerificationStatus.NOT_SUBMITTED,
                    ProviderVerificationStatus.REJECTED,
                    ProviderVerificationStatus.RESUBMISSION_REQUESTED,
                    ProviderVerificationStatus.EXPIRED
            );

    private static final List<ProviderVerificationStatus> REVIEWABLE_STATUSES =
            List.of(
                    ProviderVerificationStatus.SUBMITTED,
                    ProviderVerificationStatus.UNDER_REVIEW
            );

    private final MongoTemplate mongoTemplate;
    private final ProviderVerificationRepository providerVerificationRepository;

    public VerificationAtomicUpdates(
            MongoTemplate mongoTemplate,
            ProviderVerificationRepository providerVerificationRepository
    ) {
        this.mongoTemplate = mongoTemplate;
        this.providerVerificationRepository = providerVerificationRepository;
    }

    /**
     * Creates the initial NOT_SUBMITTED record when the provider has not
     * uploaded or submitted verification documents before.
     * The unique providerId index makes concurrent first-time creation safe.
     */
    public ProviderVerification ensureVerificationRecord(
            String providerId,
            Instant now
    ) {
        Optional<ProviderVerification> existing =
                providerVerificationRepository.findByProviderId(providerId);

        if (existing.isPresent()) {
            return existing.get();
        }

        ProviderVerification verification = new ProviderVerification();
        verification.setProviderId(providerId);
        verification.setStatus(ProviderVerificationStatus.NOT_SUBMITTED);
        verification.setCreatedAt(now);
        verification.setUpdatedAt(now);
        verification.setCoordinationRevision(0L);
        verification.setInFlightUploadCount(0);

        try {
            return mongoTemplate.insert(verification);
        } catch (DuplicateKeyException exception) {
            return providerVerificationRepository.findByProviderId(providerId)
                    .orElseThrow(() -> exception);
        }
    }

    /**
     * Reserves an upload before contacting Cloudinary. A submission cannot
     * complete while this counter is greater than zero.
     */
    public void reserveDocumentUpload(String providerId, Instant now) {
        ensureVerificationRecord(providerId, now);

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),
                        Criteria.where("status").in(UPLOADABLE_STATUSES)
                )
        );

        // Do not modify updatedAt here: for EXPIRED records it is the workflow
        // timestamp used to require a newer document during re-verification.
        Update update = new Update()
                .inc("inFlightUploadCount", 1)
                .inc("coordinationRevision", 1);

        ProviderVerification updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                ProviderVerification.class
        );

        if (updated == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Verification documents cannot be uploaded while the verification is being reviewed or is already approved"
            );
        }
    }

    /**
     * Releases the upload reservation after Cloudinary and MongoDB persistence
     * have either succeeded or failed. Workflow updatedAt is intentionally
     * unchanged because it records verification-state changes, not upload locks.
     */
    public void releaseDocumentUpload(String providerId) {
        Query query = new Query(
                Criteria.where("providerId").is(providerId)
                        .and("inFlightUploadCount").gt(0)
        );

        Update update = new Update()
                .inc("inFlightUploadCount", -1)
                .inc("coordinationRevision", 1);

        UpdateResult result = mongoTemplate.updateFirst(
                query,
                update,
                ProviderVerification.class
        );

        if (result.getMatchedCount() != 1) {
            throw new IllegalStateException(
                    "Unable to release the provider verification upload reservation"
            );
        }
    }

    /**
     * Atomically freezes the submitted document IDs and changes the workflow
     * to SUBMITTED only when no upload is in progress and the revision has
     * not changed since the provider read the document set.
     */
    public Optional<ProviderVerification> submitIfUnchanged(
            String providerId,
            long expectedRevision,
            List<String> submittedDocumentIds,
            Instant now
    ) {
        Criteria inFlightUploadsZero = new Criteria().orOperator(
                Criteria.where("inFlightUploadCount").is(0),
                Criteria.where("inFlightUploadCount").exists(false)
        );

        Criteria revisionMatches;
        if (expectedRevision == 0L) {
            revisionMatches = new Criteria().orOperator(
                    Criteria.where("coordinationRevision").is(0L),
                    Criteria.where("coordinationRevision").exists(false)
            );
        } else {
            revisionMatches =
                    Criteria.where("coordinationRevision").is(expectedRevision);
        }

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),
                        Criteria.where("status").in(SUBMITTABLE_STATUSES),
                        inFlightUploadsZero,
                        revisionMatches
                )
        );

        Update update = new Update()
                .set("status", ProviderVerificationStatus.SUBMITTED)
                .set("submittedDocumentIds", List.copyOf(submittedDocumentIds))
                .set("submittedAt", now)
                .set("reviewedAt", null)
                .set("reviewedByUserId", null)
                .set("decisionReason", null)
                .set("updatedAt", now)
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
     * Starts review only if the record is still SUBMITTED and no upload is
     * in progress. A stale request cannot reopen a completed decision.
     */
    public Optional<ProviderVerification> startReview(
            String providerId,
            Instant now
    ) {
        Criteria noUploadsInFlight = new Criteria().orOperator(
                Criteria.where("inFlightUploadCount").is(0),
                Criteria.where("inFlightUploadCount").exists(false)
        );

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),
                        Criteria.where("status").is(ProviderVerificationStatus.SUBMITTED),
                        noUploadsInFlight
                )
        );

        Update update = new Update()
                .set("status", ProviderVerificationStatus.UNDER_REVIEW)
                .set("updatedAt", now)
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
     * Applies one administrative decision using a conditional atomic update.
     * The expected document-ID snapshot prevents a stale decision from being
     * applied to a later resubmission.
     */
    public Optional<ProviderVerification> applyDecision(
            String providerId,
            List<String> expectedSubmittedDocumentIds,
            ProviderVerificationStatus newStatus,
            String reviewerId,
            String reason,
            Instant now
    ) {
        Criteria noUploadsInFlight = new Criteria().orOperator(
                Criteria.where("inFlightUploadCount").is(0),
                Criteria.where("inFlightUploadCount").exists(false)
        );

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),
                        Criteria.where("status").in(REVIEWABLE_STATUSES),
                        Criteria.where("submittedDocumentIds")
                                .is(List.copyOf(expectedSubmittedDocumentIds)),
                        noUploadsInFlight
                )
        );

        Update update = new Update()
                .set("status", newStatus)
                .set("reviewedAt", now)
                .set("reviewedByUserId", reviewerId)
                .set("decisionReason", reason)
                .set("updatedAt", now)
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
     * Changes only documents frozen into the current submission. The caller
     * checks matchedCount and throws inside its Mongo transaction if any
     * submitted document is missing.
     */
    public long updateSubmittedDocumentStatuses(
            String providerId,
            List<String> submittedDocumentIds,
            VerificationDocumentStatus documentStatus,
            Instant now
    ) {
        if (submittedDocumentIds == null || submittedDocumentIds.isEmpty()) {
            return 0L;
        }

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),
                        Criteria.where("id").in(submittedDocumentIds)
                )
        );

        Update update = new Update()
                .set("status", documentStatus)
                .set("updatedAt", now);

        UpdateResult result = mongoTemplate.updateMulti(
                query,
                update,
                VerificationDocument.class
        );

        return result.getMatchedCount();
    }
}
