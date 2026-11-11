package com.gamagerecruiters.marketplace.modules.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private UserInternalService userInternalService;

    private JwtAuthenticationEntryPoint entryPoint;
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        entryPoint = new JwtAuthenticationEntryPoint();
        jwtAuthenticationFilter = new JwtAuthenticationFilter(tokenService, userInternalService, entryPoint);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Valid token with active user in DB should authenticate and proceed down filter chain")
    void shouldAuthenticateWhenTokenIsValidAndUserIsActive() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/protected");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("valid-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-token")).thenReturn("user-1");
        when(tokenService.getRoleFromToken("valid-token")).thenReturn(Role.CUSTOMER);

        User activeUser = new User();
        activeUser.setId("user-1");
        activeUser.setEmail("active@marketplace.local");
        activeUser.setRole(Role.CUSTOMER);
        activeUser.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById("user-1")).thenReturn(activeUser);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isInstanceOf(UserPrincipal.class);
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        assertThat(principal.getUserId()).isEqualTo("user-1");
        assertThat(principal.getEmail()).isEqualTo("active@marketplace.local");
        assertThat(principal.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Missing Authorization header should pass through filter chain without authenticating")
    void shouldAllowRequestWhenAuthorizationHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Malformed or invalid token should return 401 with standard error body and halt filter chain")
    void shouldRejectWhenTokenIsMalformedOrInvalid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/protected");
        request.addHeader("Authorization", "Bearer invalid-garbage-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("invalid-garbage-token")).thenReturn(false);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("\"status\":401");
        assertThat(response.getContentAsString()).contains("\"error\":\"UNAUTHORIZED\"");
        assertThat(response.getContentAsString()).contains("Authentication token is invalid or expired");
    }

    @Test
    @DisplayName("Expired token should return 401 with standard error body and halt filter chain")
    void shouldRejectWhenTokenIsExpired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/protected");
        request.addHeader("Authorization", "Bearer expired-jwt-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("expired-jwt-token")).thenReturn(false);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("\"status\":401");
        assertThat(response.getContentAsString()).contains("\"error\":\"UNAUTHORIZED\"");
        assertThat(response.getContentAsString()).contains("Authentication token is invalid or expired");
    }

    @Test
    @DisplayName("User suspended after token issuance should return 401 and halt filter chain")
    void shouldRejectWhenUserIsSuspendedAfterTokenIssued() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/protected");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("user-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("user-token")).thenReturn("u-suspended");
        when(tokenService.getRoleFromToken("user-token")).thenReturn(Role.CUSTOMER);

        User suspendedUser = new User();
        suspendedUser.setId("u-suspended");
        suspendedUser.setEmail("suspended@marketplace.local");
        suspendedUser.setRole(Role.CUSTOMER);
        suspendedUser.setStatus(UserStatus.SUSPENDED);
        when(userInternalService.getById("u-suspended")).thenReturn(suspendedUser);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"status\":401");
        assertThat(response.getContentAsString()).contains("User account is inactive or does not exist");
    }

    @Test
    @DisplayName("User deactivated after token issuance should return 401 and halt filter chain")
    void shouldRejectWhenUserIsDeactivatedAfterTokenIssued() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/protected");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("user-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("user-token")).thenReturn("u-deactivated");
        when(tokenService.getRoleFromToken("user-token")).thenReturn(Role.CUSTOMER);

        User deactivatedUser = new User();
        deactivatedUser.setId("u-deactivated");
        deactivatedUser.setEmail("deactivated@marketplace.local");
        deactivatedUser.setRole(Role.CUSTOMER);
        deactivatedUser.setStatus(UserStatus.DEACTIVATED);
        when(userInternalService.getById("u-deactivated")).thenReturn(deactivatedUser);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"status\":401");
        assertThat(response.getContentAsString()).contains("User account is inactive or does not exist");
    }

    @Test
    @DisplayName("User deleted from DB after token issuance should return 401 and halt filter chain")
    void shouldRejectWhenUserIsDeletedAfterTokenIssued() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/protected");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("user-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("user-token")).thenReturn("u-deleted");
        when(tokenService.getRoleFromToken("user-token")).thenReturn(Role.CUSTOMER);

        when(userInternalService.getById("u-deleted")).thenThrow(new ResourceNotFoundException("User not found"));

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"status\":401");
        assertThat(response.getContentAsString()).contains("User account is inactive or does not exist");
    }

    @Test
    @DisplayName("Role in DB differing from token claim should return 401, granting no stale rights")
    void shouldRejectWhenDatabaseRoleDiffersFromTokenClaim() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/protected");
        request.addHeader("Authorization", "Bearer stale-admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        // Token claims ADMIN
        when(tokenService.validateToken("stale-admin-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("stale-admin-token")).thenReturn("u-demoted");
        when(tokenService.getRoleFromToken("stale-admin-token")).thenReturn(Role.ADMIN);

        // Database has CUSTOMER
        User demotedUser = new User();
        demotedUser.setId("u-demoted");
        demotedUser.setEmail("demoted@marketplace.local");
        demotedUser.setRole(Role.CUSTOMER);
        demotedUser.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById("u-demoted")).thenReturn(demotedUser);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"status\":401");
        assertThat(response.getContentAsString()).contains("Token permissions have been superseded by account changes");
    }

    @Test
    @DisplayName("User with mustChangePassword=true accessing standard endpoint gets 403 PASSWORD_CHANGE_REQUIRED")
    void shouldRejectNormalEndpointWith403PasswordChangeRequiredWhenUserMustChangePassword() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customers/me");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("valid-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-token")).thenReturn("u-flagged");
        when(tokenService.getRoleFromToken("valid-token")).thenReturn(Role.ADMIN);

        User flaggedUser = new User();
        flaggedUser.setId("u-flagged");
        flaggedUser.setEmail("flagged@marketplace.local");
        flaggedUser.setRole(Role.ADMIN);
        flaggedUser.setStatus(UserStatus.ACTIVE);
        flaggedUser.setMustChangePassword(true);
        when(userInternalService.getById("u-flagged")).thenReturn(flaggedUser);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"status\":403");
        assertThat(response.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
    }

    @Test
    @DisplayName("User with mustChangePassword=true calling POST /api/v1/auth/change-password is allowed to proceed")
    void shouldAllowPasswordChangePostWhenUserMustChangePassword() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/change-password");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("valid-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-token")).thenReturn("u-flagged");
        when(tokenService.getRoleFromToken("valid-token")).thenReturn(Role.ADMIN);

        User flaggedUser = new User();
        flaggedUser.setId("u-flagged");
        flaggedUser.setEmail("flagged@marketplace.local");
        flaggedUser.setRole(Role.ADMIN);
        flaggedUser.setStatus(UserStatus.ACTIVE);
        flaggedUser.setMustChangePassword(true);
        when(userInternalService.getById("u-flagged")).thenReturn(flaggedUser);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("User with mustChangePassword=true calling POST /api/v1/auth/logout is allowed to proceed")
    void shouldAllowLogoutPostWhenUserMustChangePassword() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("valid-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-token")).thenReturn("u-flagged");
        when(tokenService.getRoleFromToken("valid-token")).thenReturn(Role.ADMIN);

        User flaggedUser = new User();
        flaggedUser.setId("u-flagged");
        flaggedUser.setEmail("flagged@marketplace.local");
        flaggedUser.setRole(Role.ADMIN);
        flaggedUser.setStatus(UserStatus.ACTIVE);
        flaggedUser.setMustChangePassword(true);
        when(userInternalService.getById("u-flagged")).thenReturn(flaggedUser);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("User with mustChangePassword=true calling POST /api/v1/auth/refresh is allowed to proceed")
    void shouldAllowRefreshPostWhenUserMustChangePassword() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("valid-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-token")).thenReturn("u-flagged");
        when(tokenService.getRoleFromToken("valid-token")).thenReturn(Role.ADMIN);

        User flaggedUser = new User();
        flaggedUser.setId("u-flagged");
        flaggedUser.setEmail("flagged@marketplace.local");
        flaggedUser.setRole(Role.ADMIN);
        flaggedUser.setStatus(UserStatus.ACTIVE);
        flaggedUser.setMustChangePassword(true);
        when(userInternalService.getById("u-flagged")).thenReturn(flaggedUser);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET to /api/v1/auth/change-password is NOT allow-listed and returns 403 PASSWORD_CHANGE_REQUIRED")
    void shouldRejectChangePasswordGetWith403PasswordChangeRequired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/change-password");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("valid-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-token")).thenReturn("u-flagged");
        when(tokenService.getRoleFromToken("valid-token")).thenReturn(Role.ADMIN);

        User flaggedUser = new User();
        flaggedUser.setId("u-flagged");
        flaggedUser.setEmail("flagged@marketplace.local");
        flaggedUser.setRole(Role.ADMIN);
        flaggedUser.setStatus(UserStatus.ACTIVE);
        flaggedUser.setMustChangePassword(true);
        when(userInternalService.getById("u-flagged")).thenReturn(flaggedUser);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
    }

    @Test
    @DisplayName("Similar-looking path /api/v1/auth/change-password-x returns 403 PASSWORD_CHANGE_REQUIRED")
    void shouldRejectSimilarLookingPathWith403PasswordChangeRequired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/change-password-x");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = spy(new MockFilterChain());

        when(tokenService.validateToken("valid-token")).thenReturn(true);
        when(tokenService.getUserIdFromToken("valid-token")).thenReturn("u-flagged");
        when(tokenService.getRoleFromToken("valid-token")).thenReturn(Role.ADMIN);

        User flaggedUser = new User();
        flaggedUser.setId("u-flagged");
        flaggedUser.setEmail("flagged@marketplace.local");
        flaggedUser.setRole(Role.ADMIN);
        flaggedUser.setStatus(UserStatus.ACTIVE);
        flaggedUser.setMustChangePassword(true);
        when(userInternalService.getById("u-flagged")).thenReturn(flaggedUser);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
    }
}
