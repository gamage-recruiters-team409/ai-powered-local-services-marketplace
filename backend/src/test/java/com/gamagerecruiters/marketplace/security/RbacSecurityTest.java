package com.gamagerecruiters.marketplace.security;

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
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

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

    @MockitoBean
    private UserInternalService userInternalService;

    @Test
    @DisplayName("Unauthenticated request to protected endpoint should return 401 UNAUTHORIZED")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/test/customer-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("CUSTOMER role accessing customer-only endpoint should succeed with 200")
    void shouldAllowCustomerOnCustomerEndpoint() throws Exception {
        when(tokenService.validateToken("valid-customer-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-customer-token")).thenReturn("cust-1");
        when(tokenService.getRoleFromToken("valid-customer-token")).thenReturn(Role.CUSTOMER);

        User activeCustomer = new User();
        activeCustomer.setId("cust-1");
        activeCustomer.setEmail("cust@test.local");
        activeCustomer.setRole(Role.CUSTOMER);
        activeCustomer.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById("cust-1")).thenReturn(activeCustomer);

        mockMvc.perform(get("/api/v1/test/customer-only")
                        .header("Authorization", "Bearer valid-customer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello Customer"));
    }

    @Test
    @DisplayName("CUSTOMER role accessing admin-only endpoint should return 403 ACCESS_DENIED")
    void shouldDenyCustomerOnAdminEndpoint() throws Exception {
        when(tokenService.validateToken("valid-customer-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-customer-token")).thenReturn("cust-1");
        when(tokenService.getRoleFromToken("valid-customer-token")).thenReturn(Role.CUSTOMER);

        User activeCustomer = new User();
        activeCustomer.setId("cust-1");
        activeCustomer.setEmail("cust@test.local");
        activeCustomer.setRole(Role.CUSTOMER);
        activeCustomer.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById("cust-1")).thenReturn(activeCustomer);

        mockMvc.perform(get("/api/v1/test/admin-only")
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
        when(tokenService.getRoleFromToken("valid-admin-token")).thenReturn(Role.ADMIN);

        User activeAdmin = new User();
        activeAdmin.setId("admin-1");
        activeAdmin.setEmail("admin@test.local");
        activeAdmin.setRole(Role.ADMIN);
        activeAdmin.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById("admin-1")).thenReturn(activeAdmin);

        mockMvc.perform(get("/api/v1/test/admin-only")
                        .header("Authorization", "Bearer valid-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello Admin"));
    }

    @Test
    @DisplayName("SUPER_ADMIN role accessing any-admin endpoint should succeed with 200")
    void shouldAllowSuperAdminOnAnyAdminEndpoint() throws Exception {
        when(tokenService.validateToken("valid-super-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-super-token")).thenReturn("super-1");
        when(tokenService.getRoleFromToken("valid-super-token")).thenReturn(Role.SUPER_ADMIN);

        User activeSuper = new User();
        activeSuper.setId("super-1");
        activeSuper.setEmail("super@test.local");
        activeSuper.setRole(Role.SUPER_ADMIN);
        activeSuper.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById("super-1")).thenReturn(activeSuper);

        mockMvc.perform(get("/api/v1/test/any-admin")
                        .header("Authorization", "Bearer valid-super-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello Admin/SuperAdmin"));
    }

    @Test
    @DisplayName("OwnershipChecker should allow access when currentUserId matches ownerId")
    void shouldAllowOwnershipWhenMatching() throws Exception {
        when(tokenService.validateToken("valid-cust-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-cust-token")).thenReturn("owner-123");
        when(tokenService.getRoleFromToken("valid-cust-token")).thenReturn(Role.CUSTOMER);

        User activeCustomer = new User();
        activeCustomer.setId("owner-123");
        activeCustomer.setEmail("owner@test.local");
        activeCustomer.setRole(Role.CUSTOMER);
        activeCustomer.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById("owner-123")).thenReturn(activeCustomer);

        mockMvc.perform(get("/api/v1/test/ownership/owner-123")
                        .header("Authorization", "Bearer valid-cust-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Ownership verified"));
    }

    @Test
    @DisplayName("OwnershipChecker should deny access with 403 when currentUserId does not match ownerId")
    void shouldDenyOwnershipWhenMismatch() throws Exception {
        when(tokenService.validateToken("valid-cust-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-cust-token")).thenReturn("user-999");
        when(tokenService.getRoleFromToken("valid-cust-token")).thenReturn(Role.CUSTOMER);

        User activeCustomer = new User();
        activeCustomer.setId("user-999");
        activeCustomer.setEmail("user999@test.local");
        activeCustomer.setRole(Role.CUSTOMER);
        activeCustomer.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById("user-999")).thenReturn(activeCustomer);

        mockMvc.perform(get("/api/v1/test/ownership/owner-123")
                        .header("Authorization", "Bearer valid-cust-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Issue 2: User suspended after token issuance should be rejected with 401")
    void shouldRejectSuspendedUserWith401() throws Exception {
        when(tokenService.validateToken("user-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("user-token")).thenReturn("u-suspended");
        when(tokenService.getRoleFromToken("user-token")).thenReturn(Role.CUSTOMER);

        User suspendedUser = new User();
        suspendedUser.setId("u-suspended");
        suspendedUser.setStatus(UserStatus.SUSPENDED);
        suspendedUser.setRole(Role.CUSTOMER);
        when(userInternalService.getById("u-suspended")).thenReturn(suspendedUser);

        mockMvc.perform(get("/api/v1/test/customer-only")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Issue 2: User deactivated after token issuance should be rejected with 401")
    void shouldRejectDeactivatedUserWith401() throws Exception {
        when(tokenService.validateToken("user-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("user-token")).thenReturn("u-deactivated");
        when(tokenService.getRoleFromToken("user-token")).thenReturn(Role.CUSTOMER);

        User deactivatedUser = new User();
        deactivatedUser.setId("u-deactivated");
        deactivatedUser.setStatus(UserStatus.DEACTIVATED);
        deactivatedUser.setRole(Role.CUSTOMER);
        when(userInternalService.getById("u-deactivated")).thenReturn(deactivatedUser);

        mockMvc.perform(get("/api/v1/test/customer-only")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Issue 2: User deleted after token issuance should be rejected with 401")
    void shouldRejectDeletedUserWith401() throws Exception {
        when(tokenService.validateToken("user-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("user-token")).thenReturn("u-deleted");
        when(tokenService.getRoleFromToken("user-token")).thenReturn(Role.CUSTOMER);

        when(userInternalService.getById("u-deleted")).thenThrow(new ResourceNotFoundException("User not found"));

        mockMvc.perform(get("/api/v1/test/customer-only")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Issue 2: Role changed after token issuance (ADMIN -> CUSTOMER) should reject token with 401")
    void shouldRejectTokenWhenDbRoleMismatchesTokenClaim() throws Exception {
        // Token has ADMIN claim
        when(tokenService.validateToken("stale-admin-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("stale-admin-token")).thenReturn("u-demoted");
        when(tokenService.getRoleFromToken("stale-admin-token")).thenReturn(Role.ADMIN);

        // In DB, user was demoted to CUSTOMER
        User demotedUser = new User();
        demotedUser.setId("u-demoted");
        demotedUser.setStatus(UserStatus.ACTIVE);
        demotedUser.setRole(Role.CUSTOMER);
        when(userInternalService.getById("u-demoted")).thenReturn(demotedUser);

        mockMvc.perform(get("/api/v1/test/admin-only")
                        .header("Authorization", "Bearer stale-admin-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }
}
