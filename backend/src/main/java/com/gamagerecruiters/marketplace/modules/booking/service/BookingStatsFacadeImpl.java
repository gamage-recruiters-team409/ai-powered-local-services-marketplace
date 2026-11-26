package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.BookingSummary;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.BookingUserStats;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.DailyCount;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.LateStart;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.MonthlyCount;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.RepeatCancellation;

@Service
public class BookingStatsFacadeImpl implements BookingStatsFacade {

    static final Set<Role> STATS_ROLES = EnumSet.of(Role.ADMIN, Role.SUPER_ADMIN, Role.SUPPORT_ADMIN,
            Role.FINANCE_ADMIN, Role.MODERATOR);
    static final int MAX_OVERDUE_LIMIT = 200;
    static final int MAX_DAY_RANGE = 366;
    static final int NEXT_BOOKINGS = 5;
    static final int MONTHS = 6;

    private static final Set<BookingStatus> CANCELLED = EnumSet.of(
            BookingStatus.CANCELLED_BY_CUSTOMER, BookingStatus.CANCELLED_BY_PROVIDER);
    private static final Set<BookingStatus> OVERDUE_STATUSES = EnumSet.of(
            BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS);
    /** Upcoming bookings that can still happen. */
    private static final Set<BookingStatus> UPCOMING_STATUSES = EnumSet.of(
            BookingStatus.PENDING_PROVIDER, BookingStatus.AWAITING_PAYMENT, BookingStatus.CONFIRMED);

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    @Autowired
    public BookingStatsFacadeImpl(MongoTemplate mongoTemplate) {
        this(mongoTemplate, Clock.system(SlotServiceImpl.BUSINESS_ZONE));
    }

    BookingStatsFacadeImpl(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    @Override
    public BookingUserStats getUserStats(String userId) {
        assertStatsAccess();
        if (userId == null || userId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USER_ID_REQUIRED", "userId is required");
        }
        Criteria ofUser = new Criteria().orOperator(
                Criteria.where("customerId").is(userId), Criteria.where("providerId").is(userId));

        Query all = new Query(ofUser);
        all.fields().include("status", "createdAt", "timeline.toStatus");
        List<Booking> bookings = mongoTemplate.find(all, Booking.class);

        long completed = 0;
        long cancelled = 0;
        long disputed = 0;
        YearMonth thisMonth = YearMonth.now(clock.withZone(SlotServiceImpl.BUSINESS_ZONE));
        Map<YearMonth, Long> months = new LinkedHashMap<>();
        for (int i = MONTHS - 1; i >= 0; i--) {
            months.put(thisMonth.minusMonths(i), 0L);
        }
        for (Booking b : bookings) {
            if (b.getStatus() == BookingStatus.COMPLETED) {
                completed++;
            }
            if (CANCELLED.contains(b.getStatus())) {
                cancelled++;
            }
            if (b.getTimeline().stream().anyMatch(c -> c.getToStatus() == BookingStatus.DISPUTED)) {
                disputed++;
            }
            if (b.getCreatedAt() != null) {
                YearMonth m = YearMonth.from(b.getCreatedAt().atZone(SlotServiceImpl.BUSINESS_ZONE));
                months.computeIfPresent(m, (k, v) -> v + 1);
            }
        }

        Query next = new Query(new Criteria().andOperator(ofUser,
                Criteria.where("status").in(UPCOMING_STATUSES),
                Criteria.where("slotStart").gte(clock.instant())))
                .with(Sort.by(Sort.Direction.ASC, "slotStart"))
                .limit(NEXT_BOOKINGS);
        List<BookingSummary> nextBookings = mongoTemplate.find(next, Booking.class).stream()
                .map(BookingStatsFacadeImpl::toSummary).toList();

        List<MonthlyCount> lastSixMonths = months.entrySet().stream()
                .map(e -> new MonthlyCount(e.getKey(), e.getValue())).toList();
        return new BookingUserStats(userId, bookings.size(), completed, cancelled, disputed, lastSixMonths,
                nextBookings);
    }

    @Override
    public List<BookingSummary> findOverdueBookings(int limit) {
        return findOverdueBookings(limit, null);
    }

    @Override
    public List<BookingSummary> findOverdueBookings(int limit, String categoryId) {
        assertStatsAccess();
        assertLimit(limit);
        Criteria criteria = Criteria.where("status").in(OVERDUE_STATUSES).and("slotEnd").lt(clock.instant());
        withCategory(criteria, categoryId);
        Query query = new Query(criteria)
                .with(Sort.by(Sort.Order.asc("slotEnd"), Sort.Order.asc("id")))
                .limit(limit);
        return mongoTemplate.find(query, Booking.class).stream().map(BookingStatsFacadeImpl::toSummary).toList();
    }

    @Override
    public List<LateStart> findLateStarts(int limit, String categoryId) {
        assertStatsAccess();
        assertLimit(limit);
        Instant now = clock.instant();
        Criteria criteria = Criteria.where("status").is(BookingStatus.CONFIRMED).and("slotStart").lt(now);
        withCategory(criteria, categoryId);
        // Oldest start first = most late first; _id breaks ties so the list is stable.
        Query query = new Query(criteria)
                .with(Sort.by(Sort.Order.asc("slotStart"), Sort.Order.asc("id")))
                .limit(limit);
        return mongoTemplate.find(query, Booking.class).stream()
                .map(b -> new LateStart(b.getId(), b.getRef(), b.getListingId(),
                        b.getPriceSnapshot() == null ? null : b.getPriceSnapshot().serviceTitle(),
                        b.getPriceSnapshot() == null ? null : b.getPriceSnapshot().categoryId(),
                        b.getCustomerId(), b.getProviderId(), b.getSlotStart(),
                        Duration.between(b.getSlotStart(), now).toMinutes()))
                .toList();
    }

    private static void assertLimit(int limit) {
        if (limit < 1 || limit > MAX_OVERDUE_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT",
                    "limit must be between 1 and " + MAX_OVERDUE_LIMIT);
        }
    }

    private static void withCategory(Criteria criteria, String categoryId) {
        if (categoryId != null && !categoryId.isBlank()) {
            criteria.and("priceSnapshot.categoryId").is(categoryId.trim());
        }
    }

    @Override
    public List<RepeatCancellation> findRepeatCancellations(int minCount, int days) {
        assertStatsAccess();
        if (minCount < 1 || minCount > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MIN_COUNT", "minCount must be between 1 and 100");
        }
        if (days < 1 || days > 365) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DAYS", "days must be between 1 and 365");
        }
        Instant since = clock.instant().minus(Duration.ofDays(days));
        Query query = new Query(Criteria.where("status").in(CANCELLED)
                .and("timeline").elemMatch(Criteria.where("toStatus").in(CANCELLED).and("changedAt").gte(since)));
        query.fields().include("customerId", "providerId", "status", "timeline");

        Map<String, long[]> counts = new HashMap<>();
        Map<String, Instant> last = new HashMap<>();
        for (Booking b : mongoTemplate.find(query, Booking.class)) {
            for (BookingStatusChange c : b.getTimeline()) {
                if (!CANCELLED.contains(c.getToStatus()) || c.getChangedAt() == null
                        || c.getChangedAt().isBefore(since)) {
                    continue;
                }
                boolean byCustomer = c.getToStatus() == BookingStatus.CANCELLED_BY_CUSTOMER;
                String key = (byCustomer ? "CUSTOMER|" + b.getCustomerId() : "PROVIDER|" + b.getProviderId());
                counts.computeIfAbsent(key, k -> new long[1])[0]++;
                last.merge(key, c.getChangedAt(), (a, x) -> a.isAfter(x) ? a : x);
            }
        }
        List<String> repeaters = counts.entrySet().stream()
                .filter(e -> e.getValue()[0] >= minCount).map(Map.Entry::getKey).toList();
        Map<String, Long> totals = totalBookingsInWindow(repeaters, since);

        List<RepeatCancellation> result = new ArrayList<>();
        for (String key : repeaters) {
            String[] parts = key.split("\\|", 2);
            long count = counts.get(key)[0];
            result.add(new RepeatCancellation(parts[1], parts[0], count,
                    Math.max(count, totals.getOrDefault(key, 0L)), last.get(key)));
        }
        result.sort(Comparator.comparingLong(RepeatCancellation::count).reversed()
                .thenComparing(RepeatCancellation::lastCancelledAt, Comparator.reverseOrder()));
        return result;
    }

    /**
     * For each "ROLE|userId" key: bookings that user had in that role during the window, i.e. created in the
     * window or cancelled in the window. Each counted cancellation is in this set, so the rate is never above 100%.
     */
    private Map<String, Long> totalBookingsInWindow(List<String> keys, Instant since) {
        Map<String, Long> totals = new HashMap<>();
        if (keys.isEmpty()) {
            return totals;
        }
        List<String> customers = new ArrayList<>();
        List<String> providers = new ArrayList<>();
        for (String key : keys) {
            String[] parts = key.split("\\|", 2);
            ("CUSTOMER".equals(parts[0]) ? customers : providers).add(parts[1]);
        }
        Criteria inWindow = new Criteria().orOperator(
                Criteria.where("createdAt").gte(since),
                Criteria.where("timeline").elemMatch(Criteria.where("toStatus").in(CANCELLED)
                        .and("changedAt").gte(since)));
        Criteria ofUsers = new Criteria().orOperator(
                Criteria.where("customerId").in(customers), Criteria.where("providerId").in(providers));
        Query query = new Query(new Criteria().andOperator(ofUsers, inWindow));
        query.fields().include("customerId", "providerId");
        for (Booking b : mongoTemplate.find(query, Booking.class)) {
            if (customers.contains(b.getCustomerId())) {
                totals.merge("CUSTOMER|" + b.getCustomerId(), 1L, Long::sum);
            }
            if (providers.contains(b.getProviderId())) {
                totals.merge("PROVIDER|" + b.getProviderId(), 1L, Long::sum);
            }
        }
        return totals;
    }

    @Override
    public Map<BookingStatus, Long> countByStatus() {
        assertStatsAccess();
        Map<BookingStatus, Long> result = new EnumMap<>(BookingStatus.class);
        for (BookingStatus s : BookingStatus.values()) {
            result.put(s, 0L);
        }
        Aggregation agg = Aggregation.newAggregation(Aggregation.group("status").count().as("count"));
        for (Document d : mongoTemplate.aggregate(agg, Booking.class, Document.class).getMappedResults()) {
            Object status = d.get("_id");
            if (status != null) {
                try {
                    result.put(BookingStatus.valueOf(status.toString()), ((Number) d.get("count")).longValue());
                } catch (IllegalArgumentException ignored) {
                    // unknown legacy status value: not counted
                }
            }
        }
        return result;
    }

    @Override
    public List<DailyCount> countCreatedPerDay(LocalDate from, LocalDate to) {
        assertStatsAccess();
        if (from == null || to == null || to.isBefore(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "from and to are required, from <= to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAY_RANGE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "date range must be at most " + MAX_DAY_RANGE + " days");
        }
        Instant start = from.atStartOfDay(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        Query query = new Query(Criteria.where("createdAt").gte(start).lt(end));
        query.fields().include("createdAt");

        Map<LocalDate, Long> days = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.put(d, 0L);
        }
        for (Booking b : mongoTemplate.find(query, Booking.class)) {
            LocalDate d = b.getCreatedAt().atZone(SlotServiceImpl.BUSINESS_ZONE).toLocalDate();
            days.computeIfPresent(d, (k, v) -> v + 1);
        }
        return days.entrySet().stream().map(e -> new DailyCount(e.getKey(), e.getValue())).toList();
    }

    /** Admin roles, or a background job (no user and no HTTP request). */
    private static void assertStatsAccess() {
        if (CurrentUser.isAuthenticated()) {
            if (!STATS_ROLES.contains(CurrentUser.getRole())) {
                throw new AccessDeniedException("Access denied: booking stats are for admins only");
            }
            return;
        }
        if (RequestContextHolder.getRequestAttributes() != null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
    }

    static BookingSummary toSummary(Booking b) {
        return new BookingSummary(b.getId(), b.getRef(), b.getStatus(), b.getCustomerId(), b.getProviderId(),
                b.getListingId(), b.getPriceSnapshot() == null ? null : b.getPriceSnapshot().serviceTitle(),
                b.getPriceSnapshot() == null ? null : b.getPriceSnapshot().categoryId(),
                b.getSlotStart(), b.getSlotEnd());
    }
}
