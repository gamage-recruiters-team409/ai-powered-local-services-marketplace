package com.gamagerecruiters.marketplace.modules.admin.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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

@WebMvcTest(AdminAccessController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class AdminAccessControllerTest {

    private static final String ACCESS_URL = "/api/v1/admin/access";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @Test
    @DisplayName("GET /admin/access without a token returns 401 UNAUTHORIZED")
    void returns401WithoutToken() throws Exception {
        mockMvc.perform(get(ACCESS_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @DisplayName("GET /admin/access as a non-admin returns 403 ACCESS_DENIED")
    void returns403ForNonAdmins(Role role) throws Exception {
        String token = authenticateAs(role);

        mockMvc.perform(get(ACCESS_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("GET /admin/access as SUPER_ADMIN returns edit SUPER_ADMIN and view the other four")
    void returnsSuperAdminAccess() throws Exception {
        String token = authenticateAs(Role.SUPER_ADMIN);

        mockMvc.perform(get(ACCESS_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.canEdit.length()").value(1))
                .andExpect(jsonPath("$.canEdit[0]").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.canView.length()").value(4))
                .andExpect(jsonPath("$.canView[0]").value("ADMIN"))
                .andExpect(jsonPath("$.canView[1]").value("SUPPORT_ADMIN"))
                .andExpect(jsonPath("$.canView[2]").value("MODERATOR"))
                .andExpect(jsonPath("$.canView[3]").value("FINANCE_ADMIN"))
                .andExpect(jsonPath("$.canAccessSuperAdmin").value(true));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("GET /admin/access as a non-super admin returns own area editable, three others read-only")
    void returnsStaffAdminAccess(Role role) throws Exception {
        String token = authenticateAs(role);

        mockMvc.perform(get(ACCESS_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(role.name()))
                .andExpect(jsonPath("$.canEdit.length()").value(1))
                .andExpect(jsonPath("$.canEdit[0]").value(role.name()))
                .andExpect(jsonPath("$.canView.length()").value(3))
                .andExpect(jsonPath("$.canAccessSuperAdmin").value(false));
    }

    @Test
    @DisplayName("GET /admin/access ignores a role request parameter; the role comes only from the token")
    void ignoresRoleRequestParameter() throws Exception {
        String token = authenticateAs(Role.MODERATOR);

        mockMvc.perform(get(ACCESS_URL).param("role", "SUPER_ADMIN").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MODERATOR"))
                .andExpect(jsonPath("$.canEdit[0]").value("MODERATOR"))
                .andExpect(jsonPath("$.canAccessSuperAdmin").value(false));
    }

    private String authenticateAs(Role role) {
        String token = "test-token-" + role.name().toLowerCase();
        String userId = "user-" + role.name().toLowerCase();
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(userId);
        when(tokenService.getRoleFromToken(token)).thenReturn(role);

        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(userId)).thenReturn(user);
        return token;
    }
}
