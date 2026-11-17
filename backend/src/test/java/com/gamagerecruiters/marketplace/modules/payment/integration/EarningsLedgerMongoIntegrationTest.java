
package com.gamagerecruiters.marketplace.modules.payment.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.service.EarningsLedgerService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

/**
 * Integration tests for earnings ledger persistence and event idempotency.
 *
 * <p>
 * Each test uses an isolated local MongoDB database that is
 * removed after execution. No shared application database is used.
 * </p>
 *
 * <p>
 * These tests verify service behavior with a real unique eventKey
 * index. Production index initialization is tested separately.
 * </p>
 */
class EarningsLedgerMongoIntegrationTest {

    private static final String LOCAL_URI = "mongodb://127.0.0.1:27017";

    @Test
    void validEventIsPersistedWithDecimal128Amount() {
        withLedger((service, repository, template) -> {
            EarningsLedgerEntry result = service.appendEvent(
                    event("event-001", "provider-a", "1500.00"));

            assertNotNull(result.getId());
            assertEquals(1, repository.count());

            Document stored = template.getCollection("earnings_ledger")
                    .find(new Document("eventKey", "event-001"))
                    .first();

            assertNotNull(stored);
            // Verify that MongoDB persisted the amount as BSON Decimal128.
            assertInstanceOf(
                    org.bson.types.Decimal128.class,
                    stored.get("amount"));

            // Verify that the persisted monetary value retains its precision.
            assertEquals(
                    0,
                    new BigDecimal("1500.00").compareTo(
                            stored.get("amount", org.bson.types.Decimal128.class)
                                    .bigDecimalValue()));
        });
    }

    @Test
    void repeatedIdenticalEventDoesNotCreateDuplicate() {
        withLedger((service, repository, template) -> {
            EarningsLedgerEntry first = service.appendEvent(
                    event("event-001", "provider-a", "1500.00"));

            EarningsLedgerEntry retry = service.appendEvent(
                    event("event-001", "provider-a", "1500.00"));

            assertEquals(first.getId(), retry.getId());
            assertEquals(1, repository.count());
        });
    }

    @Test
    void conflictingEventKeyIsRejected() {
        withLedger((service, repository, template) -> {
            service.appendEvent(
                    event("event-001", "provider-a", "1500.00"));

            assertThrows(
                    IllegalStateException.class,
                    () -> service.appendEvent(
                            event("event-001", "provider-a", "2500.00")));

            assertEquals(1, repository.count());
        });
    }

    @Test
    void concurrentIdenticalEventsCreateOnlyOneRecord() throws Exception {
        withLedger((service, repository, template) -> {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);

            try {
                Future<EarningsLedgerEntry> first = executor.submit(() -> {
                    start.await();
                    return service.appendEvent(
                            event("event-concurrent", "provider-a", "1500.00"));
                });

                Future<EarningsLedgerEntry> second = executor.submit(() -> {
                    start.await();
                    return service.appendEvent(
                            event("event-concurrent", "provider-a", "1500.00"));
                });

                start.countDown();

                EarningsLedgerEntry firstResult = first.get(10, TimeUnit.SECONDS);
                EarningsLedgerEntry secondResult = second.get(10, TimeUnit.SECONDS);

                assertEquals(firstResult.getId(), secondResult.getId());
                assertEquals(1, repository.count());

            } catch (Exception exception) {
                throw new RuntimeException(exception);
            } finally {
                executor.shutdownNow();
            }
        });
    }

    @Test
    void providerHistoryIsScopedToRequestedProvider() {
        withLedger((service, repository, template) -> {
            service.appendEvent(
                    event("event-a1", "provider-a", "1000.00"));
            service.appendEvent(
                    event("event-a2", "provider-a", "2000.00"));
            service.appendEvent(
                    event("event-b1", "provider-b", "3000.00"));

            List<EarningsLedgerEntry> history = service.getProviderHistory("provider-a");

            assertEquals(2, history.size());
            assertTrue(history.stream()
                    .allMatch(entry -> "provider-a".equals(entry.getProviderId())));
        });
    }

    /**
     * Executes one test against a uniquely named local database.
     */
    private void withLedger(LedgerTestAction action) {
        String databaseName = "wp07_earnings_ledger_test_"
                + UUID.randomUUID().toString().replace("-", "");

        try (MongoClient client = MongoClients.create(LOCAL_URI)) {
            MongoDatabase database = client.getDatabase(databaseName);

            try {
                MongoTemplate template = new MongoTemplate(
                        client, databaseName);

                // Reproduce the production uniqueness requirement.
                template.getCollection("earnings_ledger")
                        .createIndex(
                                Indexes.ascending("eventKey"),
                                new IndexOptions()
                                        .name("eventKey_1")
                                        .unique(true));

                MongoRepositoryFactory factory = new MongoRepositoryFactory(template);

                EarningsLedgerRepository repository = factory.getRepository(EarningsLedgerRepository.class);

                EarningsLedgerService service = new EarningsLedgerService(repository, template);

                action.execute(service, repository, template);

            } finally {
                // Delete only this test's temporary database.
                database.drop();
            }
        }
    }

    @FunctionalInterface
    private interface LedgerTestAction {
        void execute(
                EarningsLedgerService service,
                EarningsLedgerRepository repository,
                MongoTemplate template);
    }

    /**
     * Creates a structurally valid financial event.
     */
    private EarningsLedgerEntry event(
            String eventKey,
            String providerId,
            String amount) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey(eventKey);
        entry.setPaymentId("payment-001");
        entry.setBookingId("booking-001");
        entry.setProviderId(providerId);
        entry.setEventType(EarningsLedgerEventType.EARNING_PENDING);
        entry.setAmount(new BigDecimal(amount));
        entry.setCurrency("LKR");
        entry.setCreatedAt(Instant.parse("2026-11-16T10:00:00Z"));

        return entry;
    }
}
