package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutStatus;
import com.gamagerecruiters.marketplace.modules.payment.dto.PayoutSummaryResponse;

/**
 * Unit tests for read-only administrative payout queries.
 *
 * Verifies summary mapping, query construction, pagination,
 * and rejection of invalid input before database access.
 */
class AdminPayoutQueryServiceTest {

    private MongoTemplate mongoTemplate;
    private AdminPayoutQueryService service;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        service = new AdminPayoutQueryService(mongoTemplate);
    }

    @Test
    void summaryReturnsAllStatusCounts() {
        mockSummary(List.of(
                statusCount("PENDING", 6),
                statusCount("PROCESSING", 12),
                statusCount("PAID", 148),
                statusCount("FAILED", 3)));

        assertEquals(
                new PayoutSummaryResponse(6, 12, 148, 3),
                service.getSummary());
    }

    @Test
    void emptySummaryReturnsZeroCounts() {
        mockSummary(List.of());

        assertEquals(
                new PayoutSummaryResponse(0, 0, 0, 0),
                service.getSummary());
    }

    @Test
    void missingStatusesDefaultToZero() {
        mockSummary(List.of(statusCount("PAID", 5)));

        assertEquals(
                new PayoutSummaryResponse(0, 0, 5, 0),
                service.getSummary());
    }

    @Test
    void summarySupportsLongCounts() {
        mockSummary(List.of(
                new Document("_id", "PAID")
                        .append("count", 3_000_000_000L)));

        assertEquals(
                3_000_000_000L,
                service.getSummary().paid());
    }

    @Test
    void summaryRejectsMalformedAggregationResult() {
        mockSummary(List.of(
                new Document("_id", "PAID")));

        assertThrows(
                IllegalStateException.class,
                service::getSummary);
    }

    @Test
    void summaryRejectsUnknownLifecycleStatus() {
        mockSummary(List.of(
                statusCount("UNKNOWN", 1)));

        assertThrows(
                IllegalArgumentException.class,
                service::getSummary);
    }

    @Test
    void unfilteredQueryUsesDeterministicPagination() {
        mockEmptyPage(25L);

        var result = service.getPayouts(
                1, 10, null, null, null, null);

        ArgumentCaptor<Query> countCaptor =
                ArgumentCaptor.forClass(Query.class);

        ArgumentCaptor<Query> findCaptor =
                ArgumentCaptor.forClass(Query.class);

        verify(mongoTemplate).count(
                countCaptor.capture(), eq(Payout.class));

        verify(mongoTemplate).find(
                findCaptor.capture(), eq(Payout.class));

        Query countQuery = countCaptor.getValue();
        Query findQuery = findCaptor.getValue();

        assertTrue(countQuery.getQueryObject().isEmpty());
        assertEquals(0L, countQuery.getSkip());
        assertEquals(0, countQuery.getLimit());

        assertEquals(10L, findQuery.getSkip());
        assertEquals(10, findQuery.getLimit());

        assertEquals(
                new Document("createdAt", -1)
                        .append("id", -1),
                findQuery.getSortObject());

        assertEquals(25L, result.getTotalElements());
        assertEquals(3, result.getTotalPages());
        assertEquals(1, result.getNumber());
    }

    @Test
    void searchUsesTrimmedExactIdentifiers() {
        mockEmptyPage(0L);

        service.getPayouts(
                0, 10, "  provider-123  ",
                null, null, null);

        Document query = captureCountQuery().getQueryObject();

        List<Document> filters =
                query.getList("$and", Document.class);

        List<Document> identifiers =
                filters.get(0).getList("$or", Document.class);

        assertEquals(
                new Document("id", "provider-123"),
                identifiers.get(0));

        assertEquals(
                new Document("providerId", "provider-123"),
                identifiers.get(1));
    }

    @Test
    void searchTreatsRegexCharactersAsLiteralText() {
        mockEmptyPage(0L);

        service.getPayouts(
                0, 10, ".*", null, null, null);

        Document query = captureCountQuery().getQueryObject();

        List<Document> filters =
                query.getList("$and", Document.class);

        List<Document> identifiers =
                filters.get(0).getList("$or", Document.class);

        assertEquals(".*", identifiers.get(0).get("id"));
        assertEquals(".*", identifiers.get(1).get("providerId"));
    }

    @Test
    void blankSearchDoesNotAddFilter() {
        mockEmptyPage(0L);

        service.getPayouts(
                0, 10, "   ", null, null, null);

        assertTrue(
                captureCountQuery().getQueryObject().isEmpty());
    }

    @Test
    void statusFilterUsesRequestedLifecycleState() {
        mockEmptyPage(0L);

        service.getPayouts(
                0, 10, null, PayoutStatus.PROCESSING,
                null, null);

        List<Document> filters = captureCountQuery()
                .getQueryObject()
                .getList("$and", Document.class);

        assertEquals(
                PayoutStatus.PROCESSING,
                filters.get(0).get("status"));
    }

    @Test
    void combinedFiltersUseInclusiveFromAndExclusiveTo() {
        mockEmptyPage(0L);

        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-01T00:00:00Z");

        service.getPayouts(
                0, 10, "provider-123",
                PayoutStatus.PENDING, from, to);

        List<Document> filters = captureCountQuery()
                .getQueryObject()
                .getList("$and", Document.class);

        assertEquals(3, filters.size());
        assertTrue(filters.get(0).containsKey("$or"));

        assertEquals(
                PayoutStatus.PENDING,
                filters.get(1).get("status"));

        Document dateFilter =
                filters.get(2).get("createdAt", Document.class);

        assertEquals(from, dateFilter.get("$gte"));
        assertEquals(to, dateFilter.get("$lt"));
    }

    @Test
    void supportsLowerDateBoundaryOnly() {
        mockEmptyPage(0L);

        Instant from = Instant.parse("2026-11-01T00:00:00Z");

        service.getPayouts(
                0, 10, null, null, from, null);

        Document dateFilter = captureDateFilter();

        assertEquals(from, dateFilter.get("$gte"));
        assertFalse(dateFilter.containsKey("$lt"));
    }

    @Test
    void supportsUpperDateBoundaryOnly() {
        mockEmptyPage(0L);

        Instant to = Instant.parse("2026-12-01T00:00:00Z");

        service.getPayouts(
                0, 10, null, null, null, to);

        Document dateFilter = captureDateFilter();

        assertEquals(to, dateFilter.get("$lt"));
        assertFalse(dateFilter.containsKey("$gte"));
    }

    @Test
    void rejectsNegativePageBeforeDatabaseAccess() {
        assertBadRequest(() -> service.getPayouts(
                -1, 10, null, null, null, null));
    }

    @Test
    void rejectsZeroPageSizeBeforeDatabaseAccess() {
        assertBadRequest(() -> service.getPayouts(
                0, 0, null, null, null, null));
    }

    @Test
    void rejectsOversizedPageBeforeDatabaseAccess() {
        assertBadRequest(() -> service.getPayouts(
                0, 101, null, null, null, null));
    }

    @Test
    void rejectsOversizedSearchBeforeDatabaseAccess() {
        assertBadRequest(() -> service.getPayouts(
                0, 10, "a".repeat(129),
                null, null, null));
    }

    @Test
    void rejectsReversedDateRangeBeforeDatabaseAccess() {
        Instant from = Instant.parse("2026-12-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");

        assertBadRequest(() -> service.getPayouts(
                0, 10, null, null, from, to));
    }

    @Test
    void rejectsEqualDateBoundariesBeforeDatabaseAccess() {
        Instant boundary =
                Instant.parse("2026-11-01T00:00:00Z");

        assertBadRequest(() -> service.getPayouts(
                0, 10, null, null, boundary, boundary));
    }

    // Supplies controlled aggregation results without a live database.
    private void mockSummary(List<Document> documents) {
        when(mongoTemplate.aggregate(
                any(Aggregation.class),
                eq(Payout.class),
                eq(Document.class)))
                .thenReturn(new AggregationResults<>(
                        documents, new Document()));
    }

    private Document statusCount(String status, int count) {
        return new Document("_id", status)
                .append("count", count);
    }

    private void mockEmptyPage(long total) {
        when(mongoTemplate.count(
                any(Query.class), eq(Payout.class)))
                .thenReturn(total);

        when(mongoTemplate.find(
                any(Query.class), eq(Payout.class)))
                .thenReturn(List.of());
    }

    private Query captureCountQuery() {
        ArgumentCaptor<Query> captor =
                ArgumentCaptor.forClass(Query.class);

        verify(mongoTemplate).count(
                captor.capture(), eq(Payout.class));

        return captor.getValue();
    }

    private Document captureDateFilter() {
        return captureCountQuery()
                .getQueryObject()
                .getList("$and", Document.class)
                .get(0)
                .get("createdAt", Document.class);
    }

    // Invalid requests must never reach MongoDB.
    private void assertBadRequest(Runnable operation) {
        ApiException exception = assertThrows(
                ApiException.class, operation::run);

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("VALIDATION_FAILED", exception.getErrorCode());

        verifyNoInteractions(mongoTemplate);
    }
}
