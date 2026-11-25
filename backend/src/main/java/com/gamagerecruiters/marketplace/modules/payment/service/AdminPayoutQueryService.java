package com.gamagerecruiters.marketplace.modules.payment.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutStatus;
import com.gamagerecruiters.marketplace.modules.payment.dto.PayoutSummaryResponse;

/**
 * Provides read-only payout queries for authorized finance administrators.
 *
 * <p>
 * This service does not modify payout lifecycle states, earnings balances,
 * reservations, allocations, or financial ledger records.
 * </p>
 */
@Service
public class AdminPayoutQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 128;

    private static final Sort PAYOUT_SORT = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    private final MongoTemplate mongoTemplate;

    public AdminPayoutQueryService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = Objects.requireNonNull(
                mongoTemplate, "MongoTemplate is required");
    }

    /**
     * Returns global payout counts grouped by lifecycle status.
     *
     * <p>
     * A single aggregation is used so that the summary does not depend
     * on the currently selected page or search filters.
     * </p>
     *
     * @return global payout status counts
     */
    public PayoutSummaryResponse getSummary() {

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.group("status").count().as("count"));

        List<Document> results = mongoTemplate.aggregate(
                aggregation,
                Payout.class,
                Document.class)
                .getMappedResults();

        Map<PayoutStatus, Long> counts =
                new EnumMap<>(PayoutStatus.class);

        for (Document result : results) {
            String status = result.getString("_id");
            Number count = result.get("count", Number.class);

            if (status == null || count == null) {
                throw new IllegalStateException(
                        "Invalid payout status aggregation result");
            }

            counts.put(
                    PayoutStatus.valueOf(status),
                    count.longValue());
        }

        return new PayoutSummaryResponse(
                counts.getOrDefault(PayoutStatus.PENDING, 0L),
                counts.getOrDefault(PayoutStatus.PROCESSING, 0L),
                counts.getOrDefault(PayoutStatus.PAID, 0L),
                counts.getOrDefault(PayoutStatus.FAILED, 0L));
    }

    /**
     * Returns a deterministically sorted page of matching payout requests.
     *
     * <p>
     * Search uses exact identifier matching. Date filtering applies to
     * the original payout creation timestamp using an inclusive lower
     * boundary and an exclusive upper boundary.
     * </p>
     *
     * @param page zero-based page number
     * @param size requested page size
     * @param search optional payout ID or provider ID
     * @param status optional payout lifecycle status
     * @param from optional inclusive creation timestamp
     * @param to optional exclusive creation timestamp
     * @return matching payout page
     */
    public Page<Payout> getPayouts(
            int page,
            int size,
            String search,
            PayoutStatus status,
            Instant from,
            Instant to) {

        validatePagination(page, size);
        validateDateRange(from, to);

        String normalizedSearch = normalizeSearch(search);

        List<Criteria> filters = new ArrayList<>();

        // Match identifiers as literal values, not executable regex.
        if (normalizedSearch != null) {
            filters.add(new Criteria().orOperator(
                    Criteria.where("id").is(normalizedSearch),
                    Criteria.where("providerId").is(normalizedSearch)));
        }

        if (status != null) {
            filters.add(Criteria.where("status").is(status));
        }

        // Combine both date boundaries into a single field criterion.
        if (from != null || to != null) {
            Criteria dateCriteria = Criteria.where("createdAt");

            if (from != null) {
                dateCriteria.gte(from);
            }

            if (to != null) {
                dateCriteria.lt(to);
            }

            filters.add(dateCriteria);
        }

        Query filterQuery = new Query();

        if (!filters.isEmpty()) {
            filterQuery.addCriteria(
                    new Criteria().andOperator(
                            filters.toArray(Criteria[]::new)));
        }

        Pageable pageable = PageRequest.of(
                page, size, PAYOUT_SORT);

        // Count all matching records before applying pagination.
        long total = mongoTemplate.count(
                filterQuery, Payout.class);

        Query pagedQuery = Query.of(filterQuery).with(pageable);

        List<Payout> payouts = mongoTemplate.find(
                pagedQuery, Payout.class);

        return new PageImpl<>(payouts, pageable, total);
    }

    /**
     * Rejects invalid or excessive page sizes.
     */
    private static void validatePagination(int page, int size) {

        if (page < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Page must not be negative");
        }

        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Page size must be between 1 and 100");
        }
    }

    /**
     * Rejects empty or reversed date intervals.
     */
    private static void validateDateRange(
            Instant from, Instant to) {

        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "From timestamp must be before to timestamp");
        }
    }

    /**
     * Normalizes optional search input and limits its maximum length.
     */
    private static String normalizeSearch(String search) {

        if (search == null || search.isBlank()) {
            return null;
        }

        String normalized = search.trim();

        if (normalized.length() > MAX_SEARCH_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Search must not exceed 128 characters");
        }

        return normalized;
    }
}
