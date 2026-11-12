package com.gamagerecruiters.marketplace.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccountResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccountSummaryResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminStatusChangeResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.CreateAdminAccountRequest;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccountService;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAuditLogService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/**
 * Role access, routing and request validation for every Super Admin endpoint, through the real
 * SecurityConfig, JWT filter and access policy. The service is mocked.
 */
@WebMvcTest({AdminAccountController.class, AdminAuditLogController.class})
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class SuperAdminEndpointsAccessTest {

    private static final String ACCOUNTS_URL = "/api/v1/admin/accounts";
    private static final String STATUS_URL = ACCOUNTS_URL + "/admin-7/status";
    private static final String SUMMARY_URL = ACCOUNTS_URL + "/summary";
    private static final String AUDIT_URL = "/api/v1/admin/audit-logs";
    private static final String STATUS_BODY = "{\"status\":\"SUSPENDED\",\"reason\":\"Repeated policy violations\"}";
    private static final String PASSWORD = "TempPass#2026xyz";
    private static final String CREATE_BODY = "{\"fullName\":\"Nimal Perera\",\"email\":\"nimal.ops@example.com\","
            + "\"role\":\"MODERATOR\",\"temporaryPassword\":\"" + PASSWORD + "\"}";
    private static final String SUPER_ID = "user-SUPER_ADMIN";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private AdminAccountService adminAccountService;

    @MockitoBean
    private AdminAuditLogService adminAuditLogService;

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

    /** One fresh request per Super Admin endpoint, each with a valid body where one is needed. */
    private static Map<String, Supplier<MockHttpServletRequestBuilder>> endpoints() {
        return Map.of(
                "POST /accounts", () -> post(ACCOUNTS_URL).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY),
                "GET /accounts", () -> get(ACCOUNTS_URL),
                "GET /accounts/summary", () -> get(SUMMARY_URL),
                "PATCH /accounts/{id}/status", () -> patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content(STATUS_BODY),
                "GET /audit-logs", () -> get(AUDIT_URL));
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return post(ACCOUNTS_URL).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---------- 401 ----------

    @Test
    @DisplayName("No token -> 401 UNAUTHORIZED on every endpoint; the services are never called")
    void unauthenticated() throws Exception {
        for (var endpoint : endpoints().entrySet()) {
            mockMvc.perform(endpoint.getValue().get())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(adminAccountService, adminAuditLogService);
    }

    // ---------- 403 for every role except SUPER_ADMIN ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER", "PROVIDER"})
    @DisplayName("Every role except SUPER_ADMIN -> 403 ACCESS_DENIED on every endpoint")
    void forbiddenForOtherRoles(Role role) throws Exception {
        String auth = bearer(role);
        for (var endpoint : endpoints().entrySet()) {
            mockMvc.perform(endpoint.getValue().get().header("Authorization", auth))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        }
        verifyNoInteractions(adminAccountService, adminAuditLogService);
    }

    // ---------- SUPER_ADMIN allowed ----------

    @Test
    @DisplayName("POST /accounts as SUPER_ADMIN -> 201 with the account and mustChangePassword, never the password")
    void createAllowed() throws Exception {
        when(adminAccountService.createAdminAccount(eq(SUPER_ID), eq(Role.SUPER_ADMIN), any()))
                .thenReturn(new AdminAccountResponse("new-1", "Nimal Perera", "nimal.ops@example.com", null,
                        Role.MODERATOR, UserStatus.ACTIVE, Instant.parse("2026-11-12T08:00:00Z"), true));

        String body = mockMvc.perform(create(CREATE_BODY).header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("new-1"))
                .andExpect(jsonPath("$.role").value("MODERATOR"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD).doesNotContainIgnoringCase("password\":\"");
    }

    @Test
    @DisplayName("Mass assignment and actor spoofing: extra body fields, ?actorId and headers are ignored; actor = principal")
    void actorAlwaysFromPrincipal() throws Exception {
        String body = "{\"fullName\":\"Nimal Perera\",\"email\":\"nimal.ops@example.com\",\"role\":\"MODERATOR\","
                + "\"temporaryPassword\":\"" + PASSWORD + "\",\"status\":\"SUSPENDED\",\"mustChangePassword\":false,"
                + "\"actorId\":\"evil\",\"id\":\"chosen-id\",\"passwordHash\":\"x\"}";

        mockMvc.perform(create(body).param("actorId", "evil").header("X-Actor-Id", "evil")
                        .header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateAdminAccountRequest> request = ArgumentCaptor.forClass(CreateAdminAccountRequest.class);
        verify(adminAccountService).createAdminAccount(eq(SUPER_ID), eq(Role.SUPER_ADMIN), request.capture());
        assertThat(request.getValue()).isEqualTo(
                new CreateAdminAccountRequest("Nimal Perera", "nimal.ops@example.com", Role.MODERATOR, PASSWORD));

        mockMvc.perform(get(ACCOUNTS_URL).param("actorId", "evil").header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isOk());
        verify(adminAccountService).listAccounts(eq(SUPER_ID), any(), any(), any(), anyInt(), anyInt(), any());

        mockMvc.perform(get(SUMMARY_URL).param("actorId", "evil").header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isOk());
        verify(adminAccountService).summary(SUPER_ID);
    }

    @Test
    @DisplayName("GET /accounts as SUPER_ADMIN -> 200 page; filters and paging are passed to the service")
    void listAllowed() throws Exception {
        when(adminAccountService.listAccounts(SUPER_ID, "ADMIN", "ACTIVE", "kamal", 1, 10, "email,asc"))
                .thenReturn(new AdminPageResponse<>(List.of(), 1, 10, 0, 0));

        mockMvc.perform(get(ACCOUNTS_URL).param("role", "ADMIN").param("status", "ACTIVE").param("q", "kamal")
                        .param("page", "1").param("size", "10").param("sort", "email,asc")
                        .header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    @DisplayName("GET /accounts defaults: page 0, size 20, sort createdAt,desc")
    void listDefaults() throws Exception {
        mockMvc.perform(get(ACCOUNTS_URL).header("Authorization", bearer(Role.SUPER_ADMIN))).andExpect(status().isOk());
        verify(adminAccountService).listAccounts(SUPER_ID, null, null, null, 0, 20, "createdAt,desc");
    }

    @Test
    @DisplayName("Route: GET /accounts/summary reaches the summary endpoint (not captured by a /{id} pattern)")
    void summaryRoute() throws Exception {
        when(adminAccountService.summary(SUPER_ID)).thenReturn(new AdminAccountSummaryResponse(
                3, Map.of(Role.ADMIN, 3L), Map.of(UserStatus.ACTIVE, 3L)));

        mockMvc.perform(get(SUMMARY_URL).header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.byRole.ADMIN").value(3))
                .andExpect(jsonPath("$.byStatus.ACTIVE").value(3));
        verify(adminAccountService).summary(SUPER_ID);
    }

    @Test
    @DisplayName("PATCH status as SUPER_ADMIN -> 200 with the change and audit id")
    void changeStatusAllowed() throws Exception {
        when(adminAccountService.changeStatus(eq(SUPER_ID), eq(Role.SUPER_ADMIN), eq("admin-7"),
                eq(UserStatus.SUSPENDED), eq("Repeated policy violations")))
                .thenReturn(new AdminStatusChangeResponse("admin-7", Role.ADMIN, UserStatus.ACTIVE,
                        UserStatus.SUSPENDED, "audit-1"));

        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content(STATUS_BODY)
                        .header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.auditLogId").value("audit-1"));
    }

    @Test
    @DisplayName("GET audit logs as SUPER_ADMIN -> 200 page")
    void auditLogsAllowed() throws Exception {
        when(adminAuditLogService.search(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AdminPageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get(AUDIT_URL).param("action", "ADMIN_ACCOUNT_CREATED")
                        .header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ---------- 400 validation: create ----------

    @Test
    @DisplayName("POST /accounts: empty body -> 400 VALIDATION_FAILED with 4 field errors")
    void createEmptyBody() throws Exception {
        mockMvc.perform(create("{}").header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(4));
        verifyNoInteractions(adminAccountService);
    }

    @Test
    @DisplayName("POST /accounts: bad email, long name or long email -> 400 VALIDATION_FAILED; the password is never echoed")
    void createFieldValidation() throws Exception {
        // The password policy itself is checked in the service (see AdminAccountEndToEndWebTest).
        String auth = bearer(Role.SUPER_ADMIN);
        String[][] cases = {
                {"{\"fullName\":\"Nimal\",\"email\":\"not-an-email\",\"role\":\"ADMIN\",\"temporaryPassword\":\"" + PASSWORD + "\"}", "email"},
                {"{\"fullName\":\"" + "n".repeat(101) + "\",\"email\":\"a@b.lk\",\"role\":\"ADMIN\",\"temporaryPassword\":\"" + PASSWORD + "\"}", "fullName"},
                {"{\"fullName\":\"Nimal\",\"email\":\"" + "a".repeat(250) + "@b.lk\",\"role\":\"ADMIN\",\"temporaryPassword\":\"" + PASSWORD + "\"}", "email"},
        };
        for (String[] c : cases) {
            String response = mockMvc.perform(create(c[0]).header("Authorization", auth))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(c[1]))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(PASSWORD);
        }
        verifyNoInteractions(adminAccountService);
    }

    @Test
    @DisplayName("POST /accounts: role value that does not exist -> 400 MALFORMED_REQUEST")
    void createUnknownRole() throws Exception {
        mockMvc.perform(create(CREATE_BODY.replace("MODERATOR", "ROOT")).header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        verifyNoInteractions(adminAccountService);
    }

    @Test
    @DisplayName("POST /accounts: auth's 409 EMAIL_ALREADY_EXISTS passes through unchanged")
    void createDuplicateEmailPassesThrough() throws Exception {
        when(adminAccountService.createAdminAccount(any(), any(), any()))
                .thenThrow(new ResourceConflictException("EMAIL_ALREADY_EXISTS", "A user with this email already exists"));

        mockMvc.perform(create(CREATE_BODY).header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_EXISTS"));
    }

    // ---------- 400 validation: status change, list, audit ----------

    @Test
    @DisplayName("PATCH status: missing status and short reason -> 400 VALIDATION_FAILED with field errors")
    void statusValidationErrors() throws Exception {
        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"short\"}")
                        .header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
        verifyNoInteractions(adminAccountService);
    }

    @Test
    @DisplayName("PATCH status: unknown status value (e.g. BANNED) -> 400 MALFORMED_REQUEST")
    void unknownStatusValue() throws Exception {
        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BANNED\",\"reason\":\"Repeated policy violations\"}")
                        .header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("PATCH status: auth's 403 CANNOT_MODIFY_SELF and 409 LAST_ACTIVE_SUPER_ADMIN pass through unchanged")
    void authStatusCodesPassThrough() throws Exception {
        when(adminAccountService.changeStatus(any(), any(), any(), any(), any()))
                .thenThrow(new com.gamagerecruiters.marketplace.common.exception.ApiException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "CANNOT_MODIFY_SELF", "Self status modification is not allowed"))
                .thenThrow(new ResourceConflictException("LAST_ACTIVE_SUPER_ADMIN", "Cannot suspend the last active SUPER_ADMIN"));
        String auth = bearer(Role.SUPER_ADMIN);

        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content(STATUS_BODY).header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CANNOT_MODIFY_SELF"));
        mockMvc.perform(patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content(STATUS_BODY).header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LAST_ACTIVE_SUPER_ADMIN"));
    }

    @Test
    @DisplayName("Non-numeric page or size -> 400 INVALID_PARAMETER (not 500) on the list and the audit log")
    void badNumericParameters() throws Exception {
        String auth = bearer(Role.SUPER_ADMIN);
        mockMvc.perform(get(ACCOUNTS_URL).param("page", "abc").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
        mockMvc.perform(get(ACCOUNTS_URL).param("size", "ten").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
        mockMvc.perform(get(AUDIT_URL).param("page", "abc").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
        verifyNoInteractions(adminAccountService, adminAuditLogService);
    }
}
