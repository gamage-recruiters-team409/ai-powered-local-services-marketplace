package com.gamagerecruiters.marketplace.modules.verification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bson.Document;
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

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification.UploadReservation;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocument;
import com.gamagerecruiters.marketplace.modules.verification.domain.VerificationDocumentStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

@Service
public class VerificationAtomicUpdates {

    // An upload must renew this lease while it is actively running.
    // If the backend crashes, the lease expires and can safely be recovered.
    public static final Duration UPLOAD_RESERVATION_LEASE_DURATION =
            Duration.ofMinutes(5);

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
        verification.setUploadReservations(new ArrayList<>());

        try {
            return mongoTemplate.insert(verification);
        } catch (DuplicateKeyException exception) {
            return providerVerificationRepository.findByProviderId(providerId)
                    .orElseThrow(() -> exception);
        }
    }

    /**
     * Reserves an upload before contacting Cloudinary.
     *
     * A unique reservation ID is returned to the caller. The reservation is
     * stored atomically with the in-flight counter and coordination revision.
     */
    public String reserveDocumentUpload(String providerId, Instant now) {
        ensureVerificationRecord(providerId, now);

        // Clear only expired reservations before adding a new one.
        recoverExpiredUploadReservations(providerId, now);

        String reservationId = UUID.randomUUID().toString();

        UploadReservation reservation = new UploadReservation(
                reservationId,
                now,
                now.plus(UPLOAD_RESERVATION_LEASE_DURATION)
        );

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),
                        Criteria.where("status").in(UPLOADABLE_STATUSES)
                )
        );

        // Do not modify updatedAt here: for EXPIRED records it is the workflow
        // timestamp used to require a newer document during re-verification.
        Update update = new Update()
                .push("uploadReservations").value(reservation)
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

        return reservationId;
    }

    /**
     * Renews an active upload lease.
     *
     * The conditional query ensures an already expired/recovered reservation
     * cannot be brought back to life.
     */
    public boolean renewDocumentUploadReservation(
            String providerId,
            String reservationId,
            Instant now
    ) {
        if (reservationId == null || reservationId.isBlank()) {
            return false;
        }

        Criteria activeReservation = new Criteria().andOperator(
                Criteria.where("reservationId").is(reservationId),
                Criteria.where("leaseExpiresAt").gt(now)
        );

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),
                        Criteria.where("inFlightUploadCount").gt(0),
                        Criteria.where("uploadReservations").elemMatch(activeReservation)
                )
        );

        Update update = new Update()
                .set(
                        "uploadReservations.$.leaseExpiresAt",
                        now.plus(UPLOAD_RESERVATION_LEASE_DURATION)
                );

        UpdateResult result = mongoTemplate.updateFirst(
                query,
                update,
                ProviderVerification.class
        );

        return result.getMatchedCount() == 1;
    }

    /**
     * Releases only the reservation created by the corresponding upload.
     *
     * This operation is intentionally idempotent. If recovery has already
     * removed an expired reservation, the upload's finally block can safely
     * call this method without decrementing the counter a second time.
     */
    public void releaseDocumentUpload(
            String providerId,
            String reservationId
    ) {
        if (reservationId == null || reservationId.isBlank()) {
            return;
        }

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),
                        Criteria.where("inFlightUploadCount").gt(0),
                        Criteria.where("uploadReservations.reservationId").is(reservationId)
                )
        );

        Update update = new Update()
                .pull(
                        "uploadReservations",
                        new Document("reservationId", reservationId)
                )
                .inc("inFlightUploadCount", -1)
                .inc("coordinationRevision", 1);

        // No match means the reservation was already released or recovered.
        mongoTemplate.updateFirst(
                query,
                update,
                ProviderVerification.class
        );
    }

    /**
     * Recovers expired upload reservations for one provider.
     *
     * Each reservation is removed with a conditional atomic update that checks
     * its identity and expiry again. If a heartbeat renewed it after the scan,
     * the conditional update will not remove it.
     */
    public int recoverExpiredUploadReservations(
            String providerId,
            Instant now
    ) {
        migrateLegacyUploadReservations(providerId, now);

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),
                        Criteria.where("uploadReservations.leaseExpiresAt").lte(now)
                )
        );

        return recoverExpiredReservations(query, now);
    }

    /**
     * Recovers expired upload reservations across all providers.
     * Used during application startup and by the periodic recovery worker.
     */
    public int recoverExpiredUploadReservations(Instant now) {
        migrateLegacyUploadReservations(null, now);

        Query query = Query.query(
                Criteria.where("uploadReservations.leaseExpiresAt").lte(now)
        );

        return recoverExpiredReservations(query, now);
    }

    private int recoverExpiredReservations(
            Query candidateQuery,
            Instant now
    ) {
        List<ProviderVerification> candidates =
                mongoTemplate.find(candidateQuery, ProviderVerification.class);

        int recoveredCount = 0;

        for (ProviderVerification verification : candidates) {
            List<UploadReservation> reservations =
                    verification.getUploadReservations();

            if (reservations == null || reservations.isEmpty()) {
                continue;
            }

            for (UploadReservation reservation : reservations) {
                if (reservation == null
                        || reservation.getReservationId() == null
                        || reservation.getLeaseExpiresAt() == null) {
                    continue;
                }

                if (!reservation.getLeaseExpiresAt().isAfter(now)) {
                    boolean recovered = releaseExpiredReservation(
                            verification.getProviderId(),
                            reservation.getReservationId(),
                            now
                    );

                    if (recovered) {
                        recoveredCount++;
                    }
                }
            }
        }

        return recoveredCount;
    }

    /**
     * Removes one reservation only if it is still expired at update time.
     * This compare-and-set condition protects a reservation renewed by an
     * active upload after the recovery scan read the document.
     */
    private boolean releaseExpiredReservation(
            String providerId,
            String reservationId,
            Instant now
    ) {
        Criteria expiredReservation = new Criteria().andOperator(
                Criteria.where("reservationId").is(reservationId),
                Criteria.where("leaseExpiresAt").lte(now)
        );

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("providerId").is(providerId),
                        Criteria.where("inFlightUploadCount").gt(0),
                        Criteria.where("uploadReservations").elemMatch(expiredReservation)
                )
        );

        Update update = new Update()
                .pull(
                        "uploadReservations",
                        new Document("reservationId", reservationId)
                )
                .inc("inFlightUploadCount", -1)
                .inc("coordinationRevision", 1);

        UpdateResult result = mongoTemplate.updateFirst(
                query,
                update,
                ProviderVerification.class
        );

        return result.getMatchedCount() == 1;
    }

    /**
     * Older records created before uploadReservations existed may have a
     * positive inFlightUploadCount but no reservation identities.
     *
     * Do not reset those counters immediately: an upload from the previous
     * application instance may still be finishing during a deployment.
     * Convert each legacy count into a temporary lease starting now. It will
     * be recoverable after the normal lease duration if it was abandoned.
     */
    private void migrateLegacyUploadReservations(
            String providerId,
            Instant now
    ) {
        Query legacyQuery = new Query();
        legacyQuery.addCriteria(Criteria.where("inFlightUploadCount").gt(0));
        legacyQuery.addCriteria(noUploadReservationsCriteria());

        if (providerId != null) {
            legacyQuery.addCriteria(
                    Criteria.where("providerId").is(providerId)
            );
        }

        List<ProviderVerification> legacyRecords =
                mongoTemplate.find(legacyQuery, ProviderVerification.class);

        for (ProviderVerification legacyRecord : legacyRecords) {
            int legacyCount = legacyRecord.getInFlightUploadCount();

            if (legacyCount <= 0) {
                continue;
            }

            List<UploadReservation> migratedReservations =
                    new ArrayList<>(legacyCount);

            for (int index = 0; index < legacyCount; index++) {
                migratedReservations.add(
                        new UploadReservation(
                                "legacy-" + UUID.randomUUID(),
                                now,
                                now.plus(UPLOAD_RESERVATION_LEASE_DURATION)
                        )
                );
            }

            Query updateQuery = new Query(
                    new Criteria().andOperator(
                            Criteria.where("providerId")
                                    .is(legacyRecord.getProviderId()),
                            Criteria.where("inFlightUploadCount")
                                    .is(legacyCount),
                            noUploadReservationsCriteria()
                    )
            );

            Update update = new Update()
                    .set("uploadReservations", migratedReservations)
                    .inc("coordinationRevision", 1);

            mongoTemplate.updateFirst(
                    updateQuery,
                    update,
                    ProviderVerification.class
            );
        }
    }

    private Criteria noUploadReservationsCriteria() {
        return new Criteria().orOperator(
                Criteria.where("uploadReservations").exists(false),
                Criteria.where("uploadReservations").is(null),
                Criteria.where("uploadReservations").size(0)
        );
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
        recoverExpiredUploadReservations(providerId, now);

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
        recoverExpiredUploadReservations(providerId, now);

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
     * Coordinates category changes with the provider verification workflow.
     *
     * - APPROVED providers are moved to RESUBMISSION_REQUESTED.
     * - SUBMITTED and UNDER_REVIEW providers cannot change categories while
     *   an Admin review is in progress.
     * - SUBMITTABLE statuses retain their current status, but their workflow
     *   revision is incremented so an older submission cannot be committed.
     * - Other statuses retain the existing behaviour and are not modified.
     *
     * Existing Admin review metadata and the submitted document snapshot are
     * preserved. EXPIRED records also retain updatedAt because that timestamp
     * is used by the existing expired-document validation workflow.
     */
    public Optional<ProviderVerification> requestCategoryReverification(
            String providerId,
            Instant now
    ) {
        // Retry when the verification status changes concurrently between
        // the conditional update and the subsequent status check.
        for (int attempt = 0; attempt < 3; attempt++) {

            // An approved decision must not cover a category added later.
            Query approvedQuery = new Query(
                    new Criteria().andOperator(
                            Criteria.where("providerId").is(providerId),
                            Criteria.where("status")
                                    .is(ProviderVerificationStatus.APPROVED)
                    )
            );

            Update approvedUpdate = new Update()
                    .set(
                            "status",
                            ProviderVerificationStatus.RESUBMISSION_REQUESTED
                    )
                    .set(
                            "decisionReason",
                            "A new service category was added. Please upload updated verification documents and resubmit."
                    )
                    .set("updatedAt", now)
                    .inc("coordinationRevision", 1);

            ProviderVerification approvedUpdated = mongoTemplate.findAndModify(
                    approvedQuery,
                    approvedUpdate,
                    FindAndModifyOptions.options().returnNew(true),
                    ProviderVerification.class
            );

            if (approvedUpdated != null) {
                return Optional.of(approvedUpdated);
            }

            // For statuses that can be submitted, increment the revision
            // atomically. This prevents a submission using an older category
            // selection from succeeding after the category change.
            //
            // Do not update updatedAt here: EXPIRED verification uses it
            // when checking whether uploaded documents are newer than expiry.
            Query submittableQuery = new Query(
                    new Criteria().andOperator(
                            Criteria.where("providerId").is(providerId),
                            Criteria.where("status").in(SUBMITTABLE_STATUSES)
                    )
            );

            Update revisionUpdate = new Update()
                    .inc("coordinationRevision", 1);

            ProviderVerification submittableUpdated = mongoTemplate.findAndModify(
                    submittableQuery,
                    revisionUpdate,
                    FindAndModifyOptions.options().returnNew(true),
                    ProviderVerification.class
            );

            if (submittableUpdated != null) {
                return Optional.of(submittableUpdated);
            }

            // Neither conditional update matched. Read the latest status
            // to distinguish an in-progress review from a concurrent change.
            Optional<ProviderVerification> latest =
                    providerVerificationRepository.findByProviderId(providerId);

            if (latest.isEmpty()) {
                // A provider may not have a verification record yet.
                return Optional.empty();
            }

            ProviderVerificationStatus currentStatus =
                    latest.get().getStatus();

            if (currentStatus == ProviderVerificationStatus.SUBMITTED
                    || currentStatus == ProviderVerificationStatus.UNDER_REVIEW) {
                throw new ResourceConflictException(
                        "PROVIDER_VERIFICATION_IN_PROGRESS",
                        "You cannot add a new service category while your verification is submitted or under review. Please wait until the current review is complete."
                );
            }

            // The status may have changed to APPROVED or to a submittable
            // status after the conditional updates. Retry so the appropriate
            // atomic update can be applied to the latest state.
            if (currentStatus == ProviderVerificationStatus.APPROVED
                    || (currentStatus != null
                    && SUBMITTABLE_STATUSES.contains(currentStatus))) {
                continue;
            }

            // Preserve the existing behaviour for statuses outside the
            // approved, in-progress and submittable workflow states.
            return Optional.empty();
        }

        // Repeated concurrent status changes should fail safely rather than
        // allow a category update against an uncertain verification state.
        throw new ResourceConflictException(
                "PROVIDER_VERIFICATION_CHANGED",
                "Your verification status changed while updating categories. Refresh your profile and try again."
        );
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
        recoverExpiredUploadReservations(providerId, now);

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
