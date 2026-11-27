
package com.gamagerecruiters.marketplace.modules.payment.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
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
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.dto.EarningsSummaryResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.EarningsTransactionResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.EarningsTransactionPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.ItemizedEarningsResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.ItemizedEarningsPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.ProviderEarningsSummaryService;
import com.gamagerecruiters.marketplace.modules.payment.service.ProviderEarningsTransactionService;
import com.gamagerecruiters.marketplace.modules.payment.service.ProviderItemizedEarningsService;

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

    private static final String TRANSACTIONS_URL = "/api/v1/provider/earnings/transactions";

    private static final String PROVIDER_ID = "user-provider";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProviderEarningsSummaryService summaryService;

    @MockitoBean
    private ProviderEarningsTransactionService transactionService;

    @MockitoBean
    private ProviderItemizedEarningsService itemizedService;

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

    @Test
    void transactionsEndpointReturns401WithoutToken() throws Exception {

        mockMvc.perform(get(TRANSACTIONS_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {
            "CUSTOMER",
            "ADMIN",
            "FINANCE_ADMIN"
    })
    void transactionsEndpointReturns403ForNonProvider(Role role)
            throws Exception {

        String token = authenticateAs(role);

        mockMvc.perform(get(TRANSACTIONS_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        verifyNoInteractions(transactionService);
    }

    @Test
    void transactionsEndpointReturnsProviderOwnedHistory()
            throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        EarningsTransactionResponse transaction =
                new EarningsTransactionResponse(
                        "transaction-123",
                        "payment-123",
                        "booking-123",
                        EarningsLedgerEventType.EARNING_PENDING,
                        new BigDecimal("4500.00"),
                        "LKR",
                        5L,
                        Instant.parse("2026-11-26T10:00:00Z"));

        when(transactionService.getTransactions(PROVIDER_ID, 0, 10))
                .thenReturn(new EarningsTransactionPageResponse(
                        List.of(transaction), 0, 10, 1, 1));

        mockMvc.perform(get(TRANSACTIONS_URL)
                        .param("providerId", "another-provider")
                        .param("currency", "USD")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result
                        .MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.content[0].transactionId")
                        .value("transaction-123"))
                .andExpect(jsonPath("$.content[0].paymentId")
                        .value("payment-123"))
                .andExpect(jsonPath("$.content[0].bookingId")
                        .value("booking-123"))
                .andExpect(jsonPath("$.content[0].eventType")
                        .value("EARNING_PENDING"))
                .andExpect(jsonPath("$.content[0].amount").value(4500.00))
                .andExpect(jsonPath("$.content[0].currency").value("LKR"))
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(5))
                .andExpect(jsonPath("$.content[0].createdAt")
                        .value("2026-11-26T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].eventKey").doesNotExist())
                .andExpect(jsonPath("$.content[0].providerId").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        verify(transactionService).getTransactions(PROVIDER_ID, 0, 10);
        verifyNoInteractions(summaryService);
    }

    @Test
    void transactionsEndpointSupportsCustomPagination()
            throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        when(transactionService.getTransactions(PROVIDER_ID, 2, 5))
                .thenReturn(new EarningsTransactionPageResponse(
                        List.of(), 2, 5, 0, 0));

        mockMvc.perform(get(TRANSACTIONS_URL)
                        .param("page", "2")
                        .param("size", "5")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));

        verify(transactionService).getTransactions(PROVIDER_ID, 2, 5);
    }

    @Test
    void transactionsEndpointReturns400ForNegativePage()
            throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        when(transactionService.getTransactions(PROVIDER_ID, -1, 10))
                .thenThrow(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_PAGINATION",
                        "Invalid pagination"));

        mockMvc.perform(get(TRANSACTIONS_URL)
                        .param("page", "-1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION"));
    }

    @Test
    void transactionsEndpointReturns400ForExcessivePageSize()
            throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        when(transactionService.getTransactions(PROVIDER_ID, 0, 101))
                .thenThrow(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_PAGINATION",
                        "Invalid pagination"));

        mockMvc.perform(get(TRANSACTIONS_URL)
                        .param("size", "101")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION"));
    }

    @Test
    void transactionsEndpointReturns500ForInconsistentLedgerData()
            throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        when(transactionService.getTransactions(PROVIDER_ID, 0, 10))
                .thenThrow(new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "EARNINGS_DATA_INCONSISTENT",
                        "Earnings transaction history is temporarily unavailable"));

        mockMvc.perform(get(TRANSACTIONS_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error")
                        .value("EARNINGS_DATA_INCONSISTENT"));
    }

    private static final String ITEMIZED_URL =
            "/api/v1/provider/earnings/itemized";

    @Test
    void itemizedEndpointReturns401WithoutToken() throws Exception {

        mockMvc.perform(get(ITEMIZED_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verifyNoInteractions(itemizedService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {
            "CUSTOMER",
            "ADMIN",
            "FINANCE_ADMIN"
    })
    void itemizedEndpointReturns403ForNonProvider(Role role)
            throws Exception {

        String token = authenticateAs(role);

        mockMvc.perform(get(ITEMIZED_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        verifyNoInteractions(itemizedService);
    }

    @Test
    void itemizedEndpointReturnsProviderOwnedFinancialBreakdown()
            throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        ItemizedEarningsResponse earning =
                new ItemizedEarningsResponse(
                        "payment-123",
                        "booking-123",
                        new BigDecimal("10000.00"),
                        new BigDecimal("0.10"),
                        new BigDecimal("1000.00"),
                        new BigDecimal("2000.00"),
                        new BigDecimal("1800.00"),
                        new BigDecimal("7200.00"),
                        "LKR",
                        Instant.parse("2026-11-26T10:00:00Z"));

        when(itemizedService.getItemizedEarnings(PROVIDER_ID, 0, 10))
                .thenReturn(new ItemizedEarningsPageResponse(
                        List.of(earning), 0, 10, 1, 1));

        mockMvc.perform(get(ITEMIZED_URL)
                        .param("providerId", "another-provider")
                        .param("currency", "USD")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result
                        .MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.content[0].paymentId")
                        .value("payment-123"))
                .andExpect(jsonPath("$.content[0].bookingId")
                        .value("booking-123"))
                .andExpect(jsonPath("$.content[0].grossAmount")
                        .value(10000.00))
                .andExpect(jsonPath("$.content[0].commissionRate")
                        .value(0.10))
                .andExpect(jsonPath("$.content[0].commissionAmount")
                        .value(1000.00))
                .andExpect(jsonPath("$.content[0].refundedAmount")
                        .value(2000.00))
                .andExpect(jsonPath("$.content[0].refundAdjustmentAmount")
                        .value(1800.00))
                .andExpect(jsonPath("$.content[0].netEarnings")
                        .value(7200.00))
                .andExpect(jsonPath("$.content[0].currency").value("LKR"))
                .andExpect(jsonPath("$.content[0].recognizedAt")
                        .value("2026-11-26T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].providerId").doesNotExist())
                .andExpect(jsonPath("$.content[0].customerId").doesNotExist())
                .andExpect(jsonPath("$.content[0].eventKey").doesNotExist())
                .andExpect(jsonPath("$.content[0].gatewayRef").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        verify(itemizedService).getItemizedEarnings(PROVIDER_ID, 0, 10);
        verifyNoInteractions(summaryService, transactionService);
    }

    @Test
    void itemizedEndpointSupportsCustomPagination() throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        when(itemizedService.getItemizedEarnings(PROVIDER_ID, 2, 5))
                .thenReturn(new ItemizedEarningsPageResponse(
                        List.of(), 2, 5, 0, 0));

        mockMvc.perform(get(ITEMIZED_URL)
                        .param("page", "2")
                        .param("size", "5")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));

        verify(itemizedService).getItemizedEarnings(PROVIDER_ID, 2, 5);
    }

    @Test
    void itemizedEndpointReturns400ForNegativePage() throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        when(itemizedService.getItemizedEarnings(PROVIDER_ID, -1, 10))
                .thenThrow(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_PAGINATION",
                        "Invalid pagination"));

        mockMvc.perform(get(ITEMIZED_URL)
                        .param("page", "-1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION"));
    }

    @Test
    void itemizedEndpointReturns400ForExcessivePageSize() throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        when(itemizedService.getItemizedEarnings(PROVIDER_ID, 0, 101))
                .thenThrow(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_PAGINATION",
                        "Invalid pagination"));

        mockMvc.perform(get(ITEMIZED_URL)
                        .param("size", "101")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION"));
    }

    @Test
    void itemizedEndpointReturns500ForInconsistentFinancialData()
            throws Exception {

        String token = authenticateAs(Role.PROVIDER);

        when(itemizedService.getItemizedEarnings(PROVIDER_ID, 0, 10))
                .thenThrow(new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "EARNINGS_DATA_INCONSISTENT",
                        "Itemized earnings are temporarily unavailable"));

        mockMvc.perform(get(ITEMIZED_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error")
                        .value("EARNINGS_DATA_INCONSISTENT"));
    }

}
