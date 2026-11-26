package com.gamagerecruiters.marketplace.modules.payment.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
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
import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutStatus;
import com.gamagerecruiters.marketplace.modules.payment.dto.PayoutSummaryResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.AdminPayoutQueryService;
import com.gamagerecruiters.marketplace.modules.payment.service.PayoutService;

/**
 * Verifies administrative payout HTTP contracts and authorization
 * through the actual application security configuration.
 *
 * Financial services are mocked, but the admin access policy
 * and JWT authentication filter remain active.
 */
@WebMvcTest(AdminPayoutController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class AdminPayoutControllerSecurityTest {

    private static final String PAYOUTS = "/api/v1/admin/payouts";
    private static final String SUMMARY = PAYOUTS + "/summary";
    private static final String PAYOUT_ID = "payout-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private PayoutService payoutService;

    @MockitoBean
    private AdminPayoutQueryService adminPayoutQueryService;

    /**
     * Both read endpoints require authentication.
     */
    @Test
    void readEndpointsRejectUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get(PAYOUTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        mockMvc.perform(get(SUMMARY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verifyNoInteractions(payoutService, adminPayoutQueryService);
    }

    /**
     * Only finance administrators and read-only super administrators
     * may access administrative payout queries.
     */
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {
            "FINANCE_ADMIN", "SUPER_ADMIN"
    })
    void authorizedRolesCanReadPayoutsAndSummary(Role role)
            throws Exception {

        when(adminPayoutQueryService.getPayouts(
                0, 10, null, null, null, null))
                .thenReturn(Page.empty(PageRequest.of(0, 10)));

        when(adminPayoutQueryService.getSummary())
                .thenReturn(new PayoutSummaryResponse(6, 12, 148, 3));

        String auth = bearer(role);

        mockMvc.perform(get(PAYOUTS)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get(SUMMARY)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(6))
                .andExpect(jsonPath("$.processing").value(12))
                .andExpect(jsonPath("$.paid").value(148))
                .andExpect(jsonPath("$.failed").value(3));

        verify(adminPayoutQueryService).getPayouts(
                0, 10, null, null, null, null);

        verify(adminPayoutQueryService).getSummary();
        verifyNoInteractions(payoutService);
    }

    /**
     * Unrelated admin areas and marketplace users cannot read
     * administrative financial information.
     */
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {
            "ADMIN", "SUPPORT_ADMIN", "MODERATOR",
            "CUSTOMER", "PROVIDER"
    })
    void unauthorizedRolesCannotReadPayouts(Role role)
            throws Exception {

        String auth = bearer(role);

        mockMvc.perform(get(PAYOUTS)
                        .header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(get(SUMMARY)
                        .header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        verifyNoInteractions(payoutService, adminPayoutQueryService);
    }

    /**
     * Verifies actual HTTP binding of pagination, identifier,
     * enum status, and ISO-8601 timestamp parameters.
     */
    @Test
    void bindsAllOptionalQueryParameters() throws Exception {
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-01T00:00:00Z");

        when(adminPayoutQueryService.getPayouts(
                1, 6, "provider-123",
                PayoutStatus.PENDING, from, to))
                .thenReturn(Page.empty(PageRequest.of(1, 6)));

        mockMvc.perform(get(PAYOUTS)
                        .param("page", "1")
                        .param("size", "6")
                        .param("search", "provider-123")
                        .param("status", "PENDING")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .header("Authorization", bearer(Role.FINANCE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(6));

        verify(adminPayoutQueryService).getPayouts(
                1, 6, "provider-123",
                PayoutStatus.PENDING, from, to);
    }

    /**
     * Malformed HTTP parameters must be rejected before
     * reaching the financial query service.
     */
    @ParameterizedTest
    @CsvSource({
            "page,abc",
            "size,abc",
            "status,UNKNOWN",
            "from,invalid-date",
            "to,invalid-date"
    })
    void malformedParametersReturnBadRequest(
            String parameter, String value) throws Exception {

        mockMvc.perform(get(PAYOUTS)
                        .param(parameter, value)
                        .header("Authorization", bearer(Role.FINANCE_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verifyNoInteractions(payoutService, adminPayoutQueryService);
    }

    /**
     * Verifies that service-level validation errors are mapped
     * to the established HTTP 400 response contract.
     */
    @Test
    void serviceValidationErrorReturnsBadRequest() throws Exception {
        when(adminPayoutQueryService.getPayouts(
                -1, 10, null, null, null, null))
                .thenThrow(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED",
                        "Page must not be negative"));

        mockMvc.perform(get(PAYOUTS)
                        .param("page", "-1")
                        .header("Authorization", bearer(Role.FINANCE_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(adminPayoutQueryService).getPayouts(
                -1, 10, null, null, null, null);

        verifyNoInteractions(payoutService);
    }

    /**
     * Protects the existing mutation endpoints from unauthorized
     * roles, including the read-only super administrator.
     */
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {
            "SUPER_ADMIN", "ADMIN", "SUPPORT_ADMIN",
            "MODERATOR", "CUSTOMER", "PROVIDER"
    })
    void unauthorizedRolesCannotMutatePayouts(Role role)
            throws Exception {

        String auth = bearer(role);
        String payoutUrl = PAYOUTS + "/" + PAYOUT_ID;

        mockMvc.perform(post(payoutUrl + "/process")
                        .header("Authorization", auth))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(payoutUrl + "/paid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferReference\":\"BANK-REF-1\"}")
                        .header("Authorization", auth))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(payoutUrl + "/failed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Bank rejected transfer\"}")
                        .header("Authorization", auth))
                .andExpect(status().isForbidden());

        verifyNoInteractions(payoutService, adminPayoutQueryService);
    }

    /**
     * Registers a representative active user and mock token
     * recognized by the application's JWT authentication filter.
     */
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
}
