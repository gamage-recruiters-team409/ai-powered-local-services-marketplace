package com.gamagerecruiters.marketplace.modules.adminusers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
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
import com.gamagerecruiters.marketplace.modules.adminusers.port.stub.StubUserBookingStatsAdapter;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.Granularity;

class AdminUsersQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z"); // a Thursday
    private static final String ACTOR = "admin-1";
    private static final String PROVIDER_ID = "65f1c0aa11bb22cc33a1b2c3";
    private static final String CUSTOMER_ID = "65f1c0aa11bb22cc33a1b2c4";

    private UserDirectoryPort directory;
    private UserBookingStatsPort bookings;
    private ProviderReviewStatsPort reviews;
    private ProviderProfilePort profiles;
    private ProviderListingPort listings;
    private UserDisputeStatsPort disputes;
    private AccountActionAuditPort audit;
    private AdminUsersQueryService service;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        directory = mock(UserDirectoryPort.class);
        bookings = mock(UserBookingStatsPort.class);
        reviews = mock(ProviderReviewStatsPort.class);
        profiles = mock(ProviderProfilePort.class);
        listings = mock(ProviderListingPort.class);
        disputes = mock(UserDisputeStatsPort.class);
        audit = mock(AccountActionAuditPort.class);
        service = new AdminUsersQueryService(directory, bookings, reviews, profiles, listings, disputes, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UserAccount account(String id, Role role, UserStatus status) {
        return new UserAccount(id, "Nimal Perera", "nimal@example.test", "+94770000000", role, status, true,
                NOW.minusSeconds(86400 * 30));
    }

    private void allPortsAvailable() {
        when(bookings.totals(anyString(), any())).thenReturn(Optional.of(new UserBookingStatsPort.BookingTotals(10, 7, 2, 1)));
        when(bookings.bookingsPerMonth(anyString(), any(), anyList())).thenReturn(Optional.of(List.of(
                new UserBookingStatsPort.MonthCount("2026-11", Instant.parse("2026-11-01T00:00:00Z"), 3))));
        when(bookings.outcomes(anyString(), any())).thenReturn(Optional.of(new UserBookingStatsPort.BookingOutcomes(7, 1, 1, 1, 0)));
        when(bookings.cancellations(anyString(), any())).thenReturn(Optional.of(new UserBookingStatsPort.Cancellations(2, 1, 1)));
        when(bookings.queued(anyString(), any(), anyInt())).thenReturn(Optional.of(List.of(
                new UserBookingStatsPort.QueuedBooking("b-1", "Deep cleaning", "CONFIRMED", NOW.plusSeconds(3600), "c-9", "Nimal", new java.math.BigDecimal("8500"), "LKR"))));
        when(reviews.rating(anyString())).thenReturn(Optional.of(new ProviderReviewStatsPort.Rating(4.6, 12)));
        when(profiles.verified(anyString())).thenReturn(Optional.of(true));
        when(profiles.serviceArea(anyString())).thenReturn(Optional.of(new ProviderProfilePort.ServiceArea(List.of("Colombo", "Gampaha"))));
        when(profiles.pro(anyString())).thenReturn(Optional.of(true));
        org.mockito.Mockito.doReturn(new ProviderListingPort.ListingCounts(9, 4, 2, 1, 1, 1, 0)).when(listings)
                .counts(anyString());
        when(disputes.stats(anyString(), anyInt())).thenReturn(Optional.of(new UserDisputeStatsPort.DisputeStats(3, 1, List.of())));
        when(audit.recentForTarget(anyString(), anyInt())).thenReturn(Optional.of(List.of()));
    }

    // ---------- account details ----------

    @Test
    @DisplayName("detail, every port available: every section available with the source's values")
    void detailAllAvailable() {
        when(directory.findById(PROVIDER_ID)).thenReturn(Optional.of(account(PROVIDER_ID, Role.PROVIDER, UserStatus.ACTIVE)));
        allPortsAvailable();

        AdminUserDetailResponse d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);

        assertThat(d.profile().email()).isEqualTo("nimal@example.test");
        assertThat(d.profile().providerVerified().data()).isTrue();
        assertThat(d.profile().serviceArea().data().districts()).containsExactly("Colombo", "Gampaha");
        assertThat(d.profile().lastActive().available()).isTrue();
        assertThat(d.profile().lastActive().data()).as("never signed in -> available, no date").isNull();
        assertThat(d.bookingStats().data().total()).isEqualTo(10);
        assertThat(d.bookingsPerMonth().data()).hasSize(1);
        assertThat(d.bookingOutcomes().available()).isTrue();
        assertThat(d.rating().data().averageRating()).isEqualTo(4.6);
        assertThat(d.rating().data().reviewCount()).isEqualTo(12);
        assertThat(d.cancellations().data().total()).isEqualTo(2);
        assertThat(d.disputes().data().open()).isEqualTo(1);
        assertThat(d.listings().data()).isEqualTo(new ProviderListingPort.ListingCounts(9, 4, 2, 1, 1, 1, 0));
        assertThat(d.queuedBookings().data()).hasSize(1);
        assertThat(d.adminActions().available()).isTrue();
        verify(bookings).bookingsPerMonth(eq(PROVIDER_ID), eq(Role.PROVIDER), eq(List.of(
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"))));
    }

    @Test
    @DisplayName("detail, every port stubbed: every section available=false NOT_CONNECTED with no data and no numbers")
    void detailAllStubbed() throws Exception {
        when(directory.findById(PROVIDER_ID)).thenReturn(Optional.of(account(PROVIDER_ID, Role.PROVIDER, UserStatus.ACTIVE)));
        UserBookingStatsPort stub = new StubUserBookingStatsAdapter();
        service = new AdminUsersQueryService(directory, stub, id -> Optional.empty(), new ProviderProfilePort() {
            public Optional<Boolean> verified(String id) { return Optional.empty(); }
            public Optional<ServiceArea> serviceArea(String id) { return Optional.empty(); }
            public Optional<Boolean> pro(String id) { return Optional.empty(); }
        }, mock(ProviderListingPort.class), (id, limit) -> Optional.empty(), new AccountActionAuditPort() {
            public Ticket begin(StatusChange change) { throw new UnsupportedOperationException(); }
            public Optional<List<AdminAction>> recentForTarget(String id, int limit) { return Optional.empty(); }
            public void listingVisibilityResynced(String id) { }
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        AdminUserDetailResponse d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);

        for (Section<?> s : List.of(d.bookingStats(), d.bookingsPerMonth(), d.bookingOutcomes(), d.rating(),
                d.cancellations(), d.disputes(), d.queuedBookings(), d.adminActions(),
                d.profile().providerVerified(), d.profile().serviceArea(), d.profile().pro())) {
            assertThat(s.available()).isFalse();
            assertThat(s.reason()).isEqualTo(Section.NOT_CONNECTED);
            assertThat(s.data()).isNull();
        }
        JsonNode body = json.readTree(json.writeValueAsString(d));
        assertThat(body.get("bookingStats").toString()).isEqualTo("{\"available\":false,\"reason\":\"NOT_CONNECTED\"}");
        assertThat(body.get("rating").has("data")).isFalse();
        assertThat(body.get("disputes").has("data")).isFalse();
    }

    @Test
    @DisplayName("detail, each port stubbed on its own: only that port's sections turn NOT_CONNECTED")
    void detailOnePortStubbedAtATime() {
        when(directory.findById(PROVIDER_ID)).thenReturn(Optional.of(account(PROVIDER_ID, Role.PROVIDER, UserStatus.ACTIVE)));

        allPortsAvailable();
        when(reviews.rating(anyString())).thenReturn(Optional.empty());
        AdminUserDetailResponse d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);
        assertThat(d.rating().reason()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(d.bookingStats().available()).isTrue();

        allPortsAvailable();
        when(disputes.stats(anyString(), anyInt())).thenReturn(Optional.empty());
        d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);
        assertThat(d.disputes().reason()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(d.rating().available()).isTrue();

        allPortsAvailable();
        when(listings.counts(anyString())).thenThrow(new IllegalStateException("listing down"));
        d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);
        assertThat(d.listings().reason()).isEqualTo(Section.SOURCE_ERROR);
        assertThat(d.disputes().available()).isTrue();

        allPortsAvailable();
        when(profiles.serviceArea(anyString())).thenReturn(Optional.empty());
        when(profiles.pro(anyString())).thenReturn(Optional.empty());
        d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);
        assertThat(d.profile().serviceArea().reason()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(d.profile().pro().reason()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(d.profile().providerVerified().data()).isTrue();

        allPortsAvailable();
        when(audit.recentForTarget(anyString(), anyInt())).thenReturn(Optional.empty());
        d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);
        assertThat(d.adminActions().reason()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(d.listings().available()).isTrue();
    }

    @Test
    @DisplayName("detail of a customer: rating, listings and provider profile parts are NOT_APPLICABLE (not asked)")
    void customerProviderOnlySections() {
        when(directory.findById(CUSTOMER_ID)).thenReturn(Optional.of(account(CUSTOMER_ID, Role.CUSTOMER, UserStatus.ACTIVE)));
        allPortsAvailable();

        AdminUserDetailResponse d = service.detail(CUSTOMER_ID, ACTOR, Role.ADMIN);

        assertThat(d.rating().reason()).isEqualTo(Section.NOT_APPLICABLE);
        assertThat(d.listings().reason()).isEqualTo(Section.NOT_APPLICABLE);
        assertThat(d.profile().serviceArea().reason()).isEqualTo(Section.NOT_APPLICABLE);
        assertThat(d.profile().providerVerified().reason()).isEqualTo(Section.NOT_APPLICABLE);
        assertThat(d.bookingStats().available()).isTrue();
        verify(reviews, never()).rating(anyString());
        verify(listings, never()).counts(anyString());
        verify(profiles, never()).serviceArea(anyString());
    }

    @Test
    @DisplayName("a failing source: that section is SOURCE_ERROR, the rest of the page still loads")
    void failingSource() {
        when(directory.findById(PROVIDER_ID)).thenReturn(Optional.of(account(PROVIDER_ID, Role.PROVIDER, UserStatus.ACTIVE)));
        allPortsAvailable();
        when(reviews.rating(anyString())).thenThrow(new IllegalStateException("down"));

        AdminUserDetailResponse d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);

        assertThat(d.rating().reason()).isEqualTo(Section.SOURCE_ERROR);
        assertThat(d.rating().data()).isNull();
        assertThat(d.disputes().available()).isTrue();
    }

    @Test
    @DisplayName("an authorization failure from a source is not hidden as SOURCE_ERROR")
    void accessDeniedPropagates() {
        when(directory.findById(PROVIDER_ID)).thenReturn(Optional.of(account(PROVIDER_ID, Role.PROVIDER, UserStatus.ACTIVE)));
        allPortsAvailable();
        when(audit.recentForTarget(anyString(), anyInt())).thenThrow(new AccessDeniedException("no"));

        assertThat(catchThrowable(() -> service.detail(PROVIDER_ID, ACTOR, Role.ADMIN))).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("detail of an admin account -> 404 USER_NOT_FOUND (admin accounts are not shown here)")
    void adminAccountsHidden(Role role) {
        when(directory.findById(PROVIDER_ID)).thenReturn(Optional.of(account(PROVIDER_ID, role, UserStatus.ACTIVE)));

        assertNotFound(catchThrowable(() -> service.detail(PROVIDER_ID, ACTOR, Role.ADMIN)));
    }

    @Test
    @DisplayName("detail of an unknown or impossible id -> 404 USER_NOT_FOUND")
    void unknownUser() {
        when(directory.findById(anyString())).thenReturn(Optional.empty());
        assertNotFound(catchThrowable(() -> service.detail(PROVIDER_ID, ACTOR, Role.ADMIN)));
        assertNotFound(catchThrowable(() -> service.detail("x".repeat(65), ACTOR, Role.ADMIN)));
        assertNotFound(catchThrowable(() -> service.detail(" ", ACTOR, Role.ADMIN)));
    }

    @Test
    @DisplayName("actions: ADMIN may suspend an active / reactivate a suspended account and retry a provider's listing "
            + "visibility; SUPER_ADMIN and the admin's own account get nothing; listings are never editable")
    void actions() {
        UserAccount activeProvider = account(PROVIDER_ID, Role.PROVIDER, UserStatus.ACTIVE);
        assertThat(AdminUsersQueryService.actions(activeProvider, ACTOR, Role.ADMIN))
                .isEqualTo(new AdminUserDetailResponse.Actions(true, false, true));
        assertThat(AdminUsersQueryService.actions(account(CUSTOMER_ID, Role.CUSTOMER, UserStatus.SUSPENDED), ACTOR,
                Role.ADMIN)).isEqualTo(new AdminUserDetailResponse.Actions(false, true, false));
        assertThat(AdminUsersQueryService.actions(account(CUSTOMER_ID, Role.CUSTOMER, UserStatus.DEACTIVATED), ACTOR,
                Role.ADMIN)).isEqualTo(new AdminUserDetailResponse.Actions(false, false, false));
        assertThat(AdminUsersQueryService.actions(activeProvider, ACTOR, Role.SUPER_ADMIN))
                .as("read-only").isEqualTo(new AdminUserDetailResponse.Actions(false, false, false));
        assertThat(AdminUsersQueryService.actions(activeProvider, PROVIDER_ID, Role.ADMIN))
                .as("own account").isEqualTo(new AdminUserDetailResponse.Actions(false, false, false));
    }

    @Test
    @DisplayName("detail: last sign-in from the user directory (WP01 lastActiveAt) is available with its date")
    void lastSignedIn() {
        Instant signedIn = NOW.minusSeconds(3600);
        when(directory.findById(PROVIDER_ID)).thenReturn(Optional.of(new UserAccount(PROVIDER_ID, "Nimal Perera",
                "nimal@example.test", null, Role.PROVIDER, UserStatus.ACTIVE, true, NOW.minusSeconds(86400), signedIn)));
        allPortsAvailable();

        AdminUserDetailResponse d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);

        assertThat(d.profile().lastActive().available()).isTrue();
        assertThat(d.profile().lastActive().data()).isEqualTo(signedIn);
    }

    @Test
    @DisplayName("detail JSON: a provider's listing counts, rating and service area are available; booking stats stay "
            + "NOT_CONNECTED (WP05 stub); an audit record carries its listingVisibilitySync marker")
    void detailJson() throws Exception {
        when(directory.findById(PROVIDER_ID)).thenReturn(Optional.of(account(PROVIDER_ID, Role.PROVIDER, UserStatus.SUSPENDED)));
        allPortsAvailable();
        service = new AdminUsersQueryService(directory, new StubUserBookingStatsAdapter(), reviews, profiles, listings,
                disputes, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        when(audit.recentForTarget(anyString(), anyInt())).thenReturn(Optional.of(List.of(
                new AccountActionAuditPort.AdminAction("a1", null, "ADMIN_STATUS_CHANGED", UserStatus.ACTIVE,
                        UserStatus.SUSPENDED, "POLICY_VIOLATION", "SUCCESS", NOW, "FAILED"))));

        JsonNode body = json.readTree(json.writeValueAsString(service.detail(PROVIDER_ID, ACTOR, Role.ADMIN)));

        assertThat(body.at("/listings/available").asBoolean()).isTrue();
        assertThat(body.at("/listings/data/paused").asLong()).isEqualTo(2);
        assertThat(body.at("/rating/data/averageRating").asDouble()).isEqualTo(4.6);
        assertThat(body.at("/rating/data/reviewCount").asLong()).isEqualTo(12);
        assertThat(body.at("/rating/data").has("ratingCounts")).isFalse();
        assertThat(body.at("/profile/serviceArea/data/districts").toString()).isEqualTo("[\"Colombo\",\"Gampaha\"]");
        assertThat(body.at("/bookingStats/available").asBoolean()).isFalse();
        assertThat(body.at("/bookingStats/reason").asText()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(body.at("/adminActions/data/0/listingVisibilitySync").asText()).isEqualTo("FAILED");
        assertThat(body.at("/actions").has("canReviewListings")).isFalse();
        assertThat(body.has("hiddenListings")).isFalse();
    }

    @Test
    @DisplayName("list: sorted newest first with email as the unique tie-breaker (a sort WP01 searchUsers accepts)")
    void listSortHasUniqueTieBreaker() {
        when(directory.search(anyString(), any(), any(), any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        service.list(ACTOR, null, null, null, 0, 8);

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(directory).search(eq(ACTOR), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getSort()).containsExactly(
                org.springframework.data.domain.Sort.Order.desc("createdAt"),
                org.springframework.data.domain.Sort.Order.asc("email"));
    }

    // ---------- summary ----------

    @Test
    @DisplayName("summary: real counts; suspended = suspended customers + providers; joinedLast7Days NOT_CONNECTED "
            + "when the source cannot count by date")
    void summary() {
        when(directory.count(ACTOR, Role.CUSTOMER, null)).thenReturn(120L);
        when(directory.count(ACTOR, Role.PROVIDER, null)).thenReturn(40L);
        when(directory.count(ACTOR, Role.CUSTOMER, UserStatus.SUSPENDED)).thenReturn(3L);
        when(directory.count(ACTOR, Role.PROVIDER, UserStatus.SUSPENDED)).thenReturn(2L);
        when(directory.joinedBetween(anyString(), any(), any(), any())).thenReturn(Optional.empty());

        AdminUsersSummaryResponse s = service.summary(ACTOR);

        assertThat(s.customers().data().total()).isEqualTo(120);
        assertThat(s.providers().data().total()).isEqualTo(40);
        assertThat(s.suspended().data().total()).isEqualTo(5);
        assertThat(s.customers().data().joinedLast7Days().reason()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(s.customers().data().joinedLast7Days().data()).isNull();
        verify(directory).joinedBetween(ACTOR, Role.CUSTOMER, Instant.parse("2026-11-12T10:00:00Z"), NOW);
    }

    @Test
    @DisplayName("summary: a failing count is SOURCE_ERROR for that card only")
    void summaryFailingCount() {
        when(directory.count(ACTOR, Role.CUSTOMER, null)).thenThrow(new IllegalStateException("db"));
        when(directory.count(ACTOR, Role.PROVIDER, null)).thenReturn(40L);
        when(directory.joinedBetween(anyString(), any(), any(), any())).thenReturn(Optional.of(4L));

        AdminUsersSummaryResponse s = service.summary(ACTOR);

        assertThat(s.customers().reason()).isEqualTo(Section.SOURCE_ERROR);
        assertThat(s.providers().data().joinedLast7Days().data()).isEqualTo(4L);
    }

    // ---------- registrations ----------

    @Test
    @DisplayName("registrations: source cannot count by date -> available=false, no buckets")
    void registrationsNotConnected() {
        when(directory.registrations(anyString(), any(), anyList())).thenReturn(Optional.empty());

        RegistrationsResponse r = service.registrations(ACTOR, "weeks");

        assertThat(r.period()).isEqualTo("WEEKS");
        assertThat(r.available()).isFalse();
        assertThat(r.reason()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(r.buckets()).isNull();
    }

    @Test
    @DisplayName("registrations: available -> the source's numbers per bucket; unknown period -> 400")
    void registrationsAvailable() {
        when(directory.registrations(anyString(), any(), anyList())).thenAnswer(inv -> {
            List<UserDirectoryPort.Bucket> buckets = inv.getArgument(2);
            return Optional.of(buckets.stream()
                    .map(b -> new UserDirectoryPort.RegistrationCount(b.start(), b.end(), 2, 1)).toList());
        });

        RegistrationsResponse r = service.registrations(ACTOR, "MONTHS");
        assertThat(r.available()).isTrue();
        assertThat(r.buckets()).hasSize(6);
        assertThat(r.buckets().get(5).customers()).isEqualTo(2);
        verify(directory).registrations(eq(ACTOR), eq(Granularity.MONTH), anyList());
        service.registrations(ACTOR, "WEEKS");
        verify(directory).registrations(eq(ACTOR), eq(Granularity.WEEK), anyList());

        assertApiError(catchThrowable(() -> service.registrations(ACTOR, "DAYS")), HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER");
    }

    @Test
    @DisplayName("buckets: 8 weeks starting Monday 00:00 UTC, 6 months starting the 1st 00:00 UTC, current last")
    void bucketBoundaries() {
        List<UserDirectoryPort.Bucket> weeks = AdminUsersQueryService.buckets(AdminUsersQueryService.Period.WEEKS, NOW);
        assertThat(weeks).hasSize(8);
        assertThat(weeks.get(7).start()).isEqualTo(Instant.parse("2026-11-16T00:00:00Z"));
        assertThat(weeks.get(7).end()).isEqualTo(Instant.parse("2026-11-23T00:00:00Z"));
        assertThat(weeks.get(0).start()).isEqualTo(Instant.parse("2026-09-28T00:00:00Z"));

        List<UserDirectoryPort.Bucket> months = AdminUsersQueryService.buckets(AdminUsersQueryService.Period.MONTHS, NOW);
        assertThat(months).hasSize(6);
        assertThat(months.get(0).start()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(months.get(5).end()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
    }

    // ---------- helpers ----------

    private static void assertNotFound(Throwable thrown) {
        assertApiError(thrown, HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }
}
