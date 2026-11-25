package com.gamagerecruiters.marketplace.modules.adminusers.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AccountActionResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUserDetailResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUserRow;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUsersSummaryResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.RegistrationsResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.Section;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUserAccountService;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUserListingService;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUsersCsvExporter;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUsersQueryService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/** Role access for every admin users endpoint, through the real SecurityConfig and admin access policy. */
@WebMvcTest(AdminUsersController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminUsersExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class AdminUsersControllerTest {

    private static final String BASE = "/api/v1/admin/users";
    private static final String ID = "65f1c0aa11bb22cc33a1b2c3";
    private static final String SUSPEND_BODY =
            "{\"reasonCode\":\"REPEATED_CANCELLATIONS\",\"note\":\"Repeated no-shows reported by customers\"}";
    private static final String REACTIVATE_BODY = "{\"note\":\"Appeal accepted after reviewing bookings\"}";
    private static final String REVIEW_BODY = "{\"decision\":\"MAKE_VISIBLE\",\"note\":\"Contact details were removed\"}";
    private static final String SYNC = "/listing-visibility/sync";
    private static final String LISTING_ID = "65f1c0aa11bb22cc33a1aaaa";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;
    @MockitoBean
    private UserInternalService userInternalService;
    @MockitoBean
    private AdminUsersQueryService queryService;
    @MockitoBean
    private AdminUsersCsvExporter csvExporter;
    @MockitoBean
    private AdminUserAccountService accountService;
    @MockitoBean
    private AdminUserListingService listingService;

    private String bearer(Role role) {
        String token = "token-" + role.name();
        String userId = "user-" + role.name();
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(userId);
        when(tokenService.getRoleFromToken(token)).thenReturn(role);
        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(userId)).thenReturn(user);
        return "Bearer " + token;
    }

    private static AdminUserDetailResponse detail() {
        Instant joined = Instant.parse("2026-01-01T00:00:00Z");
        return new AdminUserDetailResponse(
                new AdminUserDetailResponse.Profile(ID, "Nimal Perera", Role.PROVIDER, UserStatus.ACTIVE,
                        "nimal@example.test", "+94770000000", joined, Section.notConnected(), true, Section.of(true),
                        Section.notConnected(), Section.notConnected()),
                Section.notConnected(), Section.notConnected(), Section.notConnected(), Section.notConnected(),
                Section.notConnected(), Section.notConnected(), Section.notConnected(), Section.notConnected(),
                Section.of(List.of()), new AdminUserDetailResponse.Actions(true, false, true));
    }

    private void stubReads() {
        when(queryService.list(anyString(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new AdminPageResponse<>(
                List.of(new AdminUserRow(ID, "Nimal Perera", "nimal@example.test", Role.PROVIDER,
                        Instant.parse("2026-01-01T00:00:00Z"), UserStatus.ACTIVE, null)), 0, 8, 1, 1));
        when(queryService.summary(anyString())).thenReturn(new AdminUsersSummaryResponse(
                Section.of(new AdminUsersSummaryResponse.Count(10, Section.notConnected())),
                Section.of(new AdminUsersSummaryResponse.Count(4, Section.notConnected())),
                Section.of(new AdminUsersSummaryResponse.Count(1, Section.notConnected()))));
        when(queryService.registrations(anyString(), any()))
                .thenReturn(new RegistrationsResponse("WEEKS", false, Section.NOT_CONNECTED, null));
        when(queryService.detail(eq(ID), anyString(), any())).thenReturn(detail());
        when(listingService.detail(ID, LISTING_ID)).thenReturn(Section.notConnected());
        when(listingService.page(eq(ID), anyInt(), anyInt())).thenReturn(Section.of(new AdminPageResponse<>(List.of(
                new ProviderListingPort.ListingRow(LISTING_ID, "Deep Cleaning", "Cleaning", null, "FIXED", "PAUSED",
                        List.of(), Instant.parse("2026-10-24T00:00:00Z"))), 0, 5, 1, 1)));
        when(csvExporter.export(anyString(), any(), any(), any())).thenReturn(new AdminUsersCsvExporter.Export(
                "﻿id,name,email,role,joined,status\r\n".getBytes(StandardCharsets.UTF_8), "users-2026-11-19.csv",
                0, 0, false));
    }

    // ---------- 401 ----------

    @Test
    @DisplayName("every endpoint: 401 without a token")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get(BASE + "/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/registrations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/export.csv")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/" + ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/" + ID + "/suspend").contentType(MediaType.APPLICATION_JSON).content(SUSPEND_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/" + ID + "/reactivate").contentType(MediaType.APPLICATION_JSON).content(REACTIVATE_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/" + ID + "/listings/" + LISTING_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/" + ID + "/listings")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/" + ID + SYNC)).andExpect(status().isUnauthorized());
        verifyNoInteractions(queryService, csvExporter, accountService, listingService);
    }

    // ---------- reads ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    @DisplayName("reads (list, summary, registrations, export, detail): ADMIN and SUPER_ADMIN (read-only) -> 200")
    void readsAllowed(Role role) throws Exception {
        stubReads();
        String auth = bearer(role);
        String actor = "user-" + role.name();

        mockMvc.perform(get(BASE).header("Authorization", auth).param("q", "nimal").param("role", "PROVIDER")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ID))
                .andExpect(jsonPath("$.content[0].pro").doesNotExist());
        verify(queryService).list(actor, "nimal", "PROVIDER", "ACTIVE", 0, 8);
        mockMvc.perform(get(BASE + "/summary").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers.available").value(true))
                .andExpect(jsonPath("$.customers.data.total").value(10))
                .andExpect(jsonPath("$.customers.data.joinedLast7Days.available").value(false))
                .andExpect(jsonPath("$.customers.data.joinedLast7Days.data").doesNotExist());
        mockMvc.perform(get(BASE + "/registrations").param("period", "WEEKS").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.buckets").doesNotExist());
        mockMvc.perform(get(BASE + "/export.csv").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"users-2026-11-19.csv\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Export-Truncated", "false"));
        mockMvc.perform(get(BASE + "/" + ID).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.email").value("nimal@example.test"))
                .andExpect(jsonPath("$.bookingStats.available").value(false))
                .andExpect(jsonPath("$.bookingStats.reason").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.bookingStats.data").doesNotExist())
                .andExpect(jsonPath("$.profile.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.profile.password").doesNotExist())
                .andExpect(jsonPath("$.profile.token").doesNotExist())
                .andExpect(jsonPath("$.profile.failedLoginCount").doesNotExist())
                .andExpect(jsonPath("$.actions.canSuspend").value(true));
        verify(queryService).detail(ID, actor, role);
        mockMvc.perform(get(BASE + "/" + ID + "/listings/" + LISTING_ID).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_CONNECTED"));
        mockMvc.perform(get(BASE + "/" + ID + "/listings").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.data.content[0].status").value("PAUSED"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        verify(listingService).page(ID, 0, 5);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER", "PROVIDER"})
    @DisplayName("reads: every other role -> 403")
    void readsForbidden(Role role) throws Exception {
        String auth = bearer(role);

        mockMvc.perform(get(BASE).header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        mockMvc.perform(get(BASE + "/summary").header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/registrations").header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/export.csv").header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + ID).header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + ID + "/listings/" + LISTING_ID).header("Authorization", auth))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + ID + "/listings").header("Authorization", auth))
                .andExpect(status().isForbidden());
        verifyNoInteractions(queryService, csvExporter, accountService, listingService);
    }

    // ---------- writes ----------

    @Test
    @DisplayName("suspend / reactivate / listing visibility sync: ADMIN -> 200, as the logged-in admin with its role")
    void adminCanWrite() throws Exception {
        when(accountService.suspend(eq("user-ADMIN"), eq(Role.ADMIN), eq(ID), anyString(), anyString())).thenReturn(
                new AccountActionResponse(ID, Role.PROVIDER, UserStatus.ACTIVE, UserStatus.SUSPENDED, true, "audit-1"));
        when(accountService.reactivate(eq("user-ADMIN"), eq(Role.ADMIN), eq(ID), anyString())).thenReturn(
                new AccountActionResponse(ID, Role.PROVIDER, UserStatus.SUSPENDED, UserStatus.ACTIVE, true, "audit-2"));
        String auth = bearer(Role.ADMIN);

        mockMvc.perform(post(BASE + "/" + ID + "/suspend").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(SUSPEND_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.auditLogId").value("audit-1"));
        verify(accountService).suspend("user-ADMIN", Role.ADMIN, ID, "REPEATED_CANCELLATIONS",
                "Repeated no-shows reported by customers");
        mockMvc.perform(post(BASE + "/" + ID + "/reactivate").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(REACTIVATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        verify(accountService).reactivate("user-ADMIN", Role.ADMIN, ID, "Appeal accepted after reviewing bookings");
        when(accountService.syncListingVisibility("user-ADMIN", ID)).thenReturn(new AccountActionResponse(ID,
                Role.PROVIDER, UserStatus.SUSPENDED, UserStatus.SUSPENDED, false, null, true));
        mockMvc.perform(post(BASE + "/" + ID + SYNC).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingVisibilitySynced").value(true))
                .andExpect(jsonPath("$.changed").value(false));
        verify(accountService).syncListingVisibility("user-ADMIN", ID);
    }

    @Test
    @DisplayName("suspend of a provider whose listing update failed: 200 with listingVisibilitySynced=false")
    void listingVisibilityNotSynced() throws Exception {
        when(accountService.suspend(eq("user-ADMIN"), eq(Role.ADMIN), eq(ID), anyString(), anyString())).thenReturn(
                new AccountActionResponse(ID, Role.PROVIDER, UserStatus.ACTIVE, UserStatus.SUSPENDED, true, "audit-1",
                        false));

        mockMvc.perform(post(BASE + "/" + ID + "/suspend").header("Authorization", bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(SUSPEND_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.listingVisibilitySynced").value(false));
    }

    @Test
    @DisplayName("the removed listing decision endpoint answers 404 (for ADMIN and SUPER_ADMIN) and calls nothing")
    void listingDecisionEndpointRemoved() throws Exception {
        for (Role role : List.of(Role.ADMIN, Role.SUPER_ADMIN)) {
            mockMvc.perform(post(BASE + "/" + ID + "/listings/" + LISTING_ID + "/review")
                            .header("Authorization", bearer(role))
                            .contentType(MediaType.APPLICATION_JSON).content(REVIEW_BODY))
                    .andExpect(status().isNotFound());
        }
        verifyNoInteractions(accountService, listingService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER",
            "PROVIDER"})
    @DisplayName("suspend / reactivate / listing visibility sync: SUPER_ADMIN (read-only), other admins, customers "
            + "and providers -> 403")
    void othersCannotWrite(Role role) throws Exception {
        String auth = bearer(role);

        mockMvc.perform(post(BASE + "/" + ID + "/suspend").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(SUSPEND_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        mockMvc.perform(post(BASE + "/" + ID + "/reactivate").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(REACTIVATE_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/" + ID + SYNC).header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        verifyNoInteractions(accountService, listingService);
    }

    // ---------- error codes ----------

    @Test
    @DisplayName("service errors keep their status and code: 409, 503, 403, 404, 400; bad page / missing body -> 400")
    void errorCodes() throws Exception {
        String auth = bearer(Role.ADMIN);
        when(accountService.suspend(anyString(), any(), eq("stub"), anyString(), anyString())).thenThrow(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "USER_ACCOUNT_ACTION_UNAVAILABLE", "x"));
        when(accountService.suspend(anyString(), any(), eq("admin"), anyString(), anyString())).thenThrow(new ApiException(
                HttpStatus.FORBIDDEN, "USER_ACTION_NOT_ALLOWED", "x"));
        when(accountService.suspend(anyString(), any(), eq("raced"), anyString(), anyString())).thenThrow(
                new com.gamagerecruiters.marketplace.common.exception.ResourceConflictException("CONCURRENT_MODIFICATION",
                        "The account status was changed by someone else. Reload the account and try again"));
        when(accountService.syncListingVisibility(anyString(), eq(ID))).thenThrow(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "LISTING_VISIBILITY_SYNC_FAILED", "x"));
        when(queryService.detail(eq("missing"), anyString(), any())).thenThrow(new com.gamagerecruiters.marketplace.common.exception
                .ResourceNotFoundException("USER_NOT_FOUND", "User not found"));

        mockMvc.perform(post(BASE + "/raced/suspend").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(SUSPEND_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.message")
                        .value("The account status was changed by someone else. Reload the account and try again"));
        mockMvc.perform(post(BASE + "/" + ID + SYNC).header("Authorization", auth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("LISTING_VISIBILITY_SYNC_FAILED"));
        mockMvc.perform(post(BASE + "/stub/suspend").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(SUSPEND_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("USER_ACCOUNT_ACTION_UNAVAILABLE"));
        mockMvc.perform(post(BASE + "/admin/suspend").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(SUSPEND_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("USER_ACTION_NOT_ALLOWED"));
        mockMvc.perform(get(BASE + "/missing").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
        mockMvc.perform(get(BASE).param("page", "abc").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
        mockMvc.perform(post(BASE + "/" + ID + "/suspend").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }
}
