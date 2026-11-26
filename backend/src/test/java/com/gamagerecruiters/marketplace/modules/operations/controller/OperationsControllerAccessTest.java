package com.gamagerecruiters.marketplace.modules.operations.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.operations.service.OperationsCsvExporter;
import com.gamagerecruiters.marketplace.modules.operations.service.OperationsDashboardService;
import com.gamagerecruiters.marketplace.modules.operations.service.LateCasesService;
import com.gamagerecruiters.marketplace.modules.operations.service.OpsCaseActionService;
import com.gamagerecruiters.marketplace.modules.operations.service.OverdueBookingsService;
import com.gamagerecruiters.marketplace.modules.operations.service.RepeatCancellationsService;

/** Role access for the operations endpoints, through the real SecurityConfig and admin access policy (WP09). */
@WebMvcTest(OperationsController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class OperationsControllerAccessTest {

    private static final String BASE = "/api/v1/admin/operations";
    private static final String ACTION_BODY = "{\"action\":\"CONTACT_PROVIDER\",\"note\":\"Contacted the provider.\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;
    @MockitoBean
    private UserInternalService userInternalService;
    @MockitoBean
    private OverdueBookingsService overdueService;
    @MockitoBean
    private LateCasesService lateCasesService;
    @MockitoBean
    private RepeatCancellationsService repeatService;
    @MockitoBean
    private OperationsDashboardService dashboardService;
    @MockitoBean
    private OperationsCsvExporter csvExporter;
    @MockitoBean
    private OpsCaseActionService actionService;

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

    // ---------- 401 without a token ----------

    @Test
    @DisplayName("every operations endpoint returns 401 without a token")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(BASE + "/overdue-bookings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/late-cases")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/repeat-cancellations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE + "/overdue-bookings/b1/actions")
                .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY)).andExpect(status().isUnauthorized());
    }

    // ---------- reads ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "ADMIN", "SUPER_ADMIN"})
    @DisplayName("SUPPORT_ADMIN, ADMIN and SUPER_ADMIN can read")
    void readsAllowed(Role role) throws Exception {
        mockMvc.perform(get(BASE + "/dashboard").header("Authorization", bearer(role)))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/overdue-bookings").header("Authorization", bearer(role)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER", "FINANCE_ADMIN", "MODERATOR"})
    @DisplayName("other roles cannot read (403)")
    void readsForbidden(Role role) throws Exception {
        mockMvc.perform(get(BASE + "/dashboard").header("Authorization", bearer(role)))
                .andExpect(status().isForbidden());
    }

    // ---------- writes ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPPORT_ADMIN", "ADMIN"})
    @DisplayName("SUPPORT_ADMIN and ADMIN can post a case action")
    void writesAllowed(Role role) throws Exception {
        mockMvc.perform(post(BASE + "/overdue-bookings/b1/actions").header("Authorization", bearer(role))
                .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("SUPER_ADMIN is read-only: posting a case action is 403")
    void superAdminCannotWrite() throws Exception {
        mockMvc.perform(post(BASE + "/repeat-cancellations/u1/actions").header("Authorization", bearer(Role.SUPER_ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER", "MODERATOR"})
    @DisplayName("other roles cannot write (403)")
    void writesForbidden(Role role) throws Exception {
        mockMvc.perform(post(BASE + "/overdue-bookings/b1/actions").header("Authorization", bearer(role))
                .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY)).andExpect(status().isForbidden());
    }
}
