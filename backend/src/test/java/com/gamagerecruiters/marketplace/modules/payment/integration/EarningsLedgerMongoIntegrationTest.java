
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

import org.springframework.http.HttpStatus;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
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
import com.gamagerecruiters.marketplace.modules.payment.service.EarningsRecognitionTransactionService;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentPersistenceService;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.ProviderEarningsState;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;
import com.gamagerecruiters.marketplace.modules.payment.service.ProviderItemizedEarningsService;
import com.gamagerecruiters.marketplace.modules.payment.service.RefundCommissionCalculator;
import com.gamagerecruiters.marketplace.modules.payment.initializer.EarningsLedgerIndexInitializer;
import com.gamagerecruiters.marketplace.modules.payment.service.ProviderEarningsTransactionService;
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
 * Tests use a managed MongoDB Testcontainers replica set and a unique
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
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "true")
class EarningsLedgerMongoIntegrationTest {

    // Managed replica set for real MongoDB transaction verification.
    private static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    @BeforeAll
    static void startMongo() {
        MONGO.start();
    }

    @AfterAll
    static void stopMongo() {
        MONGO.stop();
    }

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
            payment.setRefundedAmount(new BigDecimal("0.00"));

            // Persist the payment in the isolated test database.
            template.insert(payment);

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
                            ledgerService,
                            createRecognitionService(
                                    template,
                                    ledgerService,
                                    ledgerRepository));

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
     * PR #37: A later booking dispute must recover the previously
     * committed pending event without changing financial balances.
     */
    @Test
    void disputedBookingRecoversCommittedPendingEarningsWithoutDuplicate() {
        withLedger((ledgerService, ledgerRepository, template) -> {

            Payment payment = new Payment();
            payment.setId("payment-001");
            payment.setBookingId("booking-001");
            payment.setCustomerId("customer-001");
            payment.setAmount(new BigDecimal("1000.00"));
            payment.setCommissionAmount(new BigDecimal("150.00"));
            payment.setCurrency("LKR");
            payment.setStatus(PaymentStatus.CAPTURED);
            payment.setRefundedAmount(new BigDecimal("0.00"));

            // Persist the payment in the isolated test database.
            template.insert(payment);

            PaymentPersistenceService paymentService =
                    mock(PaymentPersistenceService.class);

            when(paymentService.findPaymentById("payment-001"))
                    .thenReturn(Optional.of(payment));

            BookingFacade bookingFacade = mock(BookingFacade.class);

            when(bookingFacade.getBookingParticipants("booking-001"))
                    .thenReturn(
                            new BookingParticipants(
                                    "booking-001", null, "listing-001",
                                    "customer-001", "provider-001",
                                    BookingStatus.COMPLETED, null, null),
                            new BookingParticipants(
                                    "booking-001", null, "listing-001",
                                    "customer-001", "provider-001",
                                    BookingStatus.DISPUTED, null, null));

            CompletionLookup completionLookup = mock(CompletionLookup.class);

            when(completionLookup.findConfirmedAt("booking-001"))
                    .thenReturn(Optional.of(
                            Instant.parse("2026-11-18T10:00:00Z")));

            EarningsLifecycleService lifecycleService =
                    new EarningsLifecycleService(
                            paymentService,
                            bookingFacade,
                            completionLookup,
                            ledgerService,
                            createRecognitionService(
                                    template,
                                    ledgerService,
                                    ledgerRepository));

            EarningsLedgerEntry first =
                    lifecycleService.recordPendingEarnings("payment-001");

            assertEquals(1, ledgerRepository.count());

            EarningsLedgerEntry recovered =
                    lifecycleService.recordPendingEarnings("payment-001");

            assertEquals(first.getId(), recovered.getId());
            assertEquals(first.getSequenceNumber(),
                    recovered.getSequenceNumber());
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
        });
    }

    /**
     * Runs one test against an isolated temporary MongoDB database.
     */
    private void withLedger(LedgerTestAction action) {

        String databaseName = "wp07_earnings_ledger_test_"
                + UUID.randomUUID().toString().replace("-", "");

        try (MongoClient client = MongoClients.create(MONGO.getReplicaSetUrl())) {

            MongoDatabase database = client.getDatabase(databaseName);

            try {
                MongoTemplate template = new MongoTemplate(client, databaseName);

                // Create collections before starting transactions.
                template.createCollection("earnings_ledger");
                template.createCollection("provider_earnings_state");
                template.createCollection("payments");

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

    /**
     * Creates the real recognition service using the isolated
     * MongoDB database and its transaction infrastructure.
     */
    private EarningsRecognitionTransactionService createRecognitionService(
            MongoTemplate template,
            EarningsLedgerService ledgerService,
            EarningsLedgerRepository ledgerRepository) {

        MongoRepositoryFactory factory =
                new MongoRepositoryFactory(template);

        ProviderEarningsStateRepository stateRepository =
                factory.getRepository(
                        ProviderEarningsStateRepository.class);

        MongoTransactionManager transactionManager =
                new MongoTransactionManager(
                        template.getMongoDatabaseFactory());

        EarningsLedgerTransactionService ledgerTransactionService =
                new EarningsLedgerTransactionService(
                        ledgerRepository,
                        stateRepository,
                        template,
                        transactionManager);

        return new EarningsRecognitionTransactionService(
                template,
                ledgerService,
                ledgerTransactionService,
                transactionManager);
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

    /**
     * Seeds isolated history fixtures, including legacy ledger entries.
     * Direct insertion is restricted to the temporary test database.
     */
    private void seedHistoryEntry(
            EarningsLedgerRepository repository,
            String id,
            String providerId,
            String currency,
            Long sequenceNumber) {

        EarningsLedgerEntry entry =
                event("history-event-" + id, providerId, "1500.00");

        entry.setId(id);
        entry.setCurrency(currency);
        entry.setSequenceNumber(sequenceNumber);

        repository.insert(entry);
    }

    @Test
    void transactionHistoryIsolatesProviderAndCurrency() {

        withLedger((service, repository, template) -> {

            seedHistoryEntry(repository,
                    "own-1", "provider-a", "LKR", 1L);

            seedHistoryEntry(repository,
                    "own-2", "provider-a", "LKR", 2L);

            seedHistoryEntry(repository,
                    "foreign-1", "provider-b", "LKR", 1L);

            seedHistoryEntry(repository,
                    "usd-1", "provider-a", "USD", 1L);

            var historyService =
                    new ProviderEarningsTransactionService(repository);

            var response =
                    historyService.getTransactions("provider-a", 0, 10);

            assertEquals(2L, response.totalElements());

            assertEquals(
                    List.of("own-2", "own-1"),
                    response.content().stream()
                            .map(item -> item.transactionId())
                            .toList());

            assertTrue(response.content().stream()
                    .allMatch(item -> "LKR".equals(item.currency())));

            assertEquals(4L, repository.count());
        });
    }

    @Test
    void transactionHistoryOrdersSequencedAndLegacyEntries() {

        withLedger((service, repository, template) -> {

            seedHistoryEntry(repository,
                    "sequence-1", "provider-a", "LKR", 1L);

            seedHistoryEntry(repository,
                    "legacy-a", "provider-a", "LKR", null);

            seedHistoryEntry(repository,
                    "sequence-3", "provider-a", "LKR", 3L);

            seedHistoryEntry(repository,
                    "legacy-b", "provider-a", "LKR", null);

            seedHistoryEntry(repository,
                    "sequence-2", "provider-a", "LKR", 2L);

            var historyService =
                    new ProviderEarningsTransactionService(repository);

            var response =
                    historyService.getTransactions("provider-a", 0, 10);

            assertEquals(
                    List.of(
                            "sequence-3",
                            "sequence-2",
                            "sequence-1",
                            "legacy-b",
                            "legacy-a"),
                    response.content().stream()
                            .map(item -> item.transactionId())
                            .toList());

            assertNull(response.content().get(3).sequenceNumber());
            assertNull(response.content().get(4).sequenceNumber());
        });
    }

    @Test
    void transactionHistoryPaginatesWithoutDuplicateEntries() {

        withLedger((service, repository, template) -> {

            for (long sequence = 1; sequence <= 5; sequence++) {

                seedHistoryEntry(repository,
                        "transaction-" + sequence,
                        "provider-a",
                        "LKR",
                        sequence);
            }

            var historyService =
                    new ProviderEarningsTransactionService(repository);

            var first = historyService.getTransactions(
                    "provider-a", 0, 2);

            var second = historyService.getTransactions(
                    "provider-a", 1, 2);

            var third = historyService.getTransactions(
                    "provider-a", 2, 2);

            assertEquals(5L, first.totalElements());
            assertEquals(5L, second.totalElements());
            assertEquals(5L, third.totalElements());

            assertEquals(3, first.totalPages());
            assertEquals(3, second.totalPages());
            assertEquals(3, third.totalPages());

            assertEquals(
                    List.of("transaction-5", "transaction-4"),
                    first.content().stream()
                            .map(item -> item.transactionId())
                            .toList());

            assertEquals(
                    List.of("transaction-3", "transaction-2"),
                    second.content().stream()
                            .map(item -> item.transactionId())
                            .toList());

            assertEquals(
                    List.of("transaction-1"),
                    third.content().stream()
                            .map(item -> item.transactionId())
                            .toList());

            assertEquals(5L, repository.count());
        });
    }

    @Test
    void transactionHistoryReturnsEmptyPageForUnrelatedProvider() {

        withLedger((service, repository, template) -> {

            seedHistoryEntry(repository,
                    "foreign-1", "provider-b", "LKR", 1L);

            var historyService =
                    new ProviderEarningsTransactionService(repository);

            var response =
                    historyService.getTransactions("provider-a", 0, 10);

            assertTrue(response.content().isEmpty());
            assertEquals(0, response.page());
            assertEquals(10, response.size());
            assertEquals(0L, response.totalElements());
            assertEquals(0, response.totalPages());
        });
    }

    @Test
    void createsProviderHistoryIndexWithoutRemovingFinancialIndexes() {

        withLedger((service, repository, template) -> {

            var initializer =
                    new EarningsLedgerIndexInitializer(template);

            initializer.initializeIndexes();
            initializer.initializeIndexes();

            var indexes = template.getCollection("earnings_ledger")
                    .listIndexes()
                    .into(new java.util.ArrayList<Document>());

            String historyIndexName =
                    "providerId_1_currency_1_sequenceNumber_-1__id_-1";

            var matching = indexes.stream()
                    .filter(index -> historyIndexName.equals(
                            index.getString("name")))
                    .toList();

            assertEquals(1, matching.size());

            Document historyIndex = matching.get(0);
            Document key = historyIndex.get("key", Document.class);

            assertEquals(
                    List.of(
                            "providerId",
                            "currency",
                            "sequenceNumber",
                            "_id"),
                    new java.util.ArrayList<>(key.keySet()));

            assertEquals(
                    new Document("providerId", 1)
                            .append("currency", 1)
                            .append("sequenceNumber", -1)
                            .append("_id", -1),
                    key);

            assertFalse(Boolean.TRUE.equals(historyIndex.get("unique")));
            assertFalse(Boolean.TRUE.equals(historyIndex.get("sparse")));
            assertFalse(historyIndex.containsKey("partialFilterExpression"));
            assertFalse(historyIndex.containsKey("expireAfterSeconds"));

            assertTrue(indexes.stream().anyMatch(index ->
                    "eventKey_1".equals(index.getString("name"))
                            && Boolean.TRUE.equals(index.get("unique"))));

            assertTrue(indexes.stream().anyMatch(index ->
                    "providerId_1_currency_1_sequenceNumber_1"
                            .equals(index.getString("name"))
                            && Boolean.TRUE.equals(index.get("unique"))
                            && index.containsKey("partialFilterExpression")));
        });
    }

    @Test
    void createsItemizedIndexAndPreservesExistingIndexes() {

        withLedger((service, repository, template) -> {

            var initializer =
                    new EarningsLedgerIndexInitializer(template);

            initializer.initializeIndexes();
            initializer.initializeIndexes();

            var indexes = template.getCollection("earnings_ledger")
                    .listIndexes()
                    .into(new java.util.ArrayList<Document>());

            String itemizedIndexName =
                    "providerId_1_currency_1_eventType_1_sequenceNumber_-1__id_-1";

            var matching = indexes.stream()
                    .filter(index -> itemizedIndexName.equals(
                            index.getString("name")))
                    .toList();

            assertEquals(1, matching.size());

            Document itemizedIndex = matching.get(0);
            Document key = itemizedIndex.get("key", Document.class);

            assertEquals(
                    List.of(
                            "providerId",
                            "currency",
                            "eventType",
                            "sequenceNumber",
                            "_id"),
                    new java.util.ArrayList<>(key.keySet()));

            assertEquals(
                    new Document("providerId", 1)
                            .append("currency", 1)
                            .append("eventType", 1)
                            .append("sequenceNumber", -1)
                            .append("_id", -1),
                    key);

            assertFalse(Boolean.TRUE.equals(itemizedIndex.get("unique")));
            assertFalse(Boolean.TRUE.equals(itemizedIndex.get("sparse")));
            assertFalse(Boolean.TRUE.equals(itemizedIndex.get("hidden")));
            assertFalse(itemizedIndex.containsKey("partialFilterExpression"));
            assertFalse(itemizedIndex.containsKey("expireAfterSeconds"));
            assertFalse(itemizedIndex.containsKey("collation"));

            assertTrue(indexes.stream().anyMatch(index ->
                    "eventKey_1".equals(index.getString("name"))
                            && Boolean.TRUE.equals(index.get("unique"))));

            assertTrue(indexes.stream().anyMatch(index ->
                    "providerId_1_currency_1_sequenceNumber_1"
                            .equals(index.getString("name"))
                            && Boolean.TRUE.equals(index.get("unique"))
                            && index.containsKey("partialFilterExpression")));

            assertTrue(indexes.stream().anyMatch(index ->
                    "providerId_1_currency_1_sequenceNumber_-1__id_-1"
                            .equals(index.getString("name"))));
        });
    }


    /**
     * Creates an itemized earnings service backed by real MongoDB repositories.
     */
    private ProviderItemizedEarningsService itemizedService(
            EarningsLedgerRepository repository,
            MongoTemplate template) {

        PaymentRepository paymentRepository =
                new MongoRepositoryFactory(template)
                        .getRepository(PaymentRepository.class);

        return new ProviderItemizedEarningsService(
                repository,
                paymentRepository,
                new RefundCommissionCalculator());
    }

    /**
     * Seeds a recognized payment in the isolated integration test database.
     */
    private void seedItemizedPayment(
            EarningsLedgerRepository repository,
            MongoTemplate template,
            String paymentId,
            String providerId,
            String currency,
            Long sequence,
            String refundedAmount,
            PaymentStatus status) {

        Payment payment = new Payment();

        payment.setId(paymentId);
        payment.setBookingId("booking-" + paymentId);
        payment.setCustomerId("customer-test");
        payment.setAmount(new BigDecimal("10000.00"));
        payment.setCommissionAmount(new BigDecimal("1000.00"));
        payment.setCommissionRate(new BigDecimal("0.10"));
        payment.setRefundedAmount(new BigDecimal(refundedAmount));
        payment.setCurrency(currency);
        payment.setStatus(status);

        template.insert(payment);

        seedItemizedRecognition(
                repository, paymentId, providerId, currency, sequence);
    }

    /**
     * Inserts a canonical recognition event for repository query testing.
     */
    private void seedItemizedRecognition(
            EarningsLedgerRepository repository,
            String paymentId,
            String providerId,
            String currency,
            Long sequence) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey("earning-pending:" + paymentId);
        entry.setPaymentId(paymentId);
        entry.setBookingId("booking-" + paymentId);
        entry.setProviderId(providerId);
        entry.setCurrency(currency);
        entry.setAmount(new BigDecimal("9000.00"));
        entry.setEventType(EarningsLedgerEventType.EARNING_PENDING);
        entry.setSequenceNumber(sequence);
        entry.setCreatedAt(Instant.parse("2026-11-26T10:00:00Z"));

        repository.insert(entry);
    }

    @Test
    void itemizedEarningsIsolatesProviderCurrencyAndEventType() {

        withLedger((service, repository, template) -> {

            new EarningsLedgerIndexInitializer(template)
                    .initializeIndexes();

            seedItemizedPayment(
                    repository, template, "own-payment",
                    "provider-a", "LKR", 1L,
                    "0.00", PaymentStatus.CAPTURED);

            seedItemizedPayment(
                    repository, template, "foreign-payment",
                    "provider-b", "LKR", 1L,
                    "0.00", PaymentStatus.CAPTURED);

            seedItemizedPayment(
                    repository, template, "foreign-currency",
                    "provider-a", "USD", 1L,
                    "0.00", PaymentStatus.CAPTURED);

            EarningsLedgerEntry movement = new EarningsLedgerEntry();

            movement.setEventKey("available-own-payment");
            movement.setPaymentId("own-payment");
            movement.setBookingId("booking-own-payment");
            movement.setProviderId("provider-a");
            movement.setCurrency("LKR");
            movement.setAmount(new BigDecimal("9000.00"));
            movement.setEventType(EarningsLedgerEventType.EARNING_AVAILABLE);
            movement.setSequenceNumber(2L);
            movement.setCreatedAt(Instant.now());

            repository.insert(movement);

            var response = itemizedService(repository, template)
                    .getItemizedEarnings("provider-a", 0, 10);

            assertEquals(1L, response.totalElements());
            assertEquals(1, response.content().size());

            var item = response.content().get(0);

            assertEquals("own-payment", item.paymentId());
            assertEquals("booking-own-payment", item.bookingId());
            assertEquals("LKR", item.currency());

            assertEquals(0, new BigDecimal("10000.00")
                    .compareTo(item.grossAmount()));

            assertEquals(0, new BigDecimal("1000.00")
                    .compareTo(item.commissionAmount()));

            assertEquals(0, new BigDecimal("9000.00")
                    .compareTo(item.netEarnings()));

            assertEquals(4L, repository.count());
        });
    }

    @Test
    void itemizedEarningsMapsPartialAndFullRefunds() {

        withLedger((service, repository, template) -> {

            seedItemizedPayment(
                    repository, template, "partial-payment",
                    "provider-a", "LKR", 1L,
                    "2000.00", PaymentStatus.PARTIALLY_REFUNDED);

            seedItemizedPayment(
                    repository, template, "full-payment",
                    "provider-a", "LKR", 2L,
                    "10000.00", PaymentStatus.REFUNDED);

            var response = itemizedService(repository, template)
                    .getItemizedEarnings("provider-a", 0, 10);

            assertEquals(2L, response.totalElements());

            var full = response.content().get(0);
            var partial = response.content().get(1);

            assertEquals("full-payment", full.paymentId());

            assertEquals(0, BigDecimal.ZERO
                    .compareTo(full.netEarnings()));

            assertEquals(0, new BigDecimal("9000.00")
                    .compareTo(full.refundAdjustmentAmount()));

            assertEquals("partial-payment", partial.paymentId());

            assertEquals(0, new BigDecimal("2000.00")
                    .compareTo(partial.refundedAmount()));

            assertEquals(0, new BigDecimal("1800.00")
                    .compareTo(partial.refundAdjustmentAmount()));

            assertEquals(0, new BigDecimal("7200.00")
                    .compareTo(partial.netEarnings()));
        });
    }

    @Test
    void itemizedEarningsUsesDeterministicPagination() {

        withLedger((service, repository, template) -> {

            new EarningsLedgerIndexInitializer(template)
                    .initializeIndexes();

            for (long sequence = 1; sequence <= 5; sequence++) {

                seedItemizedPayment(
                        repository, template, "payment-" + sequence,
                        "provider-a", "LKR", sequence,
                        "0.00", PaymentStatus.CAPTURED);
            }

            var itemized = itemizedService(repository, template);

            var first = itemized.getItemizedEarnings("provider-a", 0, 2);
            var second = itemized.getItemizedEarnings("provider-a", 1, 2);
            var third = itemized.getItemizedEarnings("provider-a", 2, 2);

            assertEquals(5L, first.totalElements());
            assertEquals(3, first.totalPages());

            assertEquals(
                    List.of("payment-5", "payment-4"),
                    first.content().stream()
                            .map(item -> item.paymentId())
                            .toList());

            assertEquals(
                    List.of("payment-3", "payment-2"),
                    second.content().stream()
                            .map(item -> item.paymentId())
                            .toList());

            assertEquals(
                    List.of("payment-1"),
                    third.content().stream()
                            .map(item -> item.paymentId())
                            .toList());

            assertEquals(5L, repository.count());
        });
    }

    @Test
    void itemizedEarningsPreservesMixedLegacyRecognitionHistory() {
        withLedger((service, repository, template) -> {
            new EarningsLedgerIndexInitializer(template)
                    .initializeIndexes();

            seedItemizedPayment(
                    repository, template, "payment-current",
                    "provider-a", "LKR", 2L,
                    "0.00", PaymentStatus.CAPTURED);

            seedItemizedPayment(
                    repository, template, "payment-legacy",
                    "provider-a", "LKR", null,
                    "2000.00", PaymentStatus.PARTIALLY_REFUNDED);

            var itemized = itemizedService(repository, template);

            var mixed = itemized.getItemizedEarnings("provider-a", 0, 10);
            var recent = itemized.getItemizedEarnings("provider-a", 0, 5);
            var first = itemized.getItemizedEarnings("provider-a", 0, 1);
            var second = itemized.getItemizedEarnings("provider-a", 1, 1);

            assertEquals(2L, mixed.totalElements());
            assertEquals(1, mixed.totalPages());

            assertEquals(
                    List.of("payment-current", "payment-legacy"),
                    mixed.content().stream()
                            .map(item -> item.paymentId())
                            .toList());

            assertEquals(mixed.content(), recent.content());

            assertEquals("payment-current",
                    first.content().get(0).paymentId());
            assertEquals("payment-legacy",
                    second.content().get(0).paymentId());

            assertEquals(2L, second.totalElements());
            assertEquals(2, second.totalPages());

            assertEquals(0, new BigDecimal("9000.00")
                    .compareTo(mixed.content().get(0).netEarnings()));

            assertEquals(0, new BigDecimal("7200.00")
                    .compareTo(mixed.content().get(1).netEarnings()));

            assertEquals(2L, repository.count());

            var legacy = repository.findAll().stream()
                    .filter(entry -> "payment-legacy"
                            .equals(entry.getPaymentId()))
                    .findFirst()
                    .orElseThrow();

            assertNull(legacy.getSequenceNumber());
            assertEquals(0, new BigDecimal("9000.00")
                    .compareTo(legacy.getAmount()));
        });
    }

    @Test
    void itemizedEarningsReturnsEmptyPageForUnrelatedProvider() {

        withLedger((service, repository, template) -> {

            seedItemizedPayment(
                    repository, template, "foreign-payment",
                    "provider-b", "LKR", 1L,
                    "0.00", PaymentStatus.CAPTURED);

            var response = itemizedService(repository, template)
                    .getItemizedEarnings("provider-a", 0, 10);

            assertTrue(response.content().isEmpty());
            assertEquals(0L, response.totalElements());
            assertEquals(0, response.totalPages());
        });
    }

    @Test
    void itemizedEarningsRejectsMissingPaymentRecord() {

        withLedger((service, repository, template) -> {

            seedItemizedRecognition(
                    repository, "missing-payment",
                    "provider-a", "LKR", 1L);

            var itemized = itemizedService(repository, template);

            ApiException exception = assertThrows(
                    ApiException.class,
                    () -> itemized.getItemizedEarnings(
                            "provider-a", 0, 10));

            assertEquals(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    exception.getStatus());

            assertEquals(
                    "EARNINGS_DATA_INCONSISTENT",
                    exception.getErrorCode());

            assertEquals(1L, repository.count());
        });
    }

}
