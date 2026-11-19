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
import java.util.Map;
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
        when(reviews.rating(anyString())).thenReturn(Optional.of(new ProviderReviewStatsPort.Rating(4.6, 12, Map.of(5, 9L))));
        when(profiles.verified(anyString())).thenReturn(Optional.of(true));
        when(profiles.serviceArea(anyString())).thenReturn(Optional.of(new ProviderProfilePort.ServiceArea("Colombo", "Dehiwala")));
        when(profiles.pro(anyString())).thenReturn(Optional.of(true));
        when(listings.hiddenListings(anyString(), anyInt())).thenReturn(Optional.of(
                new ProviderListingPort.HiddenListings(1, 0, 2, List.of())));
        when(disputes.stats(anyString(), anyInt())).thenReturn(Optional.of(new UserDisputeStatsPort.DisputeStats(3, 1, List.of())));
        when(audit.recentForTarget(anyString(), anyInt())).thenReturn(Optional.of(List.of()));
        when(directory.lastActiveAt(anyString())).thenReturn(Optional.of(NOW.minusSeconds(60)));
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
        assertThat(d.profile().serviceArea().data().district()).isEqualTo("Colombo");
        assertThat(d.profile().lastActive().available()).isTrue();
        assertThat(d.bookingStats().data().total()).isEqualTo(10);
        assertThat(d.bookingsPerMonth().data()).hasSize(1);
        assertThat(d.bookingOutcomes().available()).isTrue();
        assertThat(d.rating().data().averageRating()).isEqualTo(4.6);
        assertThat(d.cancellations().data().total()).isEqualTo(2);
        assertThat(d.disputes().data().open()).isEqualTo(1);
        assertThat(d.hiddenListings().data().paused()).isEqualTo(2);
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
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        AdminUserDetailResponse d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);

        for (Section<?> s : List.of(d.bookingStats(), d.bookingsPerMonth(), d.bookingOutcomes(), d.rating(),
                d.cancellations(), d.disputes(), d.hiddenListings(), d.queuedBookings(), d.adminActions(),
                d.profile().lastActive(), d.profile().providerVerified(), d.profile().serviceArea(), d.profile().pro())) {
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
        when(listings.hiddenListings(anyString(), anyInt())).thenReturn(Optional.empty());
        d = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN);
        assertThat(d.hiddenListings().reason()).isEqualTo(Section.NOT_CONNECTED);
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
        assertThat(d.hiddenListings().available()).isTrue();
    }

    @Test
    @DisplayName("detail of a customer: rating, hidden listings and provider profile parts are NOT_APPLICABLE (not asked)")
    void customerProviderOnlySections() {
        when(directory.findById(CUSTOMER_ID)).thenReturn(Optional.of(account(CUSTOMER_ID, Role.CUSTOMER, UserStatus.ACTIVE)));
        allPortsAvailable();

        AdminUserDetailResponse d = service.detail(CUSTOMER_ID, ACTOR, Role.ADMIN);

        assertThat(d.rating().reason()).isEqualTo(Section.NOT_APPLICABLE);
        assertThat(d.hiddenListings().reason()).isEqualTo(Section.NOT_APPLICABLE);
        assertThat(d.profile().providerVerified().reason()).isEqualTo(Section.NOT_APPLICABLE);
        assertThat(d.bookingStats().available()).isTrue();
        verify(reviews, never()).rating(anyString());
        verify(listings, never()).hiddenListings(anyString(), anyInt());
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
    @DisplayName("actions: ADMIN may suspend an active / reactivate a suspended account and review a provider's "
            + "listings; SUPER_ADMIN and the admin's own account get nothing")
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
    @DisplayName("admin actions: actor name resolved once per admin, suspension reason code split from the note")
    void adminActionsEnriched() {
        when(directory.findById(PROVIDER_ID)).thenReturn(Optional.of(account(PROVIDER_ID, Role.PROVIDER, UserStatus.ACTIVE)));
        when(directory.findById("admin-9")).thenReturn(Optional.of(
                new UserAccount("admin-9", "Alex Dawson", "a@example.test", null, Role.ADMIN, UserStatus.ACTIVE, true, NOW)));
        when(directory.findById("gone")).thenReturn(Optional.empty());
        allPortsAvailable();
        when(audit.recentForTarget(anyString(), anyInt())).thenReturn(Optional.of(List.of(
                new AccountActionAuditPort.AdminAction("a3", "admin-9", "ADMIN_STATUS_CHANGED", UserStatus.SUSPENDED,
                        UserStatus.ACTIVE, "Appeal accepted after review", "SUCCESS", NOW),
                new AccountActionAuditPort.AdminAction("a2", "admin-9", "ADMIN_STATUS_CHANGED", UserStatus.ACTIVE,
                        UserStatus.SUSPENDED, "OFF_PLATFORM_CONTACT: Shared a phone number", "SUCCESS", NOW.minusSeconds(60)),
                new AccountActionAuditPort.AdminAction("a1", "gone", "ADMIN_STATUS_CHANGED", UserStatus.ACTIVE,
                        UserStatus.SUSPENDED, "POLICY_VIOLATION", "SUCCESS", NOW.minusSeconds(120)))));

        List<AdminUserDetailResponse.AdminActionView> actions = service.detail(PROVIDER_ID, ACTOR, Role.ADMIN)
                .adminActions().data();

        assertThat(actions.get(0).action()).isEqualTo("REACTIVATED");
        assertThat(actions.get(0).note()).isEqualTo("Appeal accepted after review");
        assertThat(actions.get(0).reasonCode()).isNull();
        assertThat(actions.get(0).actorName()).isEqualTo("Alex Dawson");
        assertThat(actions.get(1).action()).isEqualTo("SUSPENDED");
        assertThat(actions.get(1).reasonCode()).isEqualTo("OFF_PLATFORM_CONTACT");
        assertThat(actions.get(1).note()).isEqualTo("Shared a phone number");
        assertThat(actions.get(2).reasonCode()).isEqualTo("POLICY_VIOLATION");
        assertThat(actions.get(2).note()).isNull();
        assertThat(actions.get(2).actorName()).as("unknown admin").isNull();
        verify(directory, org.mockito.Mockito.times(1)).findById("admin-9");
    }

    // ---------- list ----------

    @Test
    @DisplayName("list: defaults to customers + providers, newest joined first; q is passed as plain text; pro omitted")
    void listDefaults() {
        when(directory.search(eq(ACTOR), any(), isNull(), eq("nimal (50%)"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(account(PROVIDER_ID, Role.PROVIDER, UserStatus.ACTIVE)),
                        PageRequest.of(0, 8), 1));
        when(profiles.pro(anyString())).thenReturn(Optional.empty());

        AdminPageResponse<AdminUserRow> page = service.list(ACTOR, "  nimal (50%) ", null, null, 0, 8);

        assertThat(page.content()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(PROVIDER_ID);
            assertThat(row.pro()).isNull();
        });
        verify(directory).search(eq(ACTOR), eq(Set.of(Role.CUSTOMER, Role.PROVIDER)), isNull(), eq("nimal (50%)"),
                eq(PageRequest.of(0, 8, AdminUsersQueryService.newestFirst())));
    }

    @Test
    @DisplayName("list: q that is a user id is an exact id lookup, still filtered by role and status")
    void listById() {
        when(directory.findById(CUSTOMER_ID)).thenReturn(Optional.of(account(CUSTOMER_ID, Role.CUSTOMER, UserStatus.ACTIVE)));

        assertThat(service.list(ACTOR, CUSTOMER_ID, null, null, 0, 8).totalElements()).isEqualTo(1);
        assertThat(service.list(ACTOR, CUSTOMER_ID, "PROVIDER", null, 0, 8).totalElements()).isZero();
        assertThat(service.list(ACTOR, CUSTOMER_ID, null, "SUSPENDED", 0, 8).totalElements()).isZero();
        verify(directory, never()).search(any(), any(), any(), any(), any());

        when(directory.findById(PROVIDER_ID)).thenReturn(Optional.of(account(PROVIDER_ID, Role.ADMIN, UserStatus.ACTIVE)));
        assertThat(service.list(ACTOR, PROVIDER_ID, null, null, 0, 8).totalElements()).as("admin not listed").isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"role=ADMIN", "role=SUPER_ADMIN", "role=nobody", "status=LOCKED", "status=DEACTIVATED",
            "status=nope"})
    @DisplayName("list: role other than CUSTOMER/PROVIDER or status other than ACTIVE/SUSPENDED/PENDING -> 400")
    void listBadFilters(String filter) {
        String[] kv = filter.split("=");
        Throwable thrown = catchThrowable(() -> service.list(ACTOR, null,
                kv[0].equals("role") ? kv[1] : null, kv[0].equals("status") ? kv[1] : null, 0, 8));
        assertApiError(thrown, HttpStatus.BAD_REQUEST, "INVALID_FILTER");
    }

    @Test
    @DisplayName("list: q over 100 characters and bad paging -> 400")
    void listLimits() {
        assertApiError(catchThrowable(() -> service.list(ACTOR, "q".repeat(101), null, null, 0, 8)),
                HttpStatus.BAD_REQUEST, "INVALID_SEARCH_TEXT");
        assertApiError(catchThrowable(() -> service.list(ACTOR, null, null, null, -1, 8)),
                HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST");
        assertApiError(catchThrowable(() -> service.list(ACTOR, null, null, null, 0, 101)),
                HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST");
    }

    // ---------- summary ----------

    @Test
    @DisplayName("summary: real counts; suspended = suspended customers + providers; joinedLast7Days NOT_CONNECTED")
    void summary() {
        when(directory.count(ACTOR, Role.CUSTOMER, null)).thenReturn(120L);
        when(directory.count(ACTOR, Role.PROVIDER, null)).thenReturn(40L);
        when(directory.count(ACTOR, Role.CUSTOMER, UserStatus.SUSPENDED)).thenReturn(3L);
        when(directory.count(ACTOR, Role.PROVIDER, UserStatus.SUSPENDED)).thenReturn(2L);
        when(directory.joinedSince(anyString(), any(), any())).thenReturn(Optional.empty());

        AdminUsersSummaryResponse s = service.summary(ACTOR);

        assertThat(s.customers().data().total()).isEqualTo(120);
        assertThat(s.providers().data().total()).isEqualTo(40);
        assertThat(s.suspended().data().total()).isEqualTo(5);
        assertThat(s.customers().data().joinedLast7Days().reason()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(s.customers().data().joinedLast7Days().data()).isNull();
        verify(directory).joinedSince(ACTOR, Role.CUSTOMER, Instant.parse("2026-11-12T10:00:00Z"));
    }

    @Test
    @DisplayName("summary: a failing count is SOURCE_ERROR for that card only")
    void summaryFailingCount() {
        when(directory.count(ACTOR, Role.CUSTOMER, null)).thenThrow(new IllegalStateException("db"));
        when(directory.count(ACTOR, Role.PROVIDER, null)).thenReturn(40L);
        when(directory.joinedSince(anyString(), any(), any())).thenReturn(Optional.of(4L));

        AdminUsersSummaryResponse s = service.summary(ACTOR);

        assertThat(s.customers().reason()).isEqualTo(Section.SOURCE_ERROR);
        assertThat(s.providers().data().joinedLast7Days().data()).isEqualTo(4L);
    }

    // ---------- registrations ----------

    @Test
    @DisplayName("registrations: source cannot count by date -> available=false, no buckets")
    void registrationsNotConnected() {
        when(directory.registrations(anyString(), anyList())).thenReturn(Optional.empty());

        RegistrationsResponse r = service.registrations(ACTOR, "weeks");

        assertThat(r.period()).isEqualTo("WEEKS");
        assertThat(r.available()).isFalse();
        assertThat(r.reason()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(r.buckets()).isNull();
    }

    @Test
    @DisplayName("registrations: available -> the source's numbers per bucket; unknown period -> 400")
    void registrationsAvailable() {
        when(directory.registrations(anyString(), anyList())).thenAnswer(inv -> {
            List<UserDirectoryPort.Bucket> buckets = inv.getArgument(1);
            return Optional.of(buckets.stream()
                    .map(b -> new UserDirectoryPort.RegistrationCount(b.start(), b.end(), 2, 1)).toList());
        });

        RegistrationsResponse r = service.registrations(ACTOR, "MONTHS");
        assertThat(r.available()).isTrue();
        assertThat(r.buckets()).hasSize(6);
        assertThat(r.buckets().get(5).customers()).isEqualTo(2);

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
