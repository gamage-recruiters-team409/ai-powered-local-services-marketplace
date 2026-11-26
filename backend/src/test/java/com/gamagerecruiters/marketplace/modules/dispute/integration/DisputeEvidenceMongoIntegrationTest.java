package com.gamagerecruiters.marketplace.modules.dispute.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceType;
import com.gamagerecruiters.marketplace.modules.dispute.dto.EvidenceView;
import com.gamagerecruiters.marketplace.modules.dispute.port.EvidenceStoragePort;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeAdminQueryService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeEvidenceService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;

/**
 * Evidence against a REAL MongoDB in a throw-away Testcontainers instance. Evidence storage is a mock (never a real
 * Cloudinary call). Refuses to run against a database named "marketplace".
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@TestPropertySource(properties = {
        "app.dispute.booking-sync.enabled=false",
        "app.dispute.finalizer.enabled=false",
        "app.announcement.publisher.enabled=false"
})
class DisputeEvidenceMongoIntegrationTest {

    private static final String TEST_DATABASE = "dispute_evidence_it";
    private static final String FORBIDDEN_DATABASE = "marketplace";
    private static final String CUSTOMER = "customer-ev";
    private static final String PROVIDER = "provider-ev";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl(TEST_DATABASE);
        });
    }

    @BeforeAll
    static void startContainer() {
        if (!mongoDBContainer.isRunning()) {
            mongoDBContainer.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @MockitoBean
    private EvidenceStoragePort storage;
    @Autowired
    private DisputeEvidenceService evidenceService;
    @Autowired
    private DisputeService disputeService;
    @Autowired
    private DisputeAdminQueryService adminQueryService;
    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void refuseSharedDatabaseAndClean() {
        String dbName = mongoTemplate.getDb().getName();
        if (FORBIDDEN_DATABASE.equals(dbName)) {
            throw new IllegalStateException("Refusing to run: connected to the database named \"" + FORBIDDEN_DATABASE
                    + "\". This test deletes data and must only run against a throw-away database.");
        }
        assertThat(dbName).isEqualTo(TEST_DATABASE);
        mongoTemplate.remove(new Query(), Dispute.class);
        when(storage.isAvailable()).thenReturn(true);
        when(storage.store(anyString(), any(), any())).thenAnswer(invocation -> new EvidenceStoragePort.StoredEvidence(
                "disputes/" + invocation.getArgument(0) + "/" + UUID.randomUUID(),
                ((EvidenceType) invocation.getArgument(2)).format()));
        when(storage.signedUrl(anyString(), anyString(), any())).thenReturn("https://files.example.test/signed");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private String insert(DisputeStatus status) {
        Instant created = Instant.now().minus(Duration.ofHours(2));
        Dispute dispute = new Dispute();
        dispute.setBookingId("booking-ev-" + UUID.randomUUID());
        dispute.setCustomerId(CUSTOMER);
        dispute.setProviderId(PROVIDER);
        dispute.setRaisedBy(PROVIDER);
        dispute.setRaisedByRole(Role.PROVIDER);
        dispute.setCategory(DisputeCategory.NON_PAYMENT);
        dispute.setDescription("Evidence test");
        dispute.setCreatedAt(created);
        dispute.setResponseDueAt(created.plus(DisputeRules.RESPONSE_WINDOW));
        dispute.setBookingSyncState(BookingSyncState.SYNCED);
        dispute.changeStatus(status, PROVIDER, Role.PROVIDER, null, created);
        return mongoTemplate.insert(dispute).getId();
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("both parties upload; files and per-party counts are stored; party, admin and AI summary views list "
            + "them (no storage id); the signed URL works for a party")
    void uploadAndViews() {
        String id = insert(DisputeStatus.AWAITING_RESPONSE);

        EvidenceView first = evidenceService.upload(PROVIDER, id, file("unpaid-invoice.pdf", PDF));
        evidenceService.upload(CUSTOMER, id, file("../receipt.png", PNG));

        Dispute stored = mongoTemplate.findById(id, Dispute.class);
        assertThat(stored.getEvidenceFiles()).hasSize(2);
        assertThat(stored.getEvidenceCounts()).containsEntry("PROVIDER", 1).containsEntry("CUSTOMER", 1);
        assertThat(stored.getEvidenceFiles().get(0).getPublicId()).startsWith("disputes/" + id + "/");

        var party = disputeService.getDisputeForParty(CUSTOMER, id);
        assertThat(party.evidenceFiles()).extracting(EvidenceView::fileName)
                .containsExactly("unpaid-invoice.pdf", "receipt.png");
        assertThat(party.toString()).doesNotContain("disputes/" + id);

        loginAs("admin-1", Role.ADMIN);
        assertThat(adminQueryService.getDetail(id).evidenceFiles()).hasSize(2);
        assertThat(disputeService.getDisputeForAiSummary(id).evidence())
                .extracting(e -> e.fileName() + ":" + e.type())
                .containsExactly("unpaid-invoice.pdf:PDF", "receipt.png:PNG");

        assertThat(evidenceService.signedUrl(CUSTOMER, false, id, first.id()).url()).isNotBlank();
    }

    @Test
    @DisplayName("8 uploads of the same party at the same moment -> exactly 5 recorded, the rest 409 "
            + "EVIDENCE_LIMIT_REACHED, and their stored files are deleted")
    void concurrentUploadsRespectLimit() throws Exception {
        String id = insert(DisputeStatus.UNDER_REVIEW);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<EvidenceView>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                Callable<EvidenceView> task = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return evidenceService.upload(CUSTOMER, id, file("photo.png", PNG));
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            int ok = 0;
            int limited = 0;
            for (Future<EvidenceView> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    ok++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) e.getCause()).getErrorCode()).isEqualTo("EVIDENCE_LIMIT_REACHED");
                    limited++;
                }
            }
            assertThat(ok).isEqualTo(DisputeRules.MAX_EVIDENCE_FILES_PER_PARTY);
            assertThat(limited).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }

        Dispute stored = mongoTemplate.findById(id, Dispute.class);
        assertThat(stored.getEvidenceFiles()).hasSize(5);
        assertThat(stored.getEvidenceCounts()).containsEntry("CUSTOMER", 5);
    }

    @Test
    @DisplayName("once RESOLVED -> 409 EVIDENCE_UPLOAD_CLOSED and nothing is stored")
    void closedAfterReview() {
        String id = insert(DisputeStatus.RESOLVED);

        Throwable thrown = catchThrowable(() -> evidenceService.upload(CUSTOMER, id, file("late.png", PNG)));

        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo("EVIDENCE_UPLOAD_CLOSED");
        assertThat(mongoTemplate.findById(id, Dispute.class).getEvidenceFiles()).isEmpty();
    }
}
