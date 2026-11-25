package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderProfilePort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderReviewStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDisputeStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.stub.StubUserBookingStatsAdapter;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUsersQueryService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;
import com.gamagerecruiters.marketplace.modules.auth.dto.Granularity;
import com.gamagerecruiters.marketplace.modules.auth.dto.PeriodCount;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/**
 * The real auth adapter plus the real query service, serialized as the API would: the security fields of the User
 * entity (password hash, lock and login counters, consent, must-change-password) never reach a response.
 */
class AuthUserDirectoryAdapterTest {

    private static final String ID = "65f1c0aa11bb22cc33a1b2c3";
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuvC0mpletelyFakeHashValueForTest";

    private UserInternalService userInternalService;
    private AuthUserDirectoryAdapter adapter;
    private AdminUsersQueryService queryService;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        userInternalService = mock(UserInternalService.class);
        adapter = new AuthUserDirectoryAdapter(userInternalService);
        ProviderProfilePort profiles = mock(ProviderProfilePort.class);
        when(profiles.verified(anyString())).thenReturn(Optional.of(true));
        queryService = new AdminUsersQueryService(adapter, new StubUserBookingStatsAdapter(),
                mock(ProviderReviewStatsPort.class), profiles, mock(ProviderListingPort.class),
                mock(UserDisputeStatsPort.class), mock(AccountActionAuditPort.class));
    }

    private static User userWithSecrets() {
        User u = new User();
        u.setId(ID);
        u.setName("Nimal Perera");
        u.setEmail("nimal@example.test");
        u.setPhone("+94770000000");
        u.setPasswordHash(HASH);
        u.setRole(Role.PROVIDER);
        u.setStatus(UserStatus.ACTIVE);
        u.setEmailVerified(true);
        u.setFailedLoginCount(3);
        u.setLockStartedAt(Instant.parse("2026-11-01T00:00:00Z"));
        u.setMustChangePassword(true);
        u.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return u;
    }

    @Test
    @DisplayName("account details JSON: no password hash, token, lock/login counter or must-change-password field")
    void detailHasNoSecurityFields() throws Exception {
        when(userInternalService.getById(ID)).thenReturn(userWithSecrets());

        String body = json.writeValueAsString(queryService.detail(ID, "admin-1", Role.ADMIN));

        assertThat(body).contains("\"email\":\"nimal@example.test\"").contains("\"emailVerified\":true");
        assertThat(body).doesNotContain(HASH).doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("token").doesNotContain("failedLoginCount")
                .doesNotContain("lockStartedAt").doesNotContain("mustChangePassword").doesNotContain("consent");
    }

    @Test
    @DisplayName("list JSON: no security fields and no phone (the list shows only id, name, email, role, joined, status)")
    void listHasNoSecurityFields() throws Exception {
        when(userInternalService.searchUsers(anyString(), any(), any(), any(), any())).thenReturn(new PageImpl<>(
                List.of(AdminUserSummary.fromUser(userWithSecrets())), PageRequest.of(0, 8), 1));

        String body = json.writeValueAsString(queryService.list("admin-1", null, null, null, 0, 8));

        assertThat(body).contains(ID).doesNotContain(HASH).doesNotContainIgnoringCase("password")
                .doesNotContain("+94770000000").doesNotContain("failedLoginCount");
    }

    @Test
    @DisplayName("adapter: unknown id -> empty; counts and last sign-in come from the auth module")
    void adapterBehaviour() {
        when(userInternalService.getById("missing")).thenThrow(new ResourceNotFoundException("USER_NOT_FOUND", "x"));
        assertThat(adapter.findById("missing")).isEmpty();
        assertThat(adapter.supportsAccountActions()).isTrue();
        when(userInternalService.countByRoleAndStatus("a", Role.PROVIDER, UserStatus.SUSPENDED)).thenReturn(4L);
        assertThat(adapter.count("a", Role.PROVIDER, UserStatus.SUSPENDED)).isEqualTo(4);

        User signedIn = userWithSecrets();
        signedIn.setLastActiveAt(Instant.parse("2026-11-18T09:30:00Z"));
        when(userInternalService.getById(ID)).thenReturn(signedIn);
        assertThat(adapter.findById(ID).orElseThrow().lastActiveAt()).isEqualTo(Instant.parse("2026-11-18T09:30:00Z"));
        when(userInternalService.getById(ID)).thenReturn(userWithSecrets());
        assertThat(adapter.findById(ID).orElseThrow().lastActiveAt()).as("never signed in").isNull();
    }

    @Test
    @DisplayName("joinedBetween: countNewUsers for that role over [from, to), periods summed")
    void joinedBetween() {
        Instant from = Instant.parse("2026-11-12T10:00:00Z");
        Instant to = Instant.parse("2026-11-19T10:00:00Z");
        when(userInternalService.countNewUsers("a", Set.of(Role.CUSTOMER), from, to, Granularity.WEEK)).thenReturn(List.of(
                new PeriodCount(Instant.parse("2026-11-09T00:00:00Z"), 3),
                new PeriodCount(Instant.parse("2026-11-16T00:00:00Z"), 4)));

        assertThat(adapter.joinedBetween("a", Role.CUSTOMER, from, to)).contains(7L);
    }

    @Test
    @DisplayName("registrations: one countNewUsers call per role over the whole range; each bucket takes its period, "
            + "a period missing from the answer is 0")
    void registrations() {
        Instant w1 = Instant.parse("2026-11-09T00:00:00Z");
        Instant w2 = Instant.parse("2026-11-16T00:00:00Z");
        Instant end = Instant.parse("2026-11-23T00:00:00Z");
        when(userInternalService.countNewUsers("a", Set.of(Role.CUSTOMER), w1, end, Granularity.WEEK))
                .thenReturn(List.of(new PeriodCount(w1, 5), new PeriodCount(w2, 0)));
        when(userInternalService.countNewUsers("a", Set.of(Role.PROVIDER), w1, end, Granularity.WEEK))
                .thenReturn(List.of(new PeriodCount(w2, 2)));

        List<UserDirectoryPort.RegistrationCount> counts = adapter.registrations("a", Granularity.WEEK,
                List.of(new UserDirectoryPort.Bucket(w1, w2), new UserDirectoryPort.Bucket(w2, end))).orElseThrow();

        assertThat(counts).containsExactly(
                new UserDirectoryPort.RegistrationCount(w1, w2, 5, 0),
                new UserDirectoryPort.RegistrationCount(w2, end, 0, 2));
        assertThat(adapter.registrations("a", Granularity.MONTH, List.of())).contains(List.of());
    }

    @Test
    @DisplayName("changeStatus: ONE atomic 4-argument call with the signed-in admin and the expected status; no "
            + "separate status read and never the deprecated 2-argument method")
    void changeStatusIsAtomic() {
        User updated = userWithSecrets();
        updated.setStatus(UserStatus.SUSPENDED);
        when(userInternalService.changeStatus("admin-1", ID, UserStatus.ACTIVE, UserStatus.SUSPENDED))
                .thenReturn(updated);

        assertThat(adapter.changeStatus("admin-1", ID, UserStatus.ACTIVE, UserStatus.SUSPENDED).status())
                .isEqualTo(UserStatus.SUSPENDED);
        verify(userInternalService).changeStatus("admin-1", ID, UserStatus.ACTIVE, UserStatus.SUSPENDED);
        verify(userInternalService, never()).getById(anyString());
        verify(userInternalService, never()).changeStatus(anyString(), any(UserStatus.class));
    }

    @Test
    @DisplayName("changeStatus: auth's 409 CONCURRENT_MODIFICATION -> same code, reload-and-try-again message")
    void concurrentModificationIsMapped() {
        when(userInternalService.changeStatus("admin-1", ID, UserStatus.ACTIVE, UserStatus.SUSPENDED))
                .thenThrow(new ResourceConflictException("CONCURRENT_MODIFICATION",
                        "User status was modified concurrently. Expected ACTIVE but current status is SUSPENDED."));

        Throwable thrown = catchThrowable(
                () -> adapter.changeStatus("admin-1", ID, UserStatus.ACTIVE, UserStatus.SUSPENDED));

        assertThat(thrown).isInstanceOf(ResourceConflictException.class);
        ApiException api = (ApiException) thrown;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(api.getErrorCode()).isEqualTo(UserDirectoryPort.CONCURRENT_MODIFICATION);
        assertThat(api.getMessage())
                .isEqualTo("The account status was changed by someone else. Reload the account and try again");
    }

    @Test
    @DisplayName("changeStatus: auth's other errors pass through unchanged (404 USER_NOT_FOUND, role errors)")
    void otherErrorsPassThrough() {
        ResourceNotFoundException missing = new ResourceNotFoundException("USER_NOT_FOUND", "User not found");
        when(userInternalService.changeStatus("admin-1", "x", UserStatus.ACTIVE, UserStatus.SUSPENDED))
                .thenThrow(missing);
        assertThat(catchThrowable(() -> adapter.changeStatus("admin-1", "x", UserStatus.ACTIVE, UserStatus.SUSPENDED)))
                .isSameAs(missing);

        for (String code : List.of("CANNOT_MODIFY_SELF", "ROLE_HIERARCHY_VIOLATION", "ACCOUNT_DEACTIVATED")) {
            ApiException roleError = new ApiException(HttpStatus.FORBIDDEN, code, code);
            org.mockito.Mockito.doThrow(roleError).when(userInternalService)
                    .changeStatus("admin-1", ID, UserStatus.ACTIVE, UserStatus.SUSPENDED);
            assertThat(catchThrowable(
                    () -> adapter.changeStatus("admin-1", ID, UserStatus.ACTIVE, UserStatus.SUSPENDED)))
                    .as(code).isSameAs(roleError);
        }
        ApiException last = new ApiException(HttpStatus.CONFLICT, "LAST_ACTIVE_SUPER_ADMIN", "x");
        org.mockito.Mockito.doThrow(last).when(userInternalService)
                .changeStatus("admin-1", ID, UserStatus.ACTIVE, UserStatus.SUSPENDED);
        assertThat(catchThrowable(() -> adapter.changeStatus("admin-1", ID, UserStatus.ACTIVE, UserStatus.SUSPENDED)))
                .as("another 409 is not turned into CONCURRENT_MODIFICATION").isSameAs(last);
    }
}
