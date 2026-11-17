
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
import org.springframework.data.mongodb.core.MongoTemplate;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;

/**
 * Unit tests for controlled earnings ledger persistence and retrieval.
 *
 * <p>
 * Mockito isolates the service from MongoDB. Database-level index
 * behavior must be verified separately through integration tests.
 * </p>
 */
class EarningsLedgerServiceTest {

    private EarningsLedgerRepository repository;
    private MongoTemplate mongoTemplate;
    private EarningsLedgerService service;

    @BeforeEach
    void setUp() {
        repository = mock(EarningsLedgerRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new EarningsLedgerService(repository, mongoTemplate);
    }

    @Test
    void appendEventInsertsValidEntry() {
        EarningsLedgerEntry entry = validEntry();

        when(repository.findByEventKey("event-001"))
                .thenReturn(Optional.empty());
        when(mongoTemplate.insert(entry)).thenReturn(entry);

        EarningsLedgerEntry result = service.appendEvent(entry);

        assertSame(entry, result);
        verify(mongoTemplate).insert(entry);
    }

    @Test
    void appendEventReturnsExistingMatchingEntry() {
        EarningsLedgerEntry existing = validEntry();
        existing.setId("ledger-001");

        EarningsLedgerEntry retry = validEntry();
        retry.setCreatedAt(Instant.parse("2026-11-16T12:00:00Z"));

        when(repository.findByEventKey("event-001"))
                .thenReturn(Optional.of(existing));

        EarningsLedgerEntry result = service.appendEvent(retry);

        assertSame(existing, result);
        verify(mongoTemplate, never()).insert(any(EarningsLedgerEntry.class));
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

        verify(mongoTemplate, never()).insert(any(EarningsLedgerEntry.class));
    }

    @Test
    void appendEventHandlesConcurrentDuplicateInsert() {
        EarningsLedgerEntry entry = validEntry();
        EarningsLedgerEntry existing = validEntry();
        existing.setId("ledger-001");

        when(repository.findByEventKey("event-001"))
                .thenReturn(Optional.empty(), Optional.of(existing));

        when(mongoTemplate.insert(entry))
                .thenThrow(new DuplicateKeyException("Duplicate event key"));

        EarningsLedgerEntry result = service.appendEvent(entry);

        assertSame(existing, result);
        verify(mongoTemplate).insert(entry);
    }

    @Test
    void appendEventRejectsConcurrentConflictingEvent() {
        EarningsLedgerEntry entry = validEntry();

        EarningsLedgerEntry conflicting = validEntry();
        conflicting.setAmount(new BigDecimal("999.00"));

        when(repository.findByEventKey("event-001"))
                .thenReturn(Optional.empty(), Optional.of(conflicting));

        when(mongoTemplate.insert(entry))
                .thenThrow(new DuplicateKeyException("Duplicate event key"));

        assertThrows(
                IllegalStateException.class,
                () -> service.appendEvent(entry));
    }

    @Test
    void appendEventPropagatesUnrelatedDuplicateKeyException() {
        EarningsLedgerEntry entry = validEntry();

        when(repository.findByEventKey("event-001"))
                .thenReturn(Optional.empty());

        when(mongoTemplate.insert(entry))
                .thenThrow(new DuplicateKeyException("Duplicate document ID"));

        assertThrows(
                DuplicateKeyException.class,
                () -> service.appendEvent(entry));
    }

    @Test
    void appendEventRejectsExistingDocumentId() {
        EarningsLedgerEntry entry = validEntry();
        entry.setId("existing-ledger-id");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.appendEvent(entry));

        verifyNoInteractions(repository, mongoTemplate);
    }

    @Test
    void appendEventRejectsInvalidEntry() {
        EarningsLedgerEntry entry = validEntry();
        entry.setPaymentId(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.appendEvent(entry));

        verifyNoInteractions(repository, mongoTemplate);
    }

    @Test
    void appendEventRejectsNullEntry() {
        assertThrows(
                NullPointerException.class,
                () -> service.appendEvent(null));

        verifyNoInteractions(repository, mongoTemplate);
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

        verifyNoInteractions(repository, mongoTemplate);
    }

    /**
     * Creates a structurally valid financial event for each test.
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
        entry.setCreatedAt(Instant.parse("2026-11-16T10:00:00Z"));

        return entry;
    }
}
