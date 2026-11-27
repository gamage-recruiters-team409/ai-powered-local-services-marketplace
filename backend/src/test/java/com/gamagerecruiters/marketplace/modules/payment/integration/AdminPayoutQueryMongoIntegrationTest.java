package com.gamagerecruiters.marketplace.modules.payment.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutStatus;
import com.gamagerecruiters.marketplace.modules.payment.dto.PayoutSummaryResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.AdminPayoutQueryService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

/**
 * Verifies administrative payout queries against real MongoDB.
 *
 * Each test uses a dedicated temporary database inside an isolated
 * Testcontainers MongoDB instance. Application and frontend test
 * databases are never accessed.
 */
@Tag("integration")
@DisabledIfSystemProperty(
        named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(
        named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class AdminPayoutQueryMongoIntegrationTest {

    private static final MongoDBContainer MONGO =
            new MongoDBContainer("mongo:7.0");

    private static final Instant START =
            Instant.parse("2026-11-01T00:00:00Z");

    private static final Instant END =
            Instant.parse("2026-12-01T00:00:00Z");

    private static final String PROVIDER_A = "provider-a";
    private static final String PROVIDER_B = "provider-b";
    private static final String ADMIN_ID = "finance-admin-test";

    private MongoClient client;
    private String databaseName;
    private MongoTemplate template;
    private AdminPayoutQueryService service;

    @BeforeAll
    static void startContainer() {
        if (!MONGO.isRunning()) {
            MONGO.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (MONGO.isRunning()) {
            MONGO.stop();
        }
    }

    @BeforeEach
    void setUp() {
        databaseName = "wp07_payout_query_"
                + UUID.randomUUID().toString().replace("-", "");

        client = MongoClients.create(MONGO.getReplicaSetUrl());

        template = new MongoTemplate(
                new SimpleMongoClientDatabaseFactory(
                        client, databaseName));

        service = new AdminPayoutQueryService(template);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                if (databaseName != null
                        && databaseName.startsWith("wp07_payout_query_")) {
                    client.getDatabase(databaseName).drop();
                }
            } finally {
                client.close();
            }
        }
    }

    @Test
    void emptyDatabaseReturnsZeroSummaryAndEmptyPage() {
        assertEquals(
                new PayoutSummaryResponse(0, 0, 0, 0),
                service.getSummary());

        var page = service.getPayouts(
                0, 10, null, null, null, null);

        assertTrue(page.getContent().isEmpty());
        assertEquals(0L, page.getTotalElements());
        assertEquals(0, page.getTotalPages());
    }

    @Test
    void summaryAggregatesAllPersistedLifecycleStatuses() {
        seed(PROVIDER_A, PayoutStatus.PENDING, START);
        seed(PROVIDER_B, PayoutStatus.PENDING, START);
        seed(PROVIDER_A, PayoutStatus.PROCESSING, START);
        seed(PROVIDER_A, PayoutStatus.PAID, START);
        seed(PROVIDER_B, PayoutStatus.FAILED, START);

        assertEquals(
                new PayoutSummaryResponse(2, 1, 1, 1),
                service.getSummary());
    }

    @Test
    void missingStatusesReturnZeroCounts() {
        seed(PROVIDER_A, PayoutStatus.PAID, START);

        assertEquals(
                new PayoutSummaryResponse(0, 0, 1, 0),
                service.getSummary());
    }

    @Test
    void exactPayoutIdSearchSupportsMongoObjectIdMapping() {
        Payout expected = seed(
                PROVIDER_A, PayoutStatus.PENDING, START);

        seed(PROVIDER_A, PayoutStatus.PENDING, START);

        assertTrue(ObjectId.isValid(expected.getId()));

        // Confirm that the identifier is stored as a BSON ObjectId.
        var raw = template.getCollection("provider_payouts")
                .find(new org.bson.Document(
                        "_id", new ObjectId(expected.getId())))
                .first();

        assertNotNull(raw);

        var page = service.getPayouts(
                0, 10, expected.getId(), null, null, null);

        assertEquals(1L, page.getTotalElements());
        assertEquals(
                expected.getId(),
                page.getContent().get(0).getId());
    }

    @Test
    void exactProviderIdSearchReturnsOnlyMatchingProvider() {
        seed(PROVIDER_A, PayoutStatus.PENDING, START);
        seed(PROVIDER_A, PayoutStatus.PAID, START);
        seed(PROVIDER_B, PayoutStatus.PENDING, START);

        var page = service.getPayouts(
                0, 10, "  " + PROVIDER_A + "  ",
                null, null, null);

        assertEquals(2L, page.getTotalElements());

        assertTrue(page.getContent().stream()
                .allMatch(payout ->
                        PROVIDER_A.equals(payout.getProviderId())));
    }

    @Test
    void searchDoesNotInterpretRegexOrPartialIdentifiers() {
        seed(PROVIDER_A, PayoutStatus.PENDING, START);
        seed(PROVIDER_B, PayoutStatus.PAID, START);

        for (String search : List.of(".*", "provider", "unknown")) {
            var page = service.getPayouts(
                    0, 10, search, null, null, null);

            assertEquals(0L, page.getTotalElements());
            assertTrue(page.getContent().isEmpty());
        }
    }

    @Test
    void statusFilterReturnsOnlyRequestedLifecycleState() {
        seed(PROVIDER_A, PayoutStatus.PENDING, START);
        seed(PROVIDER_A, PayoutStatus.PROCESSING, START);
        seed(PROVIDER_B, PayoutStatus.PROCESSING, START);
        seed(PROVIDER_B, PayoutStatus.PAID, START);

        var page = service.getPayouts(
                0, 10, null,
                PayoutStatus.PROCESSING, null, null);

        assertEquals(2L, page.getTotalElements());

        assertTrue(page.getContent().stream()
                .allMatch(payout ->
                        payout.getStatus() == PayoutStatus.PROCESSING));
    }

    @Test
    void dateRangeIncludesLowerAndExcludesUpperBoundary() {
        seed(PROVIDER_A, PayoutStatus.PENDING,
                START.minusMillis(1));

        Payout lower = seed(
                PROVIDER_A, PayoutStatus.PENDING, START);

        Payout inside = seed(
                PROVIDER_A, PayoutStatus.PENDING,
                END.minusMillis(1));

        seed(PROVIDER_A, PayoutStatus.PENDING, END);

        var page = service.getPayouts(
                0, 10, null, null, START, END);

        assertEquals(2L, page.getTotalElements());

        assertEquals(
                List.of(inside.getId(), lower.getId()),
                page.getContent().stream()
                        .map(Payout::getId)
                        .toList());
    }

    @Test
    void supportsIndependentDateBoundaries() {
        seed(PROVIDER_A, PayoutStatus.PENDING,
                START.minusMillis(1));

        seed(PROVIDER_A, PayoutStatus.PENDING, START);
        seed(PROVIDER_A, PayoutStatus.PENDING, END);

        assertEquals(
                2L,
                service.getPayouts(
                        0, 10, null, null, START, null)
                        .getTotalElements());

        assertEquals(
                2L,
                service.getPayouts(
                        0, 10, null, null, null, END)
                        .getTotalElements());
    }

    @Test
    void combinesIdentifierStatusAndDateFilters() {
        Payout expected = seed(
                PROVIDER_A, PayoutStatus.PENDING, START);

        seed(PROVIDER_B, PayoutStatus.PENDING, START);
        seed(PROVIDER_A, PayoutStatus.PAID, START);
        seed(PROVIDER_A, PayoutStatus.PENDING, END);

        var page = service.getPayouts(
                0, 10, PROVIDER_A,
                PayoutStatus.PENDING, START, END);

        assertEquals(1L, page.getTotalElements());

        assertEquals(
                expected.getId(),
                page.getContent().get(0).getId());
    }

    @Test
    void paginationUsesFilteredTotalRatherThanPageSize() {
        for (int i = 0; i < 5; i++) {
            seed(PROVIDER_A, PayoutStatus.PENDING,
                    START.plusSeconds(i));
        }

        seed(PROVIDER_B, PayoutStatus.PENDING, START);

        var first = service.getPayouts(
                0, 2, PROVIDER_A, null, null, null);

        var second = service.getPayouts(
                1, 2, PROVIDER_A, null, null, null);

        var last = service.getPayouts(
                2, 2, PROVIDER_A, null, null, null);

        assertEquals(5L, first.getTotalElements());
        assertEquals(5L, second.getTotalElements());
        assertEquals(5L, last.getTotalElements());

        assertEquals(3, first.getTotalPages());
        assertEquals(2, first.getContent().size());
        assertEquals(2, second.getContent().size());
        assertEquals(1, last.getContent().size());

        assertTrue(first.getContent().stream().noneMatch(
                firstPayout -> second.getContent().stream()
                        .anyMatch(secondPayout ->
                                firstPayout.getId()
                                        .equals(secondPayout.getId()))));
    }

    @Test
    void identicalCreationTimesUseDeterministicIdTieBreaker() {
        Payout first = seed(
                PROVIDER_A, PayoutStatus.PENDING, START);

        Payout second = seed(
                PROVIDER_A, PayoutStatus.PENDING, START);

        Payout third = seed(
                PROVIDER_A, PayoutStatus.PENDING, START);

        List<String> expected = List.of(
                first.getId(), second.getId(), third.getId())
                .stream()
                .sorted(java.util.Comparator.reverseOrder())
                .toList();

        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            var page = service.getPayouts(
                    pageNumber, 1, null, null, null, null);

            assertEquals(3L, page.getTotalElements());

            assertEquals(
                    expected.get(pageNumber),
                    page.getContent().get(0).getId());
        }
    }

    @Test
    void outOfRangePagePreservesFilteredTotal() {
        seed(PROVIDER_A, PayoutStatus.PENDING, START);
        seed(PROVIDER_A, PayoutStatus.PENDING, START);

        var page = service.getPayouts(
                5, 1, PROVIDER_A, null, null, null);

        assertTrue(page.getContent().isEmpty());
        assertEquals(2L, page.getTotalElements());
        assertEquals(2, page.getTotalPages());
    }

    @Test
    void globalSummaryRemainsIndependentOfListFilters() {
        seed(PROVIDER_A, PayoutStatus.PENDING, START);
        seed(PROVIDER_B, PayoutStatus.PAID, START);
        seed(PROVIDER_B, PayoutStatus.FAILED, END);

        var filtered = service.getPayouts(
                0, 1, PROVIDER_A,
                PayoutStatus.PENDING, START, END);

        assertEquals(1L, filtered.getTotalElements());

        assertEquals(
                new PayoutSummaryResponse(1, 0, 1, 1),
                service.getSummary());
    }

    /**
     * Persists a valid payout using the real domain lifecycle
     * and MongoDB mapping. No financial transaction service
     * or external bank transfer is invoked.
     */
    private Payout seed(
            String providerId,
            PayoutStatus status,
            Instant createdAt) {

        Payout payout = new Payout(
                UUID.randomUUID().toString(),
                providerId,
                new BigDecimal("5000.00"),
                "LKR");

        if (status != PayoutStatus.PENDING) {
            payout.markProcessing(ADMIN_ID);
        }

        if (status == PayoutStatus.PAID) {
            payout.markPaid(
                    ADMIN_ID,
                    "BANK-" + UUID.randomUUID());
        }

        if (status == PayoutStatus.FAILED) {
            payout.markFailed(
                    ADMIN_ID, "Test bank rejection");
        }

        ReflectionTestUtils.setField(
                payout, "createdAt", createdAt);

        return template.insert(payout);
    }
}
