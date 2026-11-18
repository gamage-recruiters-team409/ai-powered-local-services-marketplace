
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.mongodb.MongoException;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;

/**
 * Unit tests for earnings ledger persistence and retrieval.
 *
 * <p>
 * Mockito isolates the service from MongoDB and verifies that new
 * financial events use the transactional persistence boundary.
 * Actual transaction rollback, indexes, and concurrency behavior
 * must be verified separately through MongoDB integration tests.
 * </p>
 */
class EarningsLedgerServiceTest {

        private EarningsLedgerRepository repository;
        private EarningsLedgerTransactionService transactionService;
        private EarningsLedgerService service;

        @BeforeEach
        void setUp() {
                repository = mock(EarningsLedgerRepository.class);
                transactionService = mock(EarningsLedgerTransactionService.class);

                service = new EarningsLedgerService(
                                repository, transactionService);
        }

        @Test
        void appendEventInsertsValidEntry() {
                EarningsLedgerEntry entry = validEntry();

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.empty());

                when(transactionService.appendAtomically(
                                any(EarningsLedgerEntry.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                EarningsLedgerEntry result = service.appendEvent(entry);

                assertNotNull(result);
                assertEquals(entry.getEventKey(), result.getEventKey());
                assertEquals(entry.getAmount(), result.getAmount());

                // Persistence must use the transaction service.
                verify(transactionService).appendAtomically(
                                any(EarningsLedgerEntry.class));

                // The original request must not be persisted directly.
                assertNotSame(entry, result);
                assertNull(entry.getId());
                assertNull(entry.getSequenceNumber());
        }

        @Test
        void appendEventReturnsExistingMatchingEntry() {
                EarningsLedgerEntry existing = validEntry();
                existing.setId("ledger-001");
                existing.setSequenceNumber(1L);

                EarningsLedgerEntry retry = validEntry();
                retry.setCreatedAt(
                                Instant.parse("2026-11-16T12:00:00Z"));

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.of(existing));

                EarningsLedgerEntry result = service.appendEvent(retry);

                assertSame(existing, result);
                verifyNoInteractions(transactionService);
        }

        @Test
        void appendEventRejectsConflictingFinancialData() {
                EarningsLedgerEntry existing = validEntry();
                existing.setAmount(new BigDecimal("1000.00"));

                EarningsLedgerEntry conflicting = validEntry();
                conflicting.setAmount(new BigDecimal("2000.00"));

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.of(existing));

                assertThrows(
                                IllegalStateException.class,
                                () -> service.appendEvent(conflicting));

                verifyNoInteractions(transactionService);
        }

        @Test
        void appendEventHandlesConcurrentDuplicateInsert() {
                EarningsLedgerEntry entry = validEntry();

                EarningsLedgerEntry existing = validEntry();
                existing.setId("ledger-001");
                existing.setSequenceNumber(1L);

                // First lookup finds nothing; second finds the competing insert.
                when(repository.findByEventKey("event-001"))
                                .thenReturn(
                                                Optional.empty(),
                                                Optional.of(existing));

                when(transactionService.appendAtomically(
                                any(EarningsLedgerEntry.class)))
                                .thenThrow(
                                                new DuplicateKeyException("Duplicate event key"));

                EarningsLedgerEntry result = service.appendEvent(entry);

                assertSame(existing, result);

                verify(transactionService).appendAtomically(
                                any(EarningsLedgerEntry.class));
        }

        @Test
        void appendEventRejectsConcurrentConflictingEvent() {
                EarningsLedgerEntry entry = validEntry();

                EarningsLedgerEntry conflicting = validEntry();
                conflicting.setAmount(new BigDecimal("999.00"));

                when(repository.findByEventKey("event-001"))
                                .thenReturn(
                                                Optional.empty(),
                                                Optional.of(conflicting));

                when(transactionService.appendAtomically(
                                any(EarningsLedgerEntry.class)))
                                .thenThrow(
                                                new DuplicateKeyException("Duplicate event key"));

                assertThrows(
                                IllegalStateException.class,
                                () -> service.appendEvent(entry));
        }

        @Test
        void appendEventPropagatesUnrelatedDuplicateKeyException() {
                EarningsLedgerEntry entry = validEntry();

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.empty());

                when(transactionService.appendAtomically(
                                any(EarningsLedgerEntry.class)))
                                .thenThrow(
                                                new DuplicateKeyException("Duplicate document ID"));

                assertThrows(
                                DuplicateKeyException.class,
                                () -> service.appendEvent(entry));

                verify(transactionService, times(1)).appendAtomically(
                                any(EarningsLedgerEntry.class));
        }

        @Test
        void appendEventRejectsExistingDocumentId() {
                EarningsLedgerEntry entry = validEntry();
                entry.setId("existing-ledger-id");

                assertThrows(
                                IllegalArgumentException.class,
                                () -> service.appendEvent(entry));

                verifyNoInteractions(repository, transactionService);
        }

        @Test
        void appendEventRejectsClientAssignedSequence() {
                EarningsLedgerEntry entry = validEntry();
                entry.setSequenceNumber(5L);

                assertThrows(
                                IllegalArgumentException.class,
                                () -> service.appendEvent(entry));

                verifyNoInteractions(repository, transactionService);
        }

        @Test
        void appendEventRejectsInvalidEntry() {
                EarningsLedgerEntry entry = validEntry();
                entry.setPaymentId(null);

                assertThrows(
                                IllegalArgumentException.class,
                                () -> service.appendEvent(entry));

                verifyNoInteractions(repository, transactionService);
        }

        @Test
        void appendEventRejectsNullEntry() {
                assertThrows(
                                NullPointerException.class,
                                () -> service.appendEvent(null));

                verifyNoInteractions(repository, transactionService);
        }

        @Test
        void appendEventRejectsNegativeAmount() {
                EarningsLedgerEntry entry = validEntry();
                entry.setAmount(new BigDecimal("-100.00"));

                assertThrows(
                                IllegalArgumentException.class,
                                () -> service.appendEvent(entry));

                verifyNoInteractions(repository, transactionService);
        }

        @Test
        void appendEventDoesNotRetryInvalidFinancialTransition() {
                EarningsLedgerEntry entry = validEntry();

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.empty());

                // The transaction boundary rejects an insufficient balance.
                when(transactionService.appendAtomically(
                                any(EarningsLedgerEntry.class)))
                                .thenThrow(new IllegalStateException(
                                                "Ledger event would create a negative earnings balance"));

                assertThrows(
                                IllegalStateException.class,
                                () -> service.appendEvent(entry));

                // Financial validation failures must not be retried.
                verify(transactionService, times(1)).appendAtomically(
                                any(EarningsLedgerEntry.class));
        }

        @Test
        void appendEventRetriesTransientTransactionFailure() {
                EarningsLedgerEntry entry = validEntry();

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.empty());

                MongoException transientFailure = new MongoException("Transient transaction write conflict");

                transientFailure.addLabel(
                                MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);

                when(transactionService.appendAtomically(
                                any(EarningsLedgerEntry.class)))
                                .thenThrow(transientFailure)
                                .thenAnswer(invocation -> invocation.getArgument(0));

                EarningsLedgerEntry result = service.appendEvent(entry);

                assertNotNull(result);

                // The first failed attempt must not be reused.
                verify(transactionService, times(2)).appendAtomically(
                                any(EarningsLedgerEntry.class));

                assertNull(entry.getId());
                assertNull(entry.getSequenceNumber());
        }

        @Test
        void appendEventStopsAfterMaximumTransientRetries() {
                EarningsLedgerEntry entry = validEntry();

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.empty());

                MongoException transientFailure = new MongoException("Transient transaction write conflict");

                transientFailure.addLabel(
                                MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);

                when(transactionService.appendAtomically(
                                any(EarningsLedgerEntry.class)))
                                .thenThrow(transientFailure);

                assertThrows(
                                MongoException.class,
                                () -> service.appendEvent(entry));

                verify(transactionService, times(5)).appendAtomically(
                                any(EarningsLedgerEntry.class));
        }

        @Test
        void appendEventDoesNotRetryUnknownCommitResult() {
                EarningsLedgerEntry entry = validEntry();

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.empty());

                MongoException uncertainCommit = new MongoException("Unknown transaction commit result");

                uncertainCommit.addLabel(
                                MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);

                when(transactionService.appendAtomically(
                                any(EarningsLedgerEntry.class)))
                                .thenThrow(uncertainCommit);

                assertThrows(
                                MongoException.class,
                                () -> service.appendEvent(entry));

                // A new append must not be attempted when commit is uncertain.
                verify(transactionService, times(1)).appendAtomically(
                                any(EarningsLedgerEntry.class));
        }

        @Test
        void getProviderHistoryReturnsRepositoryResults() {
                EarningsLedgerEntry entry = validEntry();

                when(repository.findByProviderIdOrderByCreatedAtDesc("provider-001"))
                                .thenReturn(List.of(entry));

                List<EarningsLedgerEntry> result = service.getProviderHistory("provider-001");

                assertEquals(1, result.size());
                assertSame(entry, result.get(0));
        }

        @Test
        void getProviderHistoryRejectsBlankProviderId() {
                assertThrows(
                                IllegalArgumentException.class,
                                () -> service.getProviderHistory(" "));

                verifyNoInteractions(repository, transactionService);
        }

        @Test
        void findMatchingCommittedEventReturnsMatchingEntryWithoutWriting() {
                EarningsLedgerEntry committed = validEntry();
                committed.setId("ledger-001");
                committed.setSequenceNumber(1L);

                EarningsLedgerEntry requested = validEntry();

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.of(committed));

                Optional<EarningsLedgerEntry> result =
                                service.findMatchingCommittedEvent(requested);

                assertTrue(result.isPresent());
                assertSame(committed, result.get());
                verify(repository).findByEventKey("event-001");
                verifyNoInteractions(transactionService);
        }

        @Test
        void findMatchingCommittedEventReturnsEmptyWithoutWriting() {
                EarningsLedgerEntry requested = validEntry();

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.empty());

                Optional<EarningsLedgerEntry> result =
                                service.findMatchingCommittedEvent(requested);

                assertTrue(result.isEmpty());
                verify(repository).findByEventKey("event-001");
                verifyNoInteractions(transactionService);
        }

        @Test
        void findMatchingCommittedEventRejectsConflictingFinancialData() {
                EarningsLedgerEntry committed = validEntry();
                committed.setAmount(new BigDecimal("999.00"));

                EarningsLedgerEntry requested = validEntry();

                when(repository.findByEventKey("event-001"))
                                .thenReturn(Optional.of(committed));

                IllegalStateException exception = assertThrows(
                                IllegalStateException.class,
                                () -> service.findMatchingCommittedEvent(requested));

                assertEquals(
                                "Ledger event key already exists with different financial data",
                                exception.getMessage());

                verifyNoInteractions(transactionService);
        }

        /**
         * Creates a structurally valid financial event for each test.
         *
         * @return a new earnings ledger event
         */
        private EarningsLedgerEntry validEntry() {
                EarningsLedgerEntry entry = new EarningsLedgerEntry();

                entry.setEventKey("event-001");
                entry.setPaymentId("payment-001");
                entry.setBookingId("booking-001");
                entry.setProviderId("provider-001");
                entry.setEventType(EarningsLedgerEventType.EARNING_PENDING);
                entry.setAmount(new BigDecimal("1500.00"));
                entry.setCurrency("LKR");
                entry.setCreatedAt(
                                Instant.parse("2026-11-16T10:00:00Z"));

                return entry;
        }
}
