
package com.gamagerecruiters.marketplace.modules.payment.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.payment.dto.EarningsSummaryResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.ProviderEarningsSummaryService;

/**
 * Spring MVC security tests for provider earnings retrieval.
 *
 * <p>
 * Loads the application's actual Spring Security configuration
 * and JWT authentication filter while mocking external services.
 * </p>
 *
 * <p>
 * Verifies provider-only access, trusted authenticated identity,
 * response mapping and rejection of unauthorized requests.
 * </p>
 */
@WebMvcTest(ProviderEarningsController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class ProviderEarningsControllerSecurityTest {

    private static final String EARNINGS_URL = "/api/v1/provider/earnings/me";

    private static final String PROVIDER_ID = "user-provider";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProviderEarningsSummaryService summaryService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    /**
     * Rejects requests without an authentication token.
     */
    @Test
    void earningsEndpointReturns401WithoutToken() throws Exception {

        mockMvc.perform(get(EARNINGS_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verifyNoInteractions(summaryService);
    }

    /**
     * Ensures customers and administrators cannot access
     * provider earnings.
     */
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {
            "CUSTOMER",
            "ADMIN"
    })
    void earningsEndpointReturns403ForNonProvider(Role role)
            throws Exception {

        String token = authenticateAs(role);

        mockMvc.perform(
                get(EARNINGS_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        verifyNoInteractions(summaryService);
    }

    /**
     * Verifies that an authenticated provider receives
     * the calculated LKR earnings summary.
     */
    @Test
    void earningsEndpointReturns200ForProvider() throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        when(summaryService.getSummary(PROVIDER_ID, "LKR"))
                .thenReturn(createSummary());

        mockMvc.perform(
                get(EARNINGS_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.pending").value(1500.00))
                .andExpect(jsonPath("$.available").value(4000.00))
                .andExpect(jsonPath("$.held").value(2000.00))
                .andExpect(jsonPath("$.paidOut").value(5000.00));

        verify(summaryService).getSummary(PROVIDER_ID, "LKR");
    }

    /**
     * Confirms that client-supplied provider IDs cannot override
     * the authenticated identity obtained from the JWT.
     */
    @Test
    void earningsEndpointIgnoresSuppliedProviderId()
            throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        when(summaryService.getSummary(PROVIDER_ID, "LKR"))
                .thenReturn(createSummary());

        mockMvc.perform(
                get(EARNINGS_URL)
                        .param("providerId", "another-provider")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(summaryService).getSummary(PROVIDER_ID, "LKR");

        verify(summaryService, never())
                .getSummary("another-provider", "LKR");
    }

    /**
     * Verifies that an empty earnings summary is returned
     * without exposing internal ledger records.
     */
    @Test
    void earningsEndpointReturnsZeroBalances() throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        EarningsSummaryResponse emptySummary = new EarningsSummaryResponse(
                "LKR",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);

        when(summaryService.getSummary(PROVIDER_ID, "LKR"))
                .thenReturn(emptySummary);

        mockMvc.perform(
                get(EARNINGS_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.paidOut").value(0))
                .andExpect(jsonPath("$.eventKey").doesNotExist())
                .andExpect(jsonPath("$.paymentId").doesNotExist())
                .andExpect(jsonPath("$.providerId").doesNotExist());
    }

    /**
     * Creates a representative provider-facing earnings response.
     */
    private EarningsSummaryResponse createSummary() {

        return new EarningsSummaryResponse(
                "LKR",
                new BigDecimal("1500.00"),
                new BigDecimal("4000.00"),
                new BigDecimal("2000.00"),
                new BigDecimal("5000.00"));
    }

    /**
     * Configures a mock JWT token recognized by the
     * application's JWT authentication filter.
     *
     * @param role authenticated user's role
     * @return mock JWT token
     */
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
