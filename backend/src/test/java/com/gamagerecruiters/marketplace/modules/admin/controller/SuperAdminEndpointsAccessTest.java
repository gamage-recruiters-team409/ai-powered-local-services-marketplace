package com.gamagerecruiters.marketplace.modules.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminStatusChangeResponse;
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

/** Role access and validation for the Super Admin endpoints, through the real SecurityConfig and access policy. */
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

    private static final String STATUS_URL = "/api/v1/admin/accounts/admin-7/status";
    private static final String AUDIT_URL = "/api/v1/admin/audit-logs";
    private static final String VALID_BODY = "{\"status\":\"SUSPENDED\",\"reason\":\"Repeated policy violations\"}";

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

    private static MockHttpServletRequestBuilder changeStatus(String body) {
        return patch(STATUS_URL).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---------- 401 ----------

    @Test
    @DisplayName("No token -> 401 on both endpoints")
    void unauthenticated() throws Exception {
        mockMvc.perform(changeStatus(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get(AUDIT_URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(adminAccountService, adminAuditLogService);
    }

    // ---------- 403 for every role except SUPER_ADMIN ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER", "PROVIDER"})
    @DisplayName("Every role except SUPER_ADMIN -> 403 ACCESS_DENIED on both endpoints")
    void forbiddenForOtherRoles(Role role) throws Exception {
        String auth = bearer(role);

        mockMvc.perform(changeStatus(VALID_BODY).header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        mockMvc.perform(get(AUDIT_URL).header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        verifyNoInteractions(adminAccountService, adminAuditLogService);
    }

    // ---------- SUPER_ADMIN allowed ----------

    @Test
    @DisplayName("PATCH status as SUPER_ADMIN -> 200 with the change and audit id")
    void changeStatusAllowed() throws Exception {
        when(adminAccountService.changeStatus(eq("user-SUPER_ADMIN"), eq(Role.SUPER_ADMIN), eq("admin-7"),
                eq(UserStatus.SUSPENDED), eq("Repeated policy violations")))
                .thenReturn(new AdminStatusChangeResponse("admin-7", Role.ADMIN, UserStatus.ACTIVE,
                        UserStatus.SUSPENDED, "audit-1"));

        mockMvc.perform(changeStatus(VALID_BODY).header("Authorization", bearer(Role.SUPER_ADMIN)))
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

        mockMvc.perform(get(AUDIT_URL).param("action", "ADMIN_STATUS_CHANGED")
                        .header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.size").value(20));
    }

    // ---------- 400 validation ----------

    @Test
    @DisplayName("PATCH status: missing status and short reason -> 400 VALIDATION_FAILED with field errors")
    void validationErrors() throws Exception {
        mockMvc.perform(changeStatus("{\"reason\":\"short\"}").header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
        verifyNoInteractions(adminAccountService);
    }

    @Test
    @DisplayName("PATCH status: reason over 500 characters -> 400 VALIDATION_FAILED")
    void reasonTooLong() throws Exception {
        String body = "{\"status\":\"SUSPENDED\",\"reason\":\"" + "x".repeat(501) + "\"}";

        mockMvc.perform(changeStatus(body).header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PATCH status: unknown status value (e.g. BANNED) -> 400 MALFORMED_REQUEST")
    void unknownStatusValue() throws Exception {
        mockMvc.perform(changeStatus("{\"status\":\"BANNED\",\"reason\":\"Repeated policy violations\"}")
                        .header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("GET audit logs: non-numeric page -> 400 INVALID_PARAMETER (not 500)")
    void badPageParameter() throws Exception {
        mockMvc.perform(get(AUDIT_URL).param("page", "abc").header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("An auth-module error code (e.g. 409) passes through unchanged in the response")
    void authErrorCodePassesThrough() throws Exception {
        when(adminAccountService.changeStatus(any(), any(), any(), any(), any()))
                .thenThrow(new ResourceConflictException("LAST_ACTIVE_SUPER_ADMIN", "Cannot suspend the last active SUPER_ADMIN"));

        mockMvc.perform(changeStatus(VALID_BODY).header("Authorization", bearer(Role.SUPER_ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LAST_ACTIVE_SUPER_ADMIN"));
    }
}
