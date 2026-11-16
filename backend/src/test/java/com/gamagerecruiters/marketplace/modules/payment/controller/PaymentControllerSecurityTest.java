
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentRetrievalService;

/**
 * Spring MVC security tests for customer payment retrieval endpoints.
 *
 * <p>
 * Loads the actual Spring Security configuration and JWT filter while
 * mocking external service dependencies. Tests verify HTTP status codes,
 * role restrictions, authenticated identity handling, and response mapping.
 * </p>
 *
 * <p>
 * These tests do not connect to MongoDB or verify persistence behavior.
 * </p>
 */
@WebMvcTest(PaymentController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class PaymentControllerSecurityTest {

    private static final String PAYMENT_URL = "/api/v1/payments/payment-123";
    private static final String BOOKING_URL = "/api/v1/payments/booking/booking-456";

    private static final String PAYMENT_ID = "payment-123";
    private static final String BOOKING_ID = "booking-456";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentRetrievalService paymentRetrievalService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    /**
     * Creates a payment record for HTTP response mapping tests.
     */
    private Payment createPayment() {
        Payment payment = new Payment();

        payment.setId(PAYMENT_ID);
        payment.setBookingId(BOOKING_ID);
        payment.setAmount(new BigDecimal("2500.00"));
        payment.setCurrency("LKR");
        payment.setStatus(PaymentStatus.INITIATED);
        payment.setRefundedAmount(new BigDecimal("0.00"));
        payment.setCreatedAt(
                Instant.parse("2026-11-11T17:00:00Z"));

        // Internal fields must not be exposed in customer responses.
        payment.setGatewayRef("internal-gateway-reference");
        payment.setCommissionRate(new BigDecimal("0.10"));
        payment.setCommissionAmount(new BigDecimal("250.00"));

        return payment;
    }

    @Test
    void paymentEndpointReturns401WithoutToken() throws Exception {
        mockMvc.perform(get(PAYMENT_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verifyNoInteractions(paymentRetrievalService);
    }

    @Test
    void bookingEndpointReturns401WithoutToken() throws Exception {
        mockMvc.perform(get(BOOKING_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verifyNoInteractions(paymentRetrievalService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = { "PROVIDER", "ADMIN" })
    void paymentEndpointReturns403ForNonCustomer(Role role)
            throws Exception {

        String token = authenticateAs(role);

        mockMvc.perform(
                get(PAYMENT_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        verifyNoInteractions(paymentRetrievalService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = { "PROVIDER", "ADMIN" })
    void bookingEndpointReturns403ForNonCustomer(Role role)
            throws Exception {

        String token = authenticateAs(role);

        mockMvc.perform(
                get(BOOKING_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        verifyNoInteractions(paymentRetrievalService);
    }

    @Test
    void paymentEndpointReturns200ForCustomer() throws Exception {
        String token = authenticateAs(Role.CUSTOMER);
        String customerId = "user-customer";

        when(paymentRetrievalService.getPaymentForCustomer(
                PAYMENT_ID,
                customerId)).thenReturn(createPayment());

        mockMvc.perform(
                get(PAYMENT_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID))
                .andExpect(jsonPath("$.bookingId").value(BOOKING_ID))
                .andExpect(jsonPath("$.amount").value(2500.00))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andExpect(jsonPath("$.refundedAmount").value(0.00))
                .andExpect(jsonPath("$.gatewayRef").doesNotExist())
                .andExpect(jsonPath("$.commissionRate").doesNotExist())
                .andExpect(jsonPath("$.commissionAmount").doesNotExist());

        verify(paymentRetrievalService).getPaymentForCustomer(
                PAYMENT_ID,
                customerId);
    }

    @Test
    void bookingEndpointReturns200ForCustomer() throws Exception {
        String token = authenticateAs(Role.CUSTOMER);
        String customerId = "user-customer";

        when(paymentRetrievalService.getPaymentsForBooking(
                BOOKING_ID,
                customerId)).thenReturn(List.of(createPayment()));

        mockMvc.perform(
                get(BOOKING_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(PAYMENT_ID))
                .andExpect(jsonPath("$[0].bookingId").value(BOOKING_ID))
                .andExpect(jsonPath("$[0].gatewayRef").doesNotExist())
                .andExpect(jsonPath("$[0].commissionRate").doesNotExist());

        verify(paymentRetrievalService).getPaymentsForBooking(
                BOOKING_ID,
                customerId);
    }

    @Test
    void bookingEndpointReturnsEmptyListForCustomer() throws Exception {
        String token = authenticateAs(Role.CUSTOMER);

        when(paymentRetrievalService.getPaymentsForBooking(
                BOOKING_ID,
                "user-customer")).thenReturn(List.of());

        mockMvc.perform(
                get(BOOKING_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void paymentEndpointReturns404WhenPaymentNotFound() throws Exception {
        String token = authenticateAs(Role.CUSTOMER);

        when(paymentRetrievalService.getPaymentForCustomer(
                PAYMENT_ID,
                "user-customer")).thenThrow(new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PAYMENT_NOT_FOUND",
                        "Payment not found"));

        mockMvc.perform(
                get(PAYMENT_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void paymentEndpointHidesUnauthorizedPayment() throws Exception {
        String token = authenticateAs(Role.CUSTOMER);

        // Retrieval service hides inaccessible payments behind a generic 404.
        when(paymentRetrievalService.getPaymentForCustomer(
                PAYMENT_ID,
                "user-customer")).thenThrow(new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PAYMENT_NOT_FOUND",
                        "Payment not found"));

        mockMvc.perform(
                get(PAYMENT_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.gatewayRef").doesNotExist());
    }

    @Test
    void bookingEndpointReturns403ForUnauthorizedBooking()
            throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        when(paymentRetrievalService.getPaymentsForBooking(
                BOOKING_ID,
                "user-customer")).thenThrow(
                        new AccessDeniedException("Access denied"));

        mockMvc.perform(
                get(BOOKING_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    void paymentEndpointUsesAuthenticatedIdentityNotRequestParameter()
            throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        when(paymentRetrievalService.getPaymentForCustomer(
                PAYMENT_ID,
                "user-customer")).thenReturn(createPayment());

        mockMvc.perform(
                get(PAYMENT_URL)
                        .param("customerId", "another-customer")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(paymentRetrievalService).getPaymentForCustomer(
                PAYMENT_ID,
                "user-customer");

        verify(paymentRetrievalService, never())
                .getPaymentForCustomer(
                        PAYMENT_ID,
                        "another-customer");
    }

    /**
     * Configures a mock JWT token recognized by the project's actual
     * JWT authentication filter.
     *
     * @param role authenticated user's role
     * @return test token
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
