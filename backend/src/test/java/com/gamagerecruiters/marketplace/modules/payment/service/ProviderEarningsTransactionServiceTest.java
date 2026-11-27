package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.dto.EarningsTransactionPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies provider-scoped earnings transaction retrieval,
 * pagination boundaries and financial response mapping.
 */
class ProviderEarningsTransactionServiceTest {

    private static final String PROVIDER_ID = "provider-123";
    private static final String CURRENCY = "LKR";

    private static final Instant CREATED_AT =
            Instant.parse("2026-11-26T10:00:00Z");

    private EarningsLedgerRepository repository;
    private ProviderEarningsTransactionService service;

    @BeforeEach
    void setUp() {

        repository = mock(EarningsLedgerRepository.class);
        service = new ProviderEarningsTransactionService(repository);
    }

    private EarningsLedgerEntry entry(
            String id,
            EarningsLedgerEventType eventType,
            Long sequenceNumber) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setId(id);
        entry.setEventKey("internal-event-" + id);
        entry.setPaymentId("payment-123");
        entry.setBookingId("booking-123");
        entry.setProviderId(PROVIDER_ID);
        entry.setEventType(eventType);
        entry.setAmount(new BigDecimal("4500.00"));
        entry.setCurrency(CURRENCY);
        entry.setSequenceNumber(sequenceNumber);
        entry.setCreatedAt(CREATED_AT);

        return entry;
    }

    private void stubEntries(
            List<EarningsLedgerEntry> entries,
            long totalElements) {

        when(repository.findByProviderIdAndCurrency(
                eq(PROVIDER_ID),
                eq(CURRENCY),
                any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(
                        entries,
                        invocation.getArgument(2),
                        totalElements));
    }

    @Test
    void returnsProviderOwnedTransactionWithExactFinancialValues() {

        stubEntries(List.of(entry(
                "transaction-123",
                EarningsLedgerEventType.EARNING_PENDING,
                5L)), 1);

        EarningsTransactionPageResponse response =
                service.getTransactions(PROVIDER_ID, 0, 10);

        assertEquals(1, response.content().size());

        var transaction = response.content().get(0);

        assertEquals("transaction-123", transaction.transactionId());
        assertEquals("payment-123", transaction.paymentId());
        assertEquals("booking-123", transaction.bookingId());

        assertEquals(
                EarningsLedgerEventType.EARNING_PENDING,
                transaction.eventType());

        assertEquals(new BigDecimal("4500.00"), transaction.amount());
        assertEquals(CURRENCY, transaction.currency());
        assertEquals(5L, transaction.sequenceNumber());
        assertEquals(CREATED_AT, transaction.createdAt());

        verify(repository).findByProviderIdAndCurrency(
                eq(PROVIDER_ID),
                eq(CURRENCY),
                any(Pageable.class));

        verifyNoMoreInteractions(repository);
    }

    @Test
    void appliesDeterministicSequenceAndIdOrdering() {

        stubEntries(List.of(), 0);

        service.getTransactions(PROVIDER_ID, 2, 20);

        ArgumentCaptor<Pageable> captor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(repository).findByProviderIdAndCurrency(
                eq(PROVIDER_ID),
                eq(CURRENCY),
                captor.capture());

        Pageable pageable = captor.getValue();

        assertEquals(2, pageable.getPageNumber());
        assertEquals(20, pageable.getPageSize());

        assertEquals(
                Sort.by(
                        Sort.Order.desc("sequenceNumber"),
                        Sort.Order.desc("id")),
                pageable.getSort());
    }

    @Test
    void returnsEmptyTransactionHistory() {

        when(repository.findByProviderIdAndCurrency(
                eq(PROVIDER_ID),
                eq(CURRENCY),
                any(Pageable.class)))
                .thenAnswer(invocation ->
                        Page.empty(invocation.getArgument(2)));

        EarningsTransactionPageResponse response =
                service.getTransactions(PROVIDER_ID, 0, 10);

        assertTrue(response.content().isEmpty());
        assertEquals(0, response.page());
        assertEquals(10, response.size());
        assertEquals(0L, response.totalElements());
        assertEquals(0, response.totalPages());
    }

    @Test
    void rejectsOffsetAboveMaximum() {
        int page = 1001;
        int size = 100;
        rejectsInvalidPagination(page, size);
    }

    @Test
    void rejectsOffsetJustAboveMaximum() {
        int page = 100001;
        int size = 1;
        rejectsInvalidPagination(page, size);
    }

    @Test
    void rejectsOffsetThatWouldOverflowInteger() {
        int page = Integer.MAX_VALUE;
        int size = 100;
        rejectsInvalidPagination(page, size);
    }

    @Test
    void acceptsMaximumAllowedOffset() {
        stubEntries(List.of(), 0);

        var response = service.getTransactions(PROVIDER_ID, 1000, 100);

        assertEquals(1000, response.page());
        assertEquals(100, response.size());
        assertTrue(response.content().isEmpty());
    }

    @Test
    void preservesDatabasePaginationMetadata() {

        stubEntries(List.of(entry(
                "transaction-21",
                EarningsLedgerEventType.EARNING_AVAILABLE,
                21L)), 21);

        EarningsTransactionPageResponse response =
                service.getTransactions(PROVIDER_ID, 2, 10);

        assertEquals(2, response.page());
        assertEquals(10, response.size());
        assertEquals(21L, response.totalElements());
        assertEquals(3, response.totalPages());
        assertEquals(1, response.content().size());
    }

    @Test
    void preservesDistinctLedgerEventTypes() {

        stubEntries(List.of(
                entry("transaction-3",
                        EarningsLedgerEventType.PAYOUT_SETTLE, 3L),
                entry("transaction-2",
                        EarningsLedgerEventType.PAYOUT_RESERVE, 2L),
                entry("transaction-1",
                        EarningsLedgerEventType.EARNING_AVAILABLE, 1L)),
                3);

        var response = service.getTransactions(PROVIDER_ID, 0, 10);

        assertEquals(
                List.of(
                        EarningsLedgerEventType.PAYOUT_SETTLE,
                        EarningsLedgerEventType.PAYOUT_RESERVE,
                        EarningsLedgerEventType.EARNING_AVAILABLE),
                response.content().stream()
                        .map(transaction -> transaction.eventType())
                        .toList());
    }

    @Test
    void preservesLegacyEntryWithoutInventingSequenceNumber() {

        stubEntries(List.of(entry(
                "legacy-transaction",
                EarningsLedgerEventType.EARNING_PENDING,
                null)), 1);

        var response = service.getTransactions(PROVIDER_ID, 0, 10);

        assertNull(response.content().get(0).sequenceNumber());
    }

    @Test
    void rejectsUnexpectedProviderInRepositoryResult() {

        EarningsLedgerEntry foreignEntry = entry(
                "foreign-transaction",
                EarningsLedgerEventType.EARNING_PENDING,
                1L);

        foreignEntry.setProviderId("another-provider");

        stubEntries(List.of(foreignEntry), 1);

        assertThrows(ApiException.class,
                () -> service.getTransactions(PROVIDER_ID, 0, 10));
    }

    @Test
    void rejectsUnexpectedCurrencyInRepositoryResult() {

        EarningsLedgerEntry foreignCurrencyEntry = entry(
                "foreign-currency-transaction",
                EarningsLedgerEventType.EARNING_PENDING,
                1L);

        foreignCurrencyEntry.setCurrency("USD");

        stubEntries(List.of(foreignCurrencyEntry), 1);

        assertThrows(ApiException.class,
                () -> service.getTransactions(PROVIDER_ID, 0, 10));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void rejectsMissingProviderIdentity(String providerId) {

        assertThrows(ApiException.class,
                () -> service.getTransactions(providerId, 0, 10));

        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({
            "-1, 10",
            "0, 0",
            "0, -1",
            "0, 101"
    })
    void rejectsInvalidPagination(int page, int size) {

        assertThrows(ApiException.class,
                () -> service.getTransactions(PROVIDER_ID, page, size));

        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsValidPageSizeBoundaries(int size) {

        stubEntries(List.of(), 0);

        var response = service.getTransactions(PROVIDER_ID, 0, size);

        assertEquals(size, response.size());
    }

    @Test
    void returnsImmutableTransactionContent() {

        stubEntries(List.of(entry(
                "transaction-1",
                EarningsLedgerEventType.EARNING_PENDING,
                1L)), 1);

        var response = service.getTransactions(PROVIDER_ID, 0, 10);

        assertThrows(
                UnsupportedOperationException.class,
                () -> response.content().clear());
    }
}
