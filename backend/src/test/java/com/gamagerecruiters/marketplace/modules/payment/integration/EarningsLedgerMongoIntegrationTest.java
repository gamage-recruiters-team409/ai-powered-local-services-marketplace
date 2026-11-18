
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
import java.util.concurrent.ExecutionException;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import static org.mockito.Mockito.*;

import java.util.Optional;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionLookup;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.service.EarningsLifecycleService;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentPersistenceService;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.ProviderEarningsState;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.ProviderEarningsStateRepository;
import com.gamagerecruiters.marketplace.modules.payment.service.EarningsLedgerService;
import com.gamagerecruiters.marketplace.modules.payment.service.EarningsLedgerTransactionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

/**
 * Real MongoDB integration tests for earnings ledger persistence.
 *
 * <p>
 * Tests use an isolated local MongoDB replica set and a unique
 * temporary database per test. Shared application databases are
 * never modified.
 * </p>
 *
 * <p>
 * These tests verify transactional ledger persistence, event
 * idempotency, deterministic ordering, financial balance validation,
 * and concurrency protection required by PR #28.
 * </p>
 */
class EarningsLedgerMongoIntegrationTest {

    private static final String LOCAL_URI = "mongodb://127.0.0.1:27018/?replicaSet=wp07rs";

    @Test
    void validEventIsPersistedWithDecimal128Amount() {
        withLedger((service, repository, template) -> {

            EarningsLedgerEntry result = service.appendEvent(
                    event("event-001", "provider-a", "1500.00"));

            assertNotNull(result.getId());
            assertEquals(1L, result.getSequenceNumber());
            assertEquals(1, repository.count());

            Document stored = template.getCollection("earnings_ledger")
                    .find(new Document("eventKey", "event-001"))
                    .first();

            assertNotNull(stored);

            // Monetary values must retain MongoDB Decimal128 precision.
            assertInstanceOf(
                    Decimal128.class,
                    stored.get("amount"));

            assertEquals(
                    0,
                    new BigDecimal("1500.00").compareTo(
                            stored.get("amount", Decimal128.class)
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
            assertEquals(first.getSequenceNumber(), retry.getSequenceNumber());
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
    void concurrentIdenticalEventsCreateOnlyOneRecord() {
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

                EarningsLedgerEntry firstResult = first.get(15, TimeUnit.SECONDS);

                EarningsLedgerEntry secondResult = second.get(15, TimeUnit.SECONDS);

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
     * TL Correction 01:
     * Same-timestamp events must preserve financial processing order.
     */
    @Test
    void sameTimestampEventsReceiveDistinctProcessingSequences() {
        withLedger((service, repository, template) -> {

            EarningsLedgerEntry first = service.appendEvent(
                    event("event-001", "provider-a", "1000.00"));

            EarningsLedgerEntry second = service.appendEvent(
                    event("event-002", "provider-a", "500.00"));

            assertEquals(first.getCreatedAt(), second.getCreatedAt());
            assertEquals(1L, first.getSequenceNumber());
            assertEquals(2L, second.getSequenceNumber());
            assertEquals(2, repository.count());
        });
    }

    /**
     * TL Correction 02:
     * A balance-consuming event must be rejected when the provider
     * does not have sufficient available earnings.
     */
    @Test
    void insufficientBalanceIsRejectedWithoutLedgerInsertion() {
        withLedger((service, repository, template) -> {

            EarningsLedgerEntry debit = event(
                    "event-invalid-debit", "provider-a", "500.00");

            debit.setEventType(EarningsLedgerEventType.PAYOUT_DEBIT);

            assertThrows(
                    IllegalStateException.class,
                    () -> service.appendEvent(debit));

            assertEquals(0, repository.count());

            // A rejected event must not leave a financial state record.
            assertEquals(
                    0,
                    template.getCollection("provider_earnings_state")
                            .countDocuments());
        });
    }

    /**
     * TL Correction 02:
     * An invalid financial transition must not corrupt an existing
     * balance or create an additional ledger record.
     */
    @Test
    void invalidTransitionRollsBackLedgerAndBalanceChanges() {
        withLedger((service, repository, template) -> {

            service.appendEvent(
                    event("event-pending", "provider-a", "1000.00"));

            EarningsLedgerEntry invalid = event(
                    "event-invalid", "provider-a", "1500.00");

            invalid.setEventType(EarningsLedgerEventType.EARNING_AVAILABLE);

            assertThrows(
                    IllegalStateException.class,
                    () -> service.appendEvent(invalid));

            assertEquals(1, repository.count());

            ProviderEarningsState state = template.findById(
                    new ProviderEarningsState("provider-a", "LKR").getId(),
                    ProviderEarningsState.class);

            assertNotNull(state);
            assertEquals(0, new BigDecimal("1000.00")
                    .compareTo(state.getPending()));
            assertEquals(0, BigDecimal.ZERO
                    .compareTo(state.getAvailable()));
            assertEquals(1L, state.getLastSequenceNumber());
        });
    }

    /**
     * TL Correction 02:
     * Two concurrent payouts must not spend the same available balance.
     */
    @Test
    void concurrentOverspendingDoesNotCorruptLedger() {
        withLedger((service, repository, template) -> {

            service.appendEvent(
                    event("event-pending", "provider-a", "1000.00"));

            EarningsLedgerEntry available = event(
                    "event-available", "provider-a", "1000.00");

            available.setEventType(
                    EarningsLedgerEventType.EARNING_AVAILABLE);

            service.appendEvent(available);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);

            try {
                Future<Boolean> first = executor.submit(() -> {
                    start.await();
                    return attemptPayout(service, "payout-001");
                });

                Future<Boolean> second = executor.submit(() -> {
                    start.await();
                    return attemptPayout(service, "payout-002");
                });

                start.countDown();

                boolean firstSucceeded = first.get(15, TimeUnit.SECONDS);
                boolean secondSucceeded = second.get(15, TimeUnit.SECONDS);

                // Exactly one payout may consume the available balance.
                assertNotEquals(firstSucceeded, secondSucceeded);

                assertEquals(3, repository.count());

                ProviderEarningsState state = template.findById(
                        new ProviderEarningsState("provider-a", "LKR").getId(),
                        ProviderEarningsState.class);

                assertNotNull(state);
                assertEquals(0, BigDecimal.ZERO
                        .compareTo(state.getAvailable()));
                assertEquals(0, new BigDecimal("1000.00")
                        .compareTo(state.getPaidOut()));
                assertEquals(3L, state.getLastSequenceNumber());

            } catch (Exception exception) {
                throw new RuntimeException(exception);
            } finally {
                executor.shutdownNow();
            }
        });
    }

    /**
     * Attempts one payout and reports whether it committed.
     *
     * Only a rejected financial balance transition counts as an
     * expected unsuccessful payout. Other errors fail the test.
     */
    private boolean attemptPayout(
            EarningsLedgerService service,
            String eventKey) {

        EarningsLedgerEntry payout = event(
                eventKey, "provider-a", "1000.00");

        payout.setEventType(EarningsLedgerEventType.PAYOUT_DEBIT);

        try {
            service.appendEvent(payout);
            return true;

        } catch (IllegalStateException exception) {
            if (!"Ledger event would create a negative earnings balance"
                    .equals(exception.getMessage())) {
                throw exception;
            }

            return false;
        }
    }

    /**
     * PR #37: Verified completion must create one pending earnings event
     * and update the provider balance exactly once, including on retries.
     */
    @Test
    void verifiedCompletionPersistsPendingEarningsAndProviderStateAtomically() {
        withLedger((ledgerService, ledgerRepository, template) -> {

            Payment payment = new Payment();
            payment.setId("payment-001");
            payment.setBookingId("booking-001");
            payment.setCustomerId("customer-001");
            payment.setAmount(new BigDecimal("1000.00"));
            payment.setCommissionAmount(new BigDecimal("150.00"));
            payment.setCurrency("LKR");
            payment.setStatus(PaymentStatus.CAPTURED);

            PaymentPersistenceService paymentService =
                    mock(PaymentPersistenceService.class);

            when(paymentService.findPaymentById("payment-001"))
                    .thenReturn(Optional.of(payment));

            BookingFacade bookingFacade = mock(BookingFacade.class);

            when(bookingFacade.getBookingParticipants("booking-001"))
                    .thenReturn(new BookingParticipants(
                            "booking-001",
                            null,
                            "listing-001",
                            "customer-001",
                            "provider-001",
                            BookingStatus.COMPLETED,
                            null,
                            null));

            CompletionLookup completionLookup = mock(CompletionLookup.class);

            when(completionLookup.findConfirmedAt("booking-001"))
                    .thenReturn(Optional.of(
                            Instant.parse("2026-11-18T10:00:00Z")));

            EarningsLifecycleService lifecycleService =
                    new EarningsLifecycleService(
                            paymentService,
                            bookingFacade,
                            completionLookup,
                            ledgerService);

            EarningsLedgerEntry first =
                    lifecycleService.recordPendingEarnings("payment-001");

            assertEquals(EarningsLedgerEventType.EARNING_PENDING,
                    first.getEventType());
            assertEquals("earning-pending:payment-001", first.getEventKey());
            assertEquals(0, new BigDecimal("850.00")
                    .compareTo(first.getAmount()));

            assertEquals(1, ledgerRepository.count());

            ProviderEarningsState state = template.findById(
                    new ProviderEarningsState("provider-001", "LKR").getId(),
                    ProviderEarningsState.class);

            assertNotNull(state);
            assertEquals(0, new BigDecimal("850.00")
                    .compareTo(state.getPending()));
            assertEquals(0, BigDecimal.ZERO
                    .compareTo(state.getAvailable()));
            assertEquals(1L, state.getLastSequenceNumber());

            // Reprocessing must reuse the committed event and balance.
            EarningsLedgerEntry retry =
                    lifecycleService.recordPendingEarnings("payment-001");

            assertEquals(first.getId(), retry.getId());
            assertEquals(1, ledgerRepository.count());

            ProviderEarningsState stateAfterRetry = template.findById(
                    new ProviderEarningsState("provider-001", "LKR").getId(),
                    ProviderEarningsState.class);

            assertNotNull(stateAfterRetry);
            assertEquals(0, new BigDecimal("850.00")
                    .compareTo(stateAfterRetry.getPending()));
            assertEquals(1L, stateAfterRetry.getLastSequenceNumber());
        });
    }

    /**
     * Runs one test against an isolated temporary MongoDB database.
     */

    private void withLedger(LedgerTestAction action) {

        String databaseName = "wp07_earnings_ledger_test_"
                + UUID.randomUUID().toString().replace("-", "");

        try (MongoClient client = MongoClients.create(LOCAL_URI)) {

            MongoDatabase database = client.getDatabase(databaseName);

            try {
                MongoTemplate template = new MongoTemplate(client, databaseName);

                // Create collections before starting transactions.
                template.createCollection("earnings_ledger");
                template.createCollection("provider_earnings_state");

                // Reproduce the production event-key uniqueness rule.
                template.getCollection("earnings_ledger")
                        .createIndex(
                                Indexes.ascending("eventKey"),
                                new IndexOptions()
                                        .name("eventKey_1")
                                        .unique(true));

                MongoRepositoryFactory factory = new MongoRepositoryFactory(template);

                EarningsLedgerRepository repository = factory.getRepository(EarningsLedgerRepository.class);

                ProviderEarningsStateRepository stateRepository = factory.getRepository(
                        ProviderEarningsStateRepository.class);

                // Use the same database and session for both writes.
                MongoTransactionManager transactionManager = new MongoTransactionManager(
                        template.getMongoDatabaseFactory());

                EarningsLedgerTransactionService transactionService = new EarningsLedgerTransactionService(
                        repository,
                        stateRepository,
                        template,
                        transactionManager);

                EarningsLedgerService service = new EarningsLedgerService(
                        repository, transactionService);

                action.execute(service, repository, template);

            } finally {
                // Drop only the uniquely named test database.
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
     *
     * All events use the same timestamp intentionally so that
     * sequence-based ordering is exercised by integration tests.
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
        entry.setCreatedAt(
                Instant.parse("2026-11-16T10:00:00Z"));

        return entry;
    }
}
