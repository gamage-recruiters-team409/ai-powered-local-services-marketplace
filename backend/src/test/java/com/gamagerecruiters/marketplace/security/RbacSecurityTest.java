package com.gamagerecruiters.marketplace.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;

@WebMvcTest(TestRbacController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class RbacSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @Test
    @DisplayName("Unauthenticated request to protected endpoint should return 401 UNAUTHORIZED")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/test/customer-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("CUSTOMER role accessing customer-only endpoint should succeed with 200")
    void shouldAllowCustomerOnCustomerEndpoint() throws Exception {
        when(tokenService.validateToken("valid-customer-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-customer-token")).thenReturn("cust-1");
        when(tokenService.getEmailFromToken("valid-customer-token")).thenReturn("cust@test.local");
        when(tokenService.getRoleFromToken("valid-customer-token")).thenReturn(Role.CUSTOMER);

        mockMvc.perform(get("/api/test/customer-only")
                        .header("Authorization", "Bearer valid-customer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello Customer"));
    }

    @Test
    @DisplayName("CUSTOMER role accessing admin-only endpoint should return 403 ACCESS_DENIED")
    void shouldDenyCustomerOnAdminEndpoint() throws Exception {
        when(tokenService.validateToken("valid-customer-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-customer-token")).thenReturn("cust-1");
        when(tokenService.getEmailFromToken("valid-customer-token")).thenReturn("cust@test.local");
        when(tokenService.getRoleFromToken("valid-customer-token")).thenReturn(Role.CUSTOMER);

        mockMvc.perform(get("/api/test/admin-only")
                        .header("Authorization", "Bearer valid-customer-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("ADMIN role accessing admin-only endpoint should succeed with 200")
    void shouldAllowAdminOnAdminEndpoint() throws Exception {
        when(tokenService.validateToken("valid-admin-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-admin-token")).thenReturn("admin-1");
        when(tokenService.getEmailFromToken("valid-admin-token")).thenReturn("admin@test.local");
        when(tokenService.getRoleFromToken("valid-admin-token")).thenReturn(Role.ADMIN);

        mockMvc.perform(get("/api/test/admin-only")
                        .header("Authorization", "Bearer valid-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello Admin"));
    }

    @Test
    @DisplayName("SUPER_ADMIN role accessing any-admin endpoint should succeed with 200")
    void shouldAllowSuperAdminOnAnyAdminEndpoint() throws Exception {
        when(tokenService.validateToken("valid-super-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-super-token")).thenReturn("super-1");
        when(tokenService.getEmailFromToken("valid-super-token")).thenReturn("super@test.local");
        when(tokenService.getRoleFromToken("valid-super-token")).thenReturn(Role.SUPER_ADMIN);

        mockMvc.perform(get("/api/test/any-admin")
                        .header("Authorization", "Bearer valid-super-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello Admin/SuperAdmin"));
    }

    @Test
    @DisplayName("OwnershipChecker should allow access when currentUserId matches ownerId")
    void shouldAllowOwnershipWhenMatching() throws Exception {
        when(tokenService.validateToken("valid-cust-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-cust-token")).thenReturn("owner-123");
        when(tokenService.getEmailFromToken("valid-cust-token")).thenReturn("owner@test.local");
        when(tokenService.getRoleFromToken("valid-cust-token")).thenReturn(Role.CUSTOMER);

        mockMvc.perform(get("/api/test/ownership/owner-123")
                        .header("Authorization", "Bearer valid-cust-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Ownership verified"));
    }

    @Test
    @DisplayName("OwnershipChecker should deny access with 403 when currentUserId does not match ownerId")
    void shouldDenyOwnershipWhenMismatch() throws Exception {
        when(tokenService.validateToken("valid-cust-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-cust-token")).thenReturn("user-999");
        when(tokenService.getEmailFromToken("valid-cust-token")).thenReturn("user999@test.local");
        when(tokenService.getRoleFromToken("valid-cust-token")).thenReturn(Role.CUSTOMER);

        mockMvc.perform(get("/api/test/ownership/owner-123")
                        .header("Authorization", "Bearer valid-cust-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }
}
