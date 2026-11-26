package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceFile;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceCleanupTask;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceType;
import com.gamagerecruiters.marketplace.modules.dispute.dto.EvidenceUrlResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.EvidenceView;
import com.gamagerecruiters.marketplace.modules.dispute.port.EvidenceStoragePort;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Evidence rules with a fake storage (no real Cloudinary call) and a mocked
 * MongoTemplate.
 */
class DisputeEvidenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String ID = "dispute-1";
    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";

    static final byte[] JPG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10);
    static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0);
    static final byte[] WEBP = bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 'V', 'P');
    static final byte[] PDF = bytes('%', 'P', 'D', 'F', '-', '1', '.', '7');
    static final byte[] EXE = bytes('M', 'Z', 0x90, 0, 3, 0);

    private MongoTemplate mongoTemplate;
    private FakeStorage storage;
    private EvidenceCleanupStore cleanupStore;
    private DisputeEvidenceService service;
    private Dispute dispute;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    /** In-memory evidence storage: records calls, never touches the network. */
    static final class FakeStorage implements EvidenceStoragePort {
        boolean available = true;
        boolean failUpload;
        boolean failDelete;
        final List<String> stored = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        Instant lastExpiry;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public StoredEvidence store(String disputeId, byte[] content, EvidenceType type) {
            if (failUpload) {
                throw new IllegalStateException("upload failed");
            }
            String publicId = "disputes/" + disputeId + "/random-" + stored.size();
            stored.add(publicId);
            return new StoredEvidence(publicId, type.format());
        }

        @Override
        public String signedUrl(String publicId, String format, Instant expiresAt) {
            lastExpiry = expiresAt;
            return "https://files.example.test/" + publicId + "." + format + "?signature=test";
        }

        @Override
        public void delete(String publicId) {
            if (failDelete) {
                throw new IllegalStateException("delete failed");
            }
            deleted.add(publicId);
        }

        @Override
        public List<StorageAsset> listAssets(String folderPrefix) {
            return stored.stream()
                    .filter(id -> id.startsWith(folderPrefix))
                    .filter(id -> !deleted.contains(id))
                    .map(id -> new StorageAsset(id, Instant.EPOCH))
                    .toList();
        }
    }

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        storage = new FakeStorage();
        cleanupStore = mock(EvidenceCleanupStore.class);
        service = new DisputeEvidenceService(mongoTemplate, storage, cleanupStore, Clock.fixed(NOW, ZoneOffset.UTC));
        dispute = dispute(DisputeStatus.AWAITING_RESPONSE);
        when(mongoTemplate.findById(ID, Dispute.class)).thenAnswer(i -> dispute);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Dispute.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        logger = (Logger) LoggerFactory.getLogger(DisputeEvidenceService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private static Dispute dispute(DisputeStatus status) {
        Dispute d = new Dispute();
        d.setId(ID);
        d.setBookingId("booking-1");
        d.setCustomerId(CUSTOMER);
        d.setProviderId(PROVIDER);
        d.setRaisedBy(CUSTOMER);
        d.setRaisedByRole(Role.CUSTOMER);
        d.setBookingSyncState(BookingSyncState.SYNCED);
        d.changeStatus(status, CUSTOMER, Role.CUSTOMER, null, NOW);
        return d;
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    // ---------- types by content ----------

    @ParameterizedTest
    @CsvSource({ "JPG,photo.jpg", "PNG,screen.png", "WEBP,image.webp", "PDF,receipt.pdf" })
    @DisplayName("JPG, PNG, WEBP and PDF are accepted, detected from the content; stored under a random id")
    void acceptedTypes(EvidenceType type, String name) {
        byte[] content = switch (type) {
            case JPG -> JPG;
            case PNG -> PNG;
            case WEBP -> WEBP;
            case PDF -> PDF;
        };

        EvidenceView view = service.upload(CUSTOMER, ID, file(name, content));

        assertThat(view.type()).isEqualTo(type);
        assertThat(view.contentType()).isEqualTo(type.mediaType());
        assertThat(view.uploadedByRole()).isEqualTo(Role.CUSTOMER);
        assertThat(view.uploadedAt()).isEqualTo(NOW);
        assertThat(view.sizeBytes()).isEqualTo(content.length);
        assertThat(storage.stored).singleElement().asString().startsWith("disputes/" + ID + "/").doesNotContain(name);
    }

    @Test
    @DisplayName("the extension does not count: an executable named .png -> 400 EVIDENCE_TYPE_NOT_ALLOWED, nothing "
            + "stored; a real PNG named .txt is accepted as PNG (file name gets the detected extension)")
    void typeByMagicBytes() {
        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, ID, file("photo.png", EXE))),
                HttpStatus.BAD_REQUEST, "EVIDENCE_TYPE_NOT_ALLOWED");
        assertThat(storage.stored).isEmpty();

        EvidenceView view = service.upload(CUSTOMER, ID, file("notes.txt", PNG));
        assertThat(view.type()).isEqualTo(EvidenceType.PNG);
        assertThat(view.fileName()).isEqualTo("notes.png");
    }

    @Test
    @DisplayName("over 5 MB -> 400 EVIDENCE_TOO_LARGE; empty or missing file -> 400 EVIDENCE_FILE_REQUIRED")
    void sizeAndEmpty() {
        byte[] big = new byte[(int) DisputeRules.MAX_EVIDENCE_FILE_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, ID, file("big.png", big))),
                HttpStatus.BAD_REQUEST, "EVIDENCE_TOO_LARGE");
        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, ID, file("empty.png", new byte[0]))),
                HttpStatus.BAD_REQUEST, "EVIDENCE_FILE_REQUIRED");
        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, ID, null)),
                HttpStatus.BAD_REQUEST, "EVIDENCE_FILE_REQUIRED");

        byte[] exactly5Mb = new byte[(int) DisputeRules.MAX_EVIDENCE_FILE_BYTES];
        System.arraycopy(PDF, 0, exactly5Mb, 0, PDF.length);
        assertThat(service.upload(CUSTOMER, ID, file("max.pdf", exactly5Mb)).type()).isEqualTo(EvidenceType.PDF);
    }

    // ---------- who and when ----------

    @Test
    @DisplayName("not a party of the dispute -> 403 NOT_DISPUTE_PARTY, nothing stored; the side comes from the dispute")
    void partyOnly() {
        assertApiError(catchThrowable(() -> service.upload("stranger", ID, file("a.png", PNG))),
                HttpStatus.FORBIDDEN, "NOT_DISPUTE_PARTY");
        assertThat(storage.stored).isEmpty();

        assertThat(service.upload(PROVIDER, ID, file("a.png", PNG)).uploadedByRole()).isEqualTo(Role.PROVIDER);
    }

    @ParameterizedTest
    @EnumSource(value = DisputeStatus.class, names = { "RESOLVED", "APPEALED", "RESOLVED_FINAL", "CLOSED" })
    @DisplayName("after the review stage -> 409 EVIDENCE_UPLOAD_CLOSED, nothing stored")
    void closedStatuses(DisputeStatus status) {
        dispute = dispute(status);

        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, ID, file("a.png", PNG))),
                HttpStatus.CONFLICT, "EVIDENCE_UPLOAD_CLOSED");
        assertThat(storage.stored).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = DisputeStatus.class, names = { "OPEN", "AWAITING_RESPONSE", "UNDER_REVIEW" })
    @DisplayName("OPEN, AWAITING_RESPONSE and UNDER_REVIEW accept evidence")
    void openStatuses(DisputeStatus status) {
        dispute = dispute(status);

        assertThat(service.upload(CUSTOMER, ID, file("a.png", PNG)).id()).isNotBlank();
    }

    @Test
    @DisplayName("PENDING (unsynced) or unknown dispute -> 404 DISPUTE_NOT_FOUND")
    void notFound() {
        dispute.setBookingSyncState(BookingSyncState.PENDING);
        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, ID, file("a.png", PNG))),
                HttpStatus.NOT_FOUND, "DISPUTE_NOT_FOUND");
        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, "missing", file("a.png", PNG))),
                HttpStatus.NOT_FOUND, "DISPUTE_NOT_FOUND");
    }

    // ---------- count ----------

    @Test
    @DisplayName("5 files of the same party already -> 409 EVIDENCE_LIMIT_REACHED; the other party can still upload")
    void perPartyLimit() {
        dispute.setEvidenceCounts(Map.of("CUSTOMER", DisputeRules.MAX_EVIDENCE_FILES_PER_PARTY));

        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, ID, file("a.png", PNG))),
                HttpStatus.CONFLICT, "EVIDENCE_LIMIT_REACHED");
        assertThat(service.upload(PROVIDER, ID, file("a.png", PNG)).uploadedByRole()).isEqualTo(Role.PROVIDER);
    }

    @Test
    @DisplayName("the conditional record update loses a race (limit reached meanwhile) -> stored file deleted, 409")
    void recordRaceDeletesStoredFile() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Dispute.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null));

        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, ID, file("a.png", PNG))),
                HttpStatus.CONFLICT, "EVIDENCE_LIMIT_REACHED");
        assertThat(storage.deleted).containsExactlyElementsOf(storage.stored);
    }

    // ---------- storage ----------

    @Test
    @DisplayName("storage not configured -> 503 EVIDENCE_STORAGE_UNAVAILABLE; upload failure -> 503 "
            + "EVIDENCE_UPLOAD_FAILED; nothing recorded")
    void storageProblems() {
        storage.available = false;
        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, ID, file("a.png", PNG))),
                HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_STORAGE_UNAVAILABLE");

        storage.available = true;
        storage.failUpload = true;
        assertApiError(catchThrowable(() -> service.upload(CUSTOMER, ID, file("a.png", PNG))),
                HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_UPLOAD_FAILED");
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(Dispute.class));
    }

    // ---------- signed URL ----------

    private EvidenceFile stored(String id) {
        EvidenceFile file = new EvidenceFile();
        file.setId(id);
        file.setPublicId("disputes/" + ID + "/random-x");
        file.setFormat("pdf");
        file.setFileName("receipt.pdf");
        file.setType(EvidenceType.PDF);
        file.setUploadedByRole(Role.PROVIDER);
        return file;
    }

    @Test
    @DisplayName("signed URL: both parties and admins; expires after 5 minutes")
    void signedUrlAccess() {
        dispute.setEvidenceFiles(List.of(stored("ev-1")));

        EvidenceUrlResponse forCustomer = service.signedUrl(CUSTOMER, false, ID, "ev-1");
        assertThat(forCustomer.expiresAt()).isEqualTo(NOW.plus(DisputeRules.EVIDENCE_URL_TTL));
        assertThat(storage.lastExpiry).isEqualTo(NOW.plusSeconds(300));
        assertThat(service.signedUrl(PROVIDER, false, ID, "ev-1").url()).contains("signature");
        assertThat(service.signedUrl("admin-1", true, ID, "ev-1").url()).isNotBlank();
    }

    @Test
    @DisplayName("signed URL: a non-party -> 403; unknown evidence -> 404 EVIDENCE_NOT_FOUND; storage off -> 503")
    void signedUrlDenied() {
        dispute.setEvidenceFiles(List.of(stored("ev-1")));

        assertApiError(catchThrowable(() -> service.signedUrl("stranger", false, ID, "ev-1")),
                HttpStatus.FORBIDDEN, "NOT_DISPUTE_PARTY");
        assertApiError(catchThrowable(() -> service.signedUrl(CUSTOMER, false, ID, "ev-unknown")),
                HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND");
        storage.available = false;
        assertApiError(catchThrowable(() -> service.signedUrl(CUSTOMER, false, ID, "ev-1")),
                HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_STORAGE_UNAVAILABLE");
    }

    // ---------- names and logs ----------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "../../etc/passwd.png|passwd.png",
            "C:\\Users\\me\\My Photo (1).jpg|My Photo (1).png",
            "<script>alert(1)</script>.png|script_.png",
            "'.png'|evidence.png",
            "|evidence.png" })
    @DisplayName("file names are sanitised: no path, safe characters only, detected extension")
    void sanitizedNames(String original, String expected) {
        assertThat(DisputeEvidenceService.sanitizeFileName(original, EvidenceType.PNG)).isEqualTo(expected);
    }

    @Test
    @DisplayName("logs contain ids only: never the file name, the storage id or a URL")
    void logsHaveIdsOnly() {
        dispute.setEvidenceFiles(List.of(stored("ev-1")));
        service.upload(CUSTOMER, ID, file("secret-name.png", PNG));
        service.signedUrl(CUSTOMER, false, ID, "ev-1");

        assertThat(logs.list).isNotEmpty().allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("secret-name").doesNotContain("random-").doesNotContain("https://"));
    }

    // ---------- post-upload failure recovery (F6) ----------

    @Test
    @DisplayName("Cloudinary upload succeeds but MongoDB record throws → asset deleted, 503 EVIDENCE_UPLOAD_FAILED")
    void mongoExceptionAfterUploadDeletesOrphan() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Dispute.class)))
                .thenThrow(new RuntimeException("database timeout"));

        Throwable thrown = catchThrowable(() -> service.upload(CUSTOMER, ID, file("a.png", PNG)));

        assertApiError(thrown, HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_UPLOAD_FAILED");
        assertThat(storage.stored).hasSize(1);
        assertThat(storage.deleted).containsExactlyElementsOf(storage.stored);
        // Confirmed-not-persisted and deleted cleanly: no durable cleanup task needed.
        verifyNoInteractions(cleanupStore);
    }

    @Test
    @DisplayName("MongoDB returns no match and Cloudinary deletion fails → 409 still thrown, durable cleanup task queued")
    void recordRaceWithDeleteFailure() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Dispute.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null));
        storage.failDelete = true;

        Throwable thrown = catchThrowable(() -> service.upload(CUSTOMER, ID, file("a.png", PNG)));

        assertApiError(thrown, HttpStatus.CONFLICT, "EVIDENCE_LIMIT_REACHED");
        assertThat(storage.stored).hasSize(1);
        // Delete was attempted (but failed): the orphan is logged AND a durable cleanup task is queued for retry.
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("DISPUTE_EVIDENCE_ORPHAN_DELETE_FAILED"));
        verify(cleanupStore).enqueue(eq(ID), any(), eq(storage.stored.get(0)),
                eq(EvidenceCleanupTask.Reason.DELETE_FAILED), eq(NOW));
    }

    @Test
    @DisplayName("uncertain MongoDB write but evidence actually persisted → evidence kept, view returned, no deletion")
    void uncertainWriteKeepsPersistedEvidence() {
        // updateFirst throws — simulating a connection reset after the write actually
        // committed.
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Dispute.class)))
                .thenThrow(new RuntimeException("connection reset"));

        java.util.concurrent.atomic.AtomicBoolean firstCall = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(mongoTemplate.findById(ID, Dispute.class)).thenAnswer(invocation -> {
            Dispute d = dispute(DisputeStatus.AWAITING_RESPONSE);
            if (firstCall.compareAndSet(true, false)) {
                // First call: initial findVisible before upload
                return d;
            }
            // Second call: re-read in evidenceActuallyPersisted finding the file persisted
            // by publicId
            EvidenceFile persisted = new EvidenceFile();
            persisted.setId("persisted-id");
            persisted.setPublicId(storage.stored.get(0));
            persisted.setFormat("png");
            persisted.setType(EvidenceType.PNG);
            persisted.setUploadedByRole(Role.CUSTOMER);
            persisted.setUploadedAt(NOW);
            d.setEvidenceFiles(List.of(persisted));
            return d;
        });

        EvidenceView view = service.upload(CUSTOMER, ID, file("a.png", PNG));

        assertThat(view).isNotNull();
        assertThat(view.type()).isEqualTo(EvidenceType.PNG);
        assertThat(storage.stored).hasSize(1);
        assertThat(storage.deleted).as("persisted evidence must NOT be deleted").isEmpty();
        // Confirmed persisted inline: nothing to reconcile.
        verifyNoInteractions(cleanupStore);
    }

    @Test
    @DisplayName("uncertain write AND the verification re-read also fails → asset preserved (never deleted), durable "
            + "cleanup task queued, 503 returned")
    void uncertainWriteWithFailedVerificationPreservesAsset() {
        // The record update throws (write outcome unknown), and the follow-up re-read also throws — e.g. the database
        // connection is still down. A failed verification must NOT be treated as "not persisted".
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Dispute.class)))
                .thenThrow(new RuntimeException("connection reset"));

        java.util.concurrent.atomic.AtomicBoolean firstCall = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(mongoTemplate.findById(ID, Dispute.class)).thenAnswer(invocation -> {
            if (firstCall.compareAndSet(true, false)) {
                return dispute(DisputeStatus.AWAITING_RESPONSE); // initial findVisible
            }
            throw new RuntimeException("database unavailable"); // verification re-read also fails → UNKNOWN
        });

        Throwable thrown = catchThrowable(() -> service.upload(CUSTOMER, ID, file("a.png", PNG)));

        assertApiError(thrown, HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_UPLOAD_FAILED");
        assertThat(storage.stored).hasSize(1);
        assertThat(storage.deleted).as("asset must be preserved when persistence cannot be confirmed").isEmpty();
        verify(cleanupStore).enqueue(eq(ID), any(), eq(storage.stored.get(0)),
                eq(EvidenceCleanupTask.Reason.UNCERTAIN_WRITE), eq(NOW));
    }

    @Test
    @DisplayName("F6-C: Cloudinary upload succeeds, record update throws, verification read throws, AND cleanup-task "
            + "enqueue throws → asset preserved, fallback buffer retains task for discovery after DB restored")
    void uncertainWriteWithEnqueueFailurePreservesAssetAndRetainsFallback() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Dispute.class)))
                .thenThrow(new RuntimeException("connection reset"));

        java.util.concurrent.atomic.AtomicBoolean firstCall = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(mongoTemplate.findById(ID, Dispute.class)).thenAnswer(invocation -> {
            if (firstCall.compareAndSet(true, false)) {
                return dispute(DisputeStatus.AWAITING_RESPONSE); // initial findVisible
            }
            throw new RuntimeException("database unavailable"); // verification re-read fails → UNKNOWN
        });

        // Cleanup task insertion also throws (e.g. MongoDB outage prevents writing the recovery task)
        org.mockito.Mockito.doThrow(new RuntimeException("MongoDB unavailable"))
                .when(cleanupStore).enqueue(any(), any(), any(), any(), any());

        Throwable thrown = catchThrowable(() -> service.upload(CUSTOMER, ID, file("a.png", PNG)));

        // 1. Confirm that the asset is preserved while the outcome is unknown (not deleted)
        assertApiError(thrown, HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_UPLOAD_FAILED");
        assertThat(storage.stored).hasSize(1);
        assertThat(storage.deleted).as("asset must be preserved while outcome is unknown").isEmpty();

        // 2. Confirm that enqueue failure was logged and task was routed to durable fallback
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("DISPUTE_EVIDENCE_CLEANUP_ENQUEUE_FAILED"));
        verify(cleanupStore).enqueueFallback(eq(ID), any(), eq(storage.stored.get(0)),
                eq(EvidenceCleanupTask.Reason.UNCERTAIN_WRITE), eq(NOW));
    }
}
