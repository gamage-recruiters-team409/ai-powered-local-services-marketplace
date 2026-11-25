package com.gamagerecruiters.marketplace.modules.adminusers.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.domain.AdminUsersRules;
import com.gamagerecruiters.marketplace.modules.adminusers.domain.SuspensionReason;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUserDetailResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUserRow;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUsersSummaryResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.RegistrationsResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.Section;
import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderProfilePort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderReviewStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserBookingStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort.UserAccount;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDisputeStatsPort;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.Granularity;

/**
 * Read side of the admin users screens. This module owns no user data: it aggregates the ports, and every part
 * that comes from another module is a {@link Section}. A port that has no data answers NOT_CONNECTED, a port that
 * fails answers SOURCE_ERROR (logged with the user id, the section and the exception type only). Numbers are never
 * guessed or defaulted to zero.
 *
 * <p>Only CUSTOMER and PROVIDER accounts are listed or shown; any other id answers 404 USER_NOT_FOUND, so admin
 * accounts are not revealed here. Email and phone are never logged.</p>
 */
@Service
public class AdminUsersQueryService {

    public enum Period { WEEKS, MONTHS }

    /** A user id is a MongoDB ObjectId: q in this form is looked up by id instead of searched as text. */
    private static final Pattern OBJECT_ID = Pattern.compile("^[0-9a-fA-F]{24}$");

    private static final Logger log = LoggerFactory.getLogger(AdminUsersQueryService.class);

    private final UserDirectoryPort directory;
    private final UserBookingStatsPort bookings;
    private final ProviderReviewStatsPort reviews;
    private final ProviderProfilePort profiles;
    private final ProviderListingPort listings;
    private final UserDisputeStatsPort disputes;
    private final AccountActionAuditPort audit;
    private final Clock clock;

    @Autowired
    public AdminUsersQueryService(UserDirectoryPort directory, UserBookingStatsPort bookings,
                                  ProviderReviewStatsPort reviews, ProviderProfilePort profiles,
                                  ProviderListingPort listings, UserDisputeStatsPort disputes,
                                  AccountActionAuditPort audit) {
        this(directory, bookings, reviews, profiles, listings, disputes, audit, Clock.systemUTC());
    }

    AdminUsersQueryService(UserDirectoryPort directory, UserBookingStatsPort bookings, ProviderReviewStatsPort reviews,
                           ProviderProfilePort profiles, ProviderListingPort listings, UserDisputeStatsPort disputes,
                           AccountActionAuditPort audit, Clock clock) {
        this.directory = directory;
        this.bookings = bookings;
        this.reviews = reviews;
        this.profiles = profiles;
        this.listings = listings;
        this.disputes = disputes;
        this.audit = audit;
        this.clock = clock;
    }

    /** Validated list / export filters. */
    public record Filters(Set<Role> roles, UserStatus status, String text, String id) {
    }

    // ---------------------------------------------------------------- list

    public AdminPageResponse<AdminUserRow> list(String actorId, String q, String role, String status, int page,
                                                int size) {
        if (page < 0 || size < 1 || size > AdminUsersRules.MAX_PAGE_SIZE) {
            throw badRequest("INVALID_PAGE_REQUEST",
                    "page must be 0 or more and size must be between 1 and " + AdminUsersRules.MAX_PAGE_SIZE);
        }
        Filters filters = filters(q, role, status);
        Page<UserAccount> result = find(actorId, filters, PageRequest.of(page, size, newestFirst()));
        return AdminPageResponse.from(result.map(this::toRow));
    }

    /** Parses and checks q, role and status (shared by the list and the export). */
    public Filters filters(String q, String role, String status) {
        Set<Role> roles = AdminUsersRules.MANAGED_ROLES;
        if (hasText(role)) {
            Role parsed = parse(Role.class, role);
            if (parsed == null || !AdminUsersRules.MANAGED_ROLES.contains(parsed)) {
                throw badRequest("INVALID_FILTER", "role must be CUSTOMER or PROVIDER");
            }
            roles = Set.of(parsed);
        }
        UserStatus statusFilter = null;
        if (hasText(status)) {
            statusFilter = parse(UserStatus.class, status);
            if (statusFilter == null || !AdminUsersRules.FILTER_STATUSES.contains(statusFilter)) {
                throw badRequest("INVALID_FILTER", "status must be ACTIVE, SUSPENDED or PENDING_VERIFICATION");
            }
        }
        String text = null;
        String id = null;
        if (hasText(q)) {
            String trimmed = q.strip();
            if (trimmed.length() > AdminUsersRules.MAX_SEARCH_LENGTH) {
                throw badRequest("INVALID_SEARCH_TEXT",
                        "q must be at most " + AdminUsersRules.MAX_SEARCH_LENGTH + " characters");
            }
            if (OBJECT_ID.matcher(trimmed).matches()) {
                id = trimmed;
            } else {
                text = trimmed;
            }
        }
        return new Filters(roles, statusFilter, text, id);
    }

    /**
     * One page of accounts matching the filters. A q that is a user id is an exact id lookup (0 or 1 result, still
     * subject to the role and status filters); any other q is a name / email search escaped by the user directory.
     */
    public Page<UserAccount> find(String actorId, Filters filters, Pageable pageable) {
        if (filters.id() != null) {
            List<UserAccount> match = directory.findById(filters.id())
                    .filter(u -> filters.roles().contains(u.role()))
                    .filter(u -> filters.status() == null || filters.status() == u.status())
                    .map(List::of)
                    .orElse(List.of());
            List<UserAccount> pageContent = pageable.getPageNumber() == 0 ? match : List.of();
            return new PageImpl<>(pageContent, pageable, match.size());
        }
        return directory.search(actorId, filters.roles(), filters.status(), filters.text(), pageable);
    }

    /**
     * Newest first, then email A-Z as a unique tie-breaker, so accounts with the same createdAt keep one stable order
     * across pages and export batches (no duplicates, none skipped). Email, not id: WP01's searchUsers only accepts
     * createdAt, fullName and email as sort fields (anything else drops the whole sort), and users.email has a
     * unique index with emails stored lowercase.
     */
    public static Sort newestFirst() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("email"));
    }

    private AdminUserRow toRow(UserAccount u) {
        Boolean pro = null;
        if (u.role() == Role.PROVIDER) {
            pro = section(u.id(), "pro", () -> profiles.pro(u.id())).data();
        }
        return new AdminUserRow(u.id(), u.displayName(), u.email(), u.role(), u.joinedAt(), u.status(), pro);
    }

    // ---------------------------------------------------------------- summary

    public AdminUsersSummaryResponse summary(String actorId) {
        Instant weekAgo = clock.instant().minus(Duration.ofDays(7));
        return new AdminUsersSummaryResponse(
                roleCount(actorId, Role.CUSTOMER, weekAgo),
                roleCount(actorId, Role.PROVIDER, weekAgo),
                guarded(null, "suspended", () -> new AdminUsersSummaryResponse.Count(
                        directory.count(actorId, Role.CUSTOMER, UserStatus.SUSPENDED)
                                + directory.count(actorId, Role.PROVIDER, UserStatus.SUSPENDED),
                        Section.notConnected())));
    }

    private Section<AdminUsersSummaryResponse.Count> roleCount(String actorId, Role role, Instant since) {
        Instant now = clock.instant();
        return guarded(null, "count:" + role, () -> new AdminUsersSummaryResponse.Count(
                directory.count(actorId, role, null),
                section(null, "joinedLast7Days:" + role, () -> directory.joinedBetween(actorId, role, since, now))));
    }

    // ---------------------------------------------------------------- registrations

    public RegistrationsResponse registrations(String actorId, String period) {
        Period parsed = hasText(period) ? parse(Period.class, period) : Period.WEEKS;
        if (parsed == null) {
            throw badRequest("INVALID_PARAMETER", "period must be WEEKS or MONTHS");
        }
        List<UserDirectoryPort.Bucket> buckets = buckets(parsed, clock.instant());
        Granularity granularity = parsed == Period.WEEKS ? Granularity.WEEK : Granularity.MONTH;
        Section<List<UserDirectoryPort.RegistrationCount>> counts =
                section(null, "registrations", () -> directory.registrations(actorId, granularity, buckets));
        if (!counts.available()) {
            return new RegistrationsResponse(parsed.name(), false, counts.reason(), null);
        }
        return new RegistrationsResponse(parsed.name(), true, null, counts.data().stream()
                .map(c -> new RegistrationsResponse.Bucket(c.start(), c.end(), c.customers(), c.providers()))
                .toList());
    }

    /** Last 8 weeks (Monday 00:00 UTC) or last 6 months (1st 00:00 UTC), oldest first, current bucket last. */
    static List<UserDirectoryPort.Bucket> buckets(Period period, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<UserDirectoryPort.Bucket> result = new ArrayList<>();
        if (period == Period.WEEKS) {
            LocalDate thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            for (int i = AdminUsersRules.REGISTRATION_WEEKS - 1; i >= 0; i--) {
                LocalDate start = thisWeek.minusWeeks(i);
                result.add(new UserDirectoryPort.Bucket(utc(start), utc(start.plusWeeks(1))));
            }
        } else {
            for (Instant start : monthStarts(now, AdminUsersRules.REGISTRATION_MONTHS)) {
                LocalDate first = LocalDate.ofInstant(start, ZoneOffset.UTC);
                result.add(new UserDirectoryPort.Bucket(start, utc(first.plusMonths(1))));
            }
        }
        return result;
    }

    /** First instant (UTC) of each of the last {@code count} months, oldest first, current month last. */
    static List<Instant> monthStarts(Instant now, int count) {
        YearMonth current = YearMonth.from(LocalDate.ofInstant(now, ZoneOffset.UTC));
        List<Instant> result = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            result.add(utc(current.minusMonths(i).atDay(1)));
        }
        return result;
    }

    private static Instant utc(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    // ---------------------------------------------------------------- account details

    public AdminUserDetailResponse detail(String userId, String actorId, Role actorRole) {
        UserAccount user = findManaged(userId);
        String id = user.id();
        Role role = user.role();
        boolean provider = role == Role.PROVIDER;

        AdminUserDetailResponse.Profile profile = new AdminUserDetailResponse.Profile(
                id, user.displayName(), role, user.status(), user.email(), user.phone(), user.joinedAt(),
                Section.of(user.lastActiveAt()),
                user.emailVerified(),
                provider ? section(id, "providerVerified", () -> profiles.verified(id)) : Section.notApplicable(),
                provider ? section(id, "serviceArea", () -> profiles.serviceArea(id)) : Section.notApplicable(),
                provider ? section(id, "pro", () -> profiles.pro(id)) : Section.notApplicable());

        List<Instant> months = monthStarts(clock.instant(), AdminUsersRules.BOOKING_MONTHS);
        return new AdminUserDetailResponse(
                profile,
                section(id, "bookingStats", () -> bookings.totals(id, role)),
                section(id, "bookingsPerMonth", () -> bookings.bookingsPerMonth(id, role, months)),
                section(id, "bookingOutcomes", () -> bookings.outcomes(id, role)),
                provider ? section(id, "rating", () -> reviews.rating(id)) : Section.notApplicable(),
                section(id, "cancellations", () -> bookings.cancellations(id, role)),
                section(id, "disputes", () -> disputes.stats(id, AdminUsersRules.RECENT_DISPUTES)),
                provider ? guarded(id, "listings", () -> listings.counts(id)) : Section.notApplicable(),
                section(id, "queuedBookings", () -> bookings.queued(id, role, AdminUsersRules.QUEUED_BOOKINGS)),
                section(id, "adminActions", () -> audit.recentForTarget(id, AdminUsersRules.ADMIN_ACTIONS)
                        .map(this::withActorNames)),
                actions(user, actorId, actorRole));
    }

    /** ADMIN edits the ADMIN area; SUPER_ADMIN reads it only. Never on the admin's own account. */
    static AdminUserDetailResponse.Actions actions(UserAccount user, String actorId, Role actorRole) {
        boolean editor = actorRole == Role.ADMIN && actorId != null && !actorId.equals(user.id());
        boolean suspendable = user.status() == UserStatus.ACTIVE || user.status() == UserStatus.PENDING_VERIFICATION
                || user.status() == UserStatus.LOCKED;
        return new AdminUserDetailResponse.Actions(
                editor && suspendable,
                editor && user.status() == UserStatus.SUSPENDED,
                editor && user.role() == Role.PROVIDER);
    }

    /** Audit records with the admin's display name, and a suspension's reason code split from its note. */
    private List<AdminUserDetailResponse.AdminActionView> withActorNames(List<AccountActionAuditPort.AdminAction> records) {
        Map<String, String> names = new HashMap<>();
        return records.stream().map(r -> {
            String name = r.actorId() == null ? "" : names.computeIfAbsent(r.actorId(), this::displayName);
            String actorName = name.isEmpty() ? null : name;
            boolean suspension = r.toStatus() == UserStatus.SUSPENDED;
            String action = suspension ? "SUSPENDED" : r.toStatus() == UserStatus.ACTIVE ? "REACTIVATED" : "STATUS_CHANGED";
            String reasonCode = suspension ? SuspensionReason.codeOf(r.reason()).map(Enum::name).orElse(null) : null;
            String note = suspension ? SuspensionReason.noteOf(r.reason()) : r.reason();
            return new AdminUserDetailResponse.AdminActionView(r.auditLogId(), r.actorId(), actorName, action,
                    r.fromStatus(), r.toStatus(), reasonCode, note, r.outcome(), r.createdAt(),
                    r.listingVisibilitySync());
        }).toList();
    }

    /** The admin's name for the audit table; empty string when it cannot be read (stored as null). */
    private String displayName(String userId) {
        try {
            return directory.findById(userId).map(UserAccount::displayName).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** A CUSTOMER or PROVIDER account, or 404 USER_NOT_FOUND (also for admin accounts and impossible ids). */
    UserAccount findManaged(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > AdminUsersRules.MAX_USER_ID_LENGTH) {
            throw notFound();
        }
        return directory.findById(userId)
                .filter(u -> AdminUsersRules.MANAGED_ROLES.contains(u.role()))
                .orElseThrow(AdminUsersQueryService::notFound);
    }

    static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("USER_NOT_FOUND", "User not found");
    }

    // ---------------------------------------------------------------- helpers

    /** Authorization failures are never hidden as SOURCE_ERROR: they propagate (401 / 403). */
    <T> Section<T> section(String userId, String name, Supplier<Optional<T>> source) {
        try {
            return source.get().map(Section::of).orElseGet(Section::notConnected);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.UNAUTHORIZED || e.getStatus() == HttpStatus.FORBIDDEN) {
                throw e;
            }
            log.warn("event=ADMIN_USERS_SECTION_FAILED userId={} section={} cause={}", userId, name, e.getErrorCode());
            return Section.sourceError();
        } catch (RuntimeException e) {
            log.warn("event=ADMIN_USERS_SECTION_FAILED userId={} section={} cause={}",
                    userId, name, e.getClass().getSimpleName());
            return Section.sourceError();
        }
    }

    <T> Section<T> guarded(String userId, String name, Supplier<T> source) {
        return section(userId, name, () -> Optional.of(source.get()));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
