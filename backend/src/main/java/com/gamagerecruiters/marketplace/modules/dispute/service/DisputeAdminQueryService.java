package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAdminSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.mapper.DisputeMapper;
import com.gamagerecruiters.marketplace.modules.dispute.port.UserDirectoryPort;

/**
 * Admin read side of the disputes area (list with filters, summary counts, case detail). Role checks happen in the
 * controller. PENDING booking-sync records are never returned or counted.
 */
@Service
public class DisputeAdminQueryService {

    /** Ids are letters, digits, '_' and '-'; the search text must look like (the start of) one. */
    private static final Pattern SEARCH_TEXT = Pattern.compile("^[A-Za-z0-9_-]{1," + DisputeRules.MAX_SEARCH_TEXT_LENGTH + "}$");
    private static final Pattern OBJECT_ID = Pattern.compile("^[0-9a-fA-F]{24}$");

    /** Whitelisted sort keys (request value -> document field). */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "updatedAt", "updatedAt",
            "responseDueAt", "responseDueAt",
            "appealDeadline", "appealDeadline",
            "status", "status",
            "category", "category");
    public static final String DEFAULT_SORT = "createdAt,desc";

    private final MongoTemplate mongoTemplate;
    private final UserDirectoryPort userDirectory;
    private final Clock clock;

    @Autowired
    public DisputeAdminQueryService(MongoTemplate mongoTemplate, UserDirectoryPort userDirectory) {
        this(mongoTemplate, userDirectory, Clock.systemUTC());
    }

    DisputeAdminQueryService(MongoTemplate mongoTemplate, UserDirectoryPort userDirectory, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.userDirectory = userDirectory;
        this.clock = clock;
    }

    /**
     * Admin list. {@code q} matches the case id exactly, or the start of the booking, customer or provider id.
     * {@code from}/{@code to} are inclusive UTC dates on createdAt. {@code sort} is "field" or "field,asc|desc".
     */
    public DisputePageResponse<DisputeSummaryResponse> search(String status, String category, String q,
                                                              LocalDate from, LocalDate to, String sort,
                                                              int page, int size) {
        Pageable pageable = PageRequest.of(validPage(page, size), size, parseSort(sort));
        List<Criteria> filters = new ArrayList<>();
        filters.add(visible());
        if (hasText(status)) {
            filters.add(Criteria.where("status").is(parseEnum(DisputeStatus.class, status, "INVALID_STATUS_FILTER",
                    "Unknown dispute status")));
        }
        if (hasText(category)) {
            filters.add(Criteria.where("category").is(parseEnum(DisputeCategory.class, category,
                    "INVALID_CATEGORY_FILTER", "Unknown dispute category")));
        }
        if (hasText(q)) {
            filters.add(searchCriteria(q.strip()));
        }
        Criteria created = createdRange(from, to);
        if (created != null) {
            filters.add(created);
        }

        Query query = Query.query(new Criteria().andOperator(filters.toArray(Criteria[]::new)));
        long total = mongoTemplate.count(query, Dispute.class);
        List<Dispute> content = mongoTemplate.find(Query.of(query).with(pageable), Dispute.class);
        return DisputePageResponse.from(new PageImpl<>(content, pageable, total).map(DisputeMapper::toSummary));
    }

    /** Current counts per open status, and decisions made in the last 30 days. */
    public DisputeAdminSummaryResponse summary() {
        Instant since = clock.instant().minus(DisputeRules.SUMMARY_RESOLVED_WINDOW);
        return new DisputeAdminSummaryResponse(
                countStatus(DisputeStatus.UNDER_REVIEW),
                countStatus(DisputeStatus.AWAITING_RESPONSE),
                countStatus(DisputeStatus.APPEALED),
                mongoTemplate.count(Query.query(new Criteria().andOperator(visible(),
                        Criteria.where("decision.decidedAt").gte(since))), Dispute.class),
                since);
    }

    public AdminDisputeDetailResponse getDetail(String disputeId) {
        Dispute dispute = mongoTemplate.findById(disputeId, Dispute.class);
        if (dispute == null || !dispute.isVisible()) {
            throw new ResourceNotFoundException("DISPUTE_NOT_FOUND", "Dispute not found");
        }
        return toAdminDetail(dispute);
    }

    /** Admin view with the parties' display names (null when they cannot be read). */
    public AdminDisputeDetailResponse toAdminDetail(Dispute dispute) {
        return DisputeMapper.toAdminDetail(dispute,
                userDirectory.findDisplayName(dispute.getCustomerId()).orElse(null),
                userDirectory.findDisplayName(dispute.getProviderId()).orElse(null),
                clock.instant());
    }

    private long countStatus(DisputeStatus status) {
        return mongoTemplate.count(Query.query(new Criteria().andOperator(visible(),
                Criteria.where("status").is(status))), Dispute.class);
    }

    private static Criteria visible() {
        return Criteria.where("bookingSyncState").ne(BookingSyncState.PENDING);
    }

    private static Criteria searchCriteria(String q) {
        if (!SEARCH_TEXT.matcher(q).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_TEXT",
                    "q must be 1 to " + DisputeRules.MAX_SEARCH_TEXT_LENGTH + " letters, digits, '_' or '-'");
        }
        // Validated above, so the prefix pattern contains no regex syntax.
        String prefix = "^" + q;
        List<Criteria> any = new ArrayList<>();
        if (OBJECT_ID.matcher(q).matches()) {
            any.add(Criteria.where("_id").is(q));
        }
        any.add(Criteria.where("bookingId").regex(prefix));
        any.add(Criteria.where("customerId").regex(prefix));
        any.add(Criteria.where("providerId").regex(prefix));
        return new Criteria().orOperator(any.toArray(Criteria[]::new));
    }

    private static Criteria createdRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "from must not be after to");
        }
        if (from == null && to == null) {
            return null;
        }
        Criteria criteria = Criteria.where("createdAt");
        if (from != null) {
            criteria = criteria.gte(from.atStartOfDay().toInstant(ZoneOffset.UTC));
        }
        if (to != null) {
            criteria = criteria.lt(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        }
        return criteria;
    }

    static Sort parseSort(String sort) {
        String value = hasText(sort) ? sort.strip() : DEFAULT_SORT;
        String[] parts = value.split(",", -1);
        String field = SORT_FIELDS.get(parts[0].strip());
        if (field == null || parts.length > 2) {
            throw invalidSort();
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length == 2) {
            String dir = parts[1].strip().toLowerCase(Locale.ROOT);
            if (!dir.equals("asc") && !dir.equals("desc")) {
                throw invalidSort();
            }
            direction = dir.equals("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
        }
        // Stable order for equal values.
        return Sort.by(direction, field).and(Sort.by(Sort.Direction.DESC, "_id"));
    }

    private static ApiException invalidSort() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT",
                "sort must be one of " + String.join(", ", SORT_FIELDS.keySet().stream().sorted().toList())
                        + ", optionally followed by ',asc' or ',desc'");
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String code, String message) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
    }

    private static int validPage(int page, int size) {
        if (page < 0 || size < 1 || size > DisputeRules.MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST",
                    "page must be 0 or more and size must be between 1 and " + DisputeRules.MAX_PAGE_SIZE);
        }
        return page;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
