package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceCleanupTask;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceFile;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceType;
import com.gamagerecruiters.marketplace.modules.dispute.dto.EvidenceUrlResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.EvidenceView;
import com.gamagerecruiters.marketplace.modules.dispute.mapper.DisputeMapper;
import com.gamagerecruiters.marketplace.modules.dispute.port.EvidenceStoragePort;

/**
 * Evidence files of a dispute.
 *
 * <p>
 * Upload (a party of the dispute, while it is OPEN, AWAITING_RESPONSE or
 * UNDER_REVIEW), checks in this order:
 * </p>
 * <ol>
 * <li>404 DISPUTE_NOT_FOUND (unknown or PENDING); 403 NOT_DISPUTE_PARTY;</li>
 * <li>409 EVIDENCE_UPLOAD_CLOSED when the status no longer allows
 * evidence;</li>
 * <li>400 EVIDENCE_FILE_REQUIRED (no or empty file); 400 EVIDENCE_TOO_LARGE
 * (over 5 MB);
 * 400 EVIDENCE_TYPE_NOT_ALLOWED (content is not JPG, PNG, WEBP or PDF: checked
 * by magic bytes);</li>
 * <li>409 EVIDENCE_LIMIT_REACHED (5 files per party);</li>
 * <li>503 EVIDENCE_STORAGE_UNAVAILABLE (storage not configured) / 503
 * EVIDENCE_UPLOAD_FAILED.</li>
 * </ol>
 * The file is stored first, then recorded with ONE conditional update that
 * repeats the status and per-party limit
 * checks, so concurrent uploads can never exceed the limit. If the conditional
 * update returns no match, the stored
 * file is deleted (best-effort). If the MongoDB update throws, the dispute is
 * re-read to determine whether the
 * evidence was actually committed before deciding to delete the remote asset —
 * a successfully recorded file is
 * never removed by an incorrect rollback. Delete failures are logged with
 * enough context for a cleanup process.
 *
 * <p>
 * Signed URL: the two parties and admins who can view the disputes area; anyone
 * else 403/404. Links expire after
 * {@link DisputeRules#EVIDENCE_URL_TTL}. Only ids are logged: never URLs, file
 * names or contents.
 * </p>
 */
@Service
public class DisputeEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(DisputeEvidenceService.class);

    private static final Pattern UNSAFE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9 ._()-]");

    private final MongoTemplate mongoTemplate;
    private final EvidenceStoragePort storage;
    private final EvidenceCleanupStore cleanupStore;
    private final Clock clock;

    @Autowired
    public DisputeEvidenceService(MongoTemplate mongoTemplate, EvidenceStoragePort storage,
            EvidenceCleanupStore cleanupStore) {
        this(mongoTemplate, storage, cleanupStore, Clock.systemUTC());
    }

    DisputeEvidenceService(MongoTemplate mongoTemplate, EvidenceStoragePort storage, EvidenceCleanupStore cleanupStore,
            Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.storage = storage;
        this.cleanupStore = cleanupStore;
        this.clock = clock;
    }

    /**
     * Whether an uncertain MongoDB write actually committed. {@code UNKNOWN} means
     * the verification read itself
     * failed — it is never treated as "not persisted", because deleting on an
     * unconfirmed read could destroy
     * successfully submitted evidence.
     */
    private enum Persistence {
        CONFIRMED_PERSISTED, CONFIRMED_NOT_PERSISTED, UNKNOWN
    }

    public EvidenceView upload(String actorId, String disputeId, MultipartFile file) {
        Dispute dispute = findVisible(disputeId);
        Role side = partySide(dispute, actorId);
        if (!DisputeRules.EVIDENCE_UPLOAD_STATUSES.contains(dispute.getStatus())) {
            throw uploadClosed();
        }
        byte[] content = readValidated(file);
        EvidenceType type = EvidenceType.detect(content).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "EVIDENCE_TYPE_NOT_ALLOWED", "Only JPG, PNG, WEBP or PDF files can be uploaded"));
        if (dispute.getEvidenceCounts().getOrDefault(side.name(), 0) >= DisputeRules.MAX_EVIDENCE_FILES_PER_PARTY) {
            throw limitReached();
        }
        if (!storage.isAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_STORAGE_UNAVAILABLE",
                    "Evidence uploads are not available right now");
        }

        EvidenceStoragePort.StoredEvidence stored;
        try {
            stored = storage.store(dispute.getId(), content, type);
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_EVIDENCE_UPLOAD_FAILED disputeId={} actorId={} cause={}",
                    dispute.getId(), actorId, e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_UPLOAD_FAILED",
                    "The file could not be uploaded. Please try again");
        }

        EvidenceFile evidence = new EvidenceFile();
        evidence.setId(UUID.randomUUID().toString());
        evidence.setPublicId(stored.publicId());
        evidence.setFormat(stored.format());
        evidence.setFileName(sanitizeFileName(file.getOriginalFilename(), type));
        evidence.setType(type);
        evidence.setSizeBytes(content.length);
        evidence.setUploadedByRole(side);
        evidence.setUploadedAt(clock.instant());

        boolean recorded;
        try {
            recorded = record(dispute.getId(), side, evidence);
        } catch (RuntimeException e) {
            // Uncertain write: the update may or may not have been committed. Re-read to
            // check before deleting —
            // a successfully recorded file must NOT be removed, and a failed verification
            // is NOT proof of a missing
            // write (deleting then would destroy evidence that actually persisted).
            switch (verifyPersisted(disputeId, evidence.getId(), stored.publicId())) {
                case CONFIRMED_PERSISTED -> {
                    log.warn("event=DISPUTE_EVIDENCE_RECORD_EXCEPTION_BUT_PERSISTED disputeId={} evidenceId={} "
                            + "cause={}", disputeId, evidence.getId(), e.getClass().getSimpleName());
                    return DisputeMapper.toView(evidence);
                }
                case UNKNOWN -> {
                    // Database outcome unresolved: keep the asset and queue a durable
                    // reconciliation task. The
                    // reconciler keeps it if the write committed, or deletes the orphan once
                    // MongoDB is reachable.
                    enqueueCleanup(disputeId, evidence.getId(), stored.publicId(),
                            EvidenceCleanupTask.Reason.UNCERTAIN_WRITE);
                    log.error("event=DISPUTE_EVIDENCE_RECORD_UNCERTAIN disputeId={} evidenceId={} publicId={} cause={}",
                            disputeId, evidence.getId(), stored.publicId(), e.getClass().getSimpleName());
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_UPLOAD_FAILED",
                            "The file could not be recorded. Please try again");
                }
                default -> {
                    log.error("event=DISPUTE_EVIDENCE_RECORD_FAILED disputeId={} evidenceId={} publicId={} cause={}",
                            disputeId, evidence.getId(), stored.publicId(), e.getClass().getSimpleName());
                    safeDelete(stored.publicId(), disputeId, evidence.getId());
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_UPLOAD_FAILED",
                            "The file could not be recorded. Please try again");
                }
            }
        }

        if (!recorded) {
            safeDelete(stored.publicId(), disputeId, evidence.getId());
            Dispute latest = findVisible(disputeId);
            if (!DisputeRules.EVIDENCE_UPLOAD_STATUSES.contains(latest.getStatus())) {
                throw uploadClosed();
            }
            throw limitReached();
        }
        log.info("event=DISPUTE_EVIDENCE_UPLOADED disputeId={} evidenceId={} actorId={} type={}",
                dispute.getId(), evidence.getId(), actorId, type);
        return DisputeMapper.toView(evidence);
    }

    /**
     * Signed link for one file. {@code admin} is true for callers allowed to view
     * the disputes admin area (checked by
     * the controller); everyone else must be a party of the dispute.
     */
    public EvidenceUrlResponse signedUrl(String actorId, boolean admin, String disputeId, String evidenceId) {
        Dispute dispute = findVisible(disputeId);
        if (!admin) {
            partySide(dispute, actorId);
        }
        EvidenceFile evidence = dispute.getEvidenceFiles().stream()
                .filter(e -> e.getId() != null && e.getId().equals(evidenceId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("EVIDENCE_NOT_FOUND", "Evidence file not found"));
        if (!storage.isAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_STORAGE_UNAVAILABLE",
                    "Evidence files cannot be opened right now");
        }
        Instant expiresAt = clock.instant().plus(DisputeRules.EVIDENCE_URL_TTL);
        String url;
        try {
            url = storage.signedUrl(evidence.getPublicId(), evidence.getFormat(), expiresAt);
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_EVIDENCE_LINK_FAILED disputeId={} evidenceId={} cause={}",
                    disputeId, evidenceId, e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_STORAGE_UNAVAILABLE",
                    "Evidence files cannot be opened right now");
        }
        log.info("event=DISPUTE_EVIDENCE_LINK_ISSUED disputeId={} evidenceId={} actorId={}",
                disputeId, evidenceId, actorId);
        return new EvidenceUrlResponse(url, expiresAt);
    }

    /**
     * Push the file and count it, only while the status and the per-party limit
     * still allow it.
     */
    private boolean record(String disputeId, Role side, EvidenceFile evidence) {
        String countField = "evidenceCounts." + side.name();
        Query query = Query.query(Criteria.where("_id").is(disputeId)
                .and("bookingSyncState").ne(BookingSyncState.PENDING)
                .and("status").in(DisputeRules.EVIDENCE_UPLOAD_STATUSES)
                .orOperator(Criteria.where(countField).exists(false),
                        Criteria.where(countField).lt(DisputeRules.MAX_EVIDENCE_FILES_PER_PARTY)));
        Update update = new Update()
                .push("evidenceFiles", evidence)
                .inc(countField, 1)
                .inc("version", 1);
        return mongoTemplate.updateFirst(query, update, Dispute.class).getModifiedCount() == 1;
    }

    /**
     * Re-reads the dispute after an uncertain MongoDB write (exception thrown but
     * the write may have gone through) to
     * decide whether the evidence record was actually committed. Returns one of
     * three outcomes:
     * <ul>
     * <li>{@code CONFIRMED_PERSISTED} — the re-read found the record: keep the
     * asset;</li>
     * <li>{@code CONFIRMED_NOT_PERSISTED} — the re-read succeeded and the record is
     * absent: the asset is an orphan;</li>
     * <li>{@code UNKNOWN} — the re-read itself failed, so the outcome cannot be
     * determined. This is never treated as
     * "not persisted": deleting on an unconfirmed read could permanently destroy
     * evidence that did commit.</li>
     * </ul>
     */
    private Persistence verifyPersisted(String disputeId, String evidenceId, String publicId) {
        try {
            Dispute latest = mongoTemplate.findById(disputeId, Dispute.class);
            boolean present = latest != null && latest.getEvidenceFiles() != null
                    && latest.getEvidenceFiles().stream()
                            .anyMatch(e -> (evidenceId != null && evidenceId.equals(e.getId()))
                                    || (publicId != null && publicId.equals(e.getPublicId())));
            return present ? Persistence.CONFIRMED_PERSISTED : Persistence.CONFIRMED_NOT_PERSISTED;
        } catch (RuntimeException e) {
            log.warn("event=DISPUTE_EVIDENCE_REREAD_FAILED disputeId={} evidenceId={} publicId={} cause={}",
                    disputeId, evidenceId, publicId, e.getClass().getSimpleName());
            return Persistence.UNKNOWN;
        }
    }

    /**
     * Best-effort deletion of an orphaned storage asset. When deletion fails, a
     * durable cleanup task is queued (and a
     * warning logged) so the orphan is revisited and removed by
     * {@link EvidenceCleanupReconciler}, never left behind
     * on the strength of a log line alone.
     */
    private void safeDelete(String publicId, String disputeId, String evidenceId) {
        try {
            storage.delete(publicId);
        } catch (RuntimeException e) {
            log.warn("event=DISPUTE_EVIDENCE_ORPHAN_DELETE_FAILED disputeId={} evidenceId={} publicId={} cause={}",
                    disputeId, evidenceId, publicId, e.getClass().getSimpleName());
            enqueueCleanup(disputeId, evidenceId, publicId, EvidenceCleanupTask.Reason.DELETE_FAILED);
        }
    }

    /**
     * Records a durable reconciliation task for a storage asset whose outcome is
     * unresolved. If MongoDB task
     * insertion fails (e.g. database connection interrupted), the task is retained
     * in the store's durable fallback
     * buffer ({@link EvidenceCleanupStore#enqueueFallback}) so the asset remains
     * discoverable and is reconciled once
     * database availability is restored (dispute finding F6-C).
     */
    private void enqueueCleanup(String disputeId, String evidenceId, String publicId,
            EvidenceCleanupTask.Reason reason) {
        try {
            cleanupStore.enqueue(disputeId, evidenceId, publicId, reason, clock.instant());
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_EVIDENCE_CLEANUP_ENQUEUE_FAILED disputeId={} evidenceId={} publicId={} reason={} "
                    + "cause={}", disputeId, evidenceId, publicId, reason, e.getClass().getSimpleName());
            cleanupStore.enqueueFallback(disputeId, evidenceId, publicId, reason, clock.instant());
        }
    }

    private Dispute findVisible(String disputeId) {
        Dispute dispute = disputeId == null ? null : mongoTemplate.findById(disputeId, Dispute.class);
        if (dispute == null || !dispute.isVisible()) {
            throw new ResourceNotFoundException("DISPUTE_NOT_FOUND", "Dispute not found");
        }
        return dispute;
    }

    /**
     * CUSTOMER or PROVIDER, from the dispute itself (never from the request). 403
     * for anyone else.
     */
    private static Role partySide(Dispute dispute, String actorId) {
        if (actorId != null && actorId.equals(dispute.getCustomerId())) {
            return Role.CUSTOMER;
        }
        if (actorId != null && actorId.equals(dispute.getProviderId())) {
            return Role.PROVIDER;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "NOT_DISPUTE_PARTY", "You are not a party to this dispute");
    }

    private static byte[] readValidated(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EVIDENCE_FILE_REQUIRED", "Choose a file to upload");
        }
        if (file.getSize() > DisputeRules.MAX_EVIDENCE_FILE_BYTES) {
            throw tooLarge();
        }
        try {
            byte[] content = file.getBytes();
            if (content.length > DisputeRules.MAX_EVIDENCE_FILE_BYTES) {
                throw tooLarge();
            }
            return content;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EVIDENCE_FILE_REQUIRED", "The file could not be read");
        }
    }

    /**
     * Display name only: the last path segment of the original name, unsafe
     * characters replaced, at most 120
     * characters, with the detected type's extension. Never used as a storage name.
     */
    static String sanitizeFileName(String original, EvidenceType type) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        base = UNSAFE_NAME_CHARS.matcher(base).replaceAll("_").replaceAll("_+", "_").strip();
        base = base.replaceAll("^[._]+", "");
        String extension = "." + type.format();
        int maxBase = DisputeRules.MAX_EVIDENCE_FILE_NAME_LENGTH - extension.length();
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase);
        }
        if (base.isBlank()) {
            base = "evidence";
        }
        return base + extension.toLowerCase(Locale.ROOT);
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.BAD_REQUEST, "EVIDENCE_TOO_LARGE", "Each file must be 5 MB or smaller");
    }

    private static ResourceConflictException uploadClosed() {
        return new ResourceConflictException("EVIDENCE_UPLOAD_CLOSED",
                "Evidence can no longer be added to this dispute");
    }

    private static ResourceConflictException limitReached() {
        return new ResourceConflictException("EVIDENCE_LIMIT_REACHED",
                "You can upload at most " + DisputeRules.MAX_EVIDENCE_FILES_PER_PARTY + " files to this dispute");
    }
}
