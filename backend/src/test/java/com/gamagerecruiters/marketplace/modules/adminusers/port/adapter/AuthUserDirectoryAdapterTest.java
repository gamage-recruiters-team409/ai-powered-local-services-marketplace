package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderProfilePort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderReviewStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDisputeStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.stub.StubUserBookingStatsAdapter;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUsersQueryService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;
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
    @DisplayName("adapter: unknown id -> empty; date-based counts and last active are not available (empty)")
    void adapterBehaviour() {
        when(userInternalService.getById("missing")).thenThrow(new ResourceNotFoundException("USER_NOT_FOUND", "x"));

        assertThat(adapter.findById("missing")).isEmpty();
        assertThat(adapter.registrations("a", List.of())).isEmpty();
        assertThat(adapter.joinedSince("a", Role.CUSTOMER, Instant.now())).isEmpty();
        assertThat(adapter.lastActiveAt(ID)).isEmpty();
        assertThat(adapter.supportsAccountActions()).isTrue();
        when(userInternalService.countByRoleAndStatus("a", Role.PROVIDER, UserStatus.SUSPENDED)).thenReturn(4L);
        assertThat(adapter.count("a", Role.PROVIDER, UserStatus.SUSPENDED)).isEqualTo(4);
    }
}
