package com.gamagerecruiters.marketplace.modules.payment.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import com.gamagerecruiters.marketplace.modules.payment.service.CustomerCheckoutService;
import com.gamagerecruiters.marketplace.modules.payment.service.CustomerCheckoutService.CheckoutResult;
import com.gamagerecruiters.marketplace.modules.payment.service.CustomerCheckoutService.CheckoutState;

/**
 * Verifies customer Checkout REST authorization, authenticated
 * identity handling and customer-facing response contracts.
 *
 * <p>
 * Checkout is explicitly enabled only within this isolated
 * MVC test context. The orchestration service is mocked, so
 * no external gateway or financial persistence operations occur.
 * </p>
 */
@WebMvcTest(controllers = CustomerCheckoutController.class, properties = "app.payment.customer-checkout.enabled=true")
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class CustomerCheckoutControllerSecurityTest {

    private static final String BOOKING_ID = "booking-456";
    private static final String CUSTOMER_ID = "user-customer";
    private static final String PAYMENT_ID = "payment-123";

    private static final String CHECKOUT_URL = "https://checkout.stripe.com/c/pay/cs_test_example";

    private static final String ENDPOINT = "/api/v1/payments/booking/" + BOOKING_ID + "/checkout";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerCheckoutService checkoutService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    /**
     * Unauthenticated requests must never reach Checkout.
     */
    @Test
    void rejectsRequestWithoutJwt() throws Exception {

        mockMvc.perform(post(ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verifyNoInteractions(checkoutService);
    }

    @Test
    void rejectsInvalidJwt() throws Exception {

        when(tokenService.validateToken("invalid-token"))
                .thenReturn(false);

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(checkoutService);
    }

    /**
     * Every non-customer role must be denied access.
     */
    @ParameterizedTest
    @EnumSource(value = Role.class, names = { "CUSTOMER" }, mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonCustomerRoles(Role role) throws Exception {

        String token = authenticateAs(role);

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        verifyNoInteractions(checkoutService);
    }

    /**
     * Authorized Checkout returns only the customer-facing
     * result and prevents HTTP caching of the Checkout URL.
     */
    @Test
    void returnsCheckoutReadyForAuthorizedCustomer()
            throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        stubReadyCheckout();

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control", "no-store"))
                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID))
                .andExpect(jsonPath("$.state").value("CHECKOUT_READY"))
                .andExpect(jsonPath("$.checkoutUrl").value(CHECKOUT_URL))
                .andExpect(jsonPath("$.gatewayRef").doesNotExist())
                .andExpect(jsonPath("$.gatewayPaymentIntentId").doesNotExist())
                .andExpect(jsonPath("$.commissionRate").doesNotExist())
                .andExpect(jsonPath("$.commissionAmount").doesNotExist())
                .andExpect(jsonPath("$.checkoutReservationToken").doesNotExist());

        verify(checkoutService).checkout(
                BOOKING_ID,
                CUSTOMER_ID);
    }

    /**
     * A completed gateway session must not be presented
     * as an authoritative successful payment.
     */
    @Test
    void returnsVerificationPendingWithoutCheckoutUrl()
            throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        when(checkoutService.checkout(
                BOOKING_ID,
                CUSTOMER_ID))
                .thenReturn(new CheckoutResult(
                        PAYMENT_ID,
                        CheckoutState.PAYMENT_VERIFICATION_PENDING,
                        null));

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control", "no-store"))
                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID))
                .andExpect(jsonPath("$.state")
                        .value("PAYMENT_VERIFICATION_PENDING"))
                .andExpect(jsonPath("$.checkoutUrl").doesNotExist());

        verify(checkoutService).checkout(
                BOOKING_ID,
                CUSTOMER_ID);
    }

    @Test
    void rejectsUnauthorizedBooking() throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        when(checkoutService.checkout(
                BOOKING_ID,
                CUSTOMER_ID))
                .thenThrow(new AccessDeniedException(
                        "Access denied"));

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * Existing structured API exceptions must retain their
     * intended HTTP status and error code.
     */
    @Test
    void preservesStructuredBookingNotFoundResponse()
            throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        when(checkoutService.checkout(
                BOOKING_ID,
                CUSTOMER_ID))
                .thenThrow(new ApiException(
                        HttpStatus.NOT_FOUND,
                        "BOOKING_NOT_FOUND",
                        "Booking not found"));

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error")
                        .value("BOOKING_NOT_FOUND"));
    }

    /**
     * Client-supplied query parameters must not override
     * authenticated identity or trusted financial inputs.
     */
    @Test
    void ignoresClientSuppliedQueryParameters()
            throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        stubReadyCheckout();

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + token)
                .param("customerId", "another-customer")
                .param("bookingId", "another-booking")
                .param("amount", "1.00")
                .param("currency", "USD")
                .param("commissionRate", "0.00")
                .param("successUrl", "https://untrusted.example/success")
                .param("cancelUrl", "https://untrusted.example/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value(CHECKOUT_URL));

        verify(checkoutService).checkout(
                BOOKING_ID,
                CUSTOMER_ID);

        verifyNoMoreInteractions(checkoutService);
    }

    /**
     * An unsolicited request body must not supply identity,
     * financial data or redirect URLs to the orchestration service.
     */
    @Test
    void ignoresClientSuppliedRequestBody() throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        stubReadyCheckout();

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "customerId": "another-customer",
                          "bookingId": "another-booking",
                          "amount": 1.00,
                          "currency": "USD",
                          "commissionRate": 0.00,
                          "successUrl": "https://untrusted.example/success",
                          "cancelUrl": "https://untrusted.example/cancel"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value(CHECKOUT_URL));

        verify(checkoutService).checkout(
                BOOKING_ID,
                CUSTOMER_ID);

        verifyNoMoreInteractions(checkoutService);
    }

    /**
     * Repeated requests must use the same authenticated
     * booking and customer identity.
     *
     * <p>
     * Actual duplicate prevention is verified separately
     * by service and MongoDB integration tests.
     * </p>
     */
    @Test
    void forwardsRepeatedRequestsWithSameTrustedIdentity()
            throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        stubReadyCheckout();

        for (int request = 0; request < 2; request++) {

            mockMvc.perform(post(ENDPOINT)
                    .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID))
                    .andExpect(jsonPath("$.checkoutUrl").value(CHECKOUT_URL));
        }

        verify(checkoutService, times(2)).checkout(
                BOOKING_ID,
                CUSTOMER_ID);
    }

    /**
     * Unexpected internal failures must not expose sensitive
     * gateway or infrastructure information to customers.
     */
    @Test
    void sanitizesUnexpectedInternalFailure() throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        String internalDetail = "internal-gateway-sensitive-detail";

        when(checkoutService.checkout(
                BOOKING_ID,
                CUSTOMER_ID))
                .thenThrow(new IllegalStateException(internalDetail));

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(result -> org.junit.jupiter.api.Assertions
                        .assertFalse(result.getResponse()
                                .getContentAsString()
                                .contains(internalDetail)));
    }

    private void stubReadyCheckout() {

        when(checkoutService.checkout(
                BOOKING_ID,
                CUSTOMER_ID))
                .thenReturn(new CheckoutResult(
                        PAYMENT_ID,
                        CheckoutState.CHECKOUT_READY,
                        CHECKOUT_URL));
    }

    /**
     * Configures the actual JWT filter's mocked dependencies
     * using an active user with the requested role.
     */
    private String authenticateAs(Role role) {

        String token = "test-token-" + role.name().toLowerCase();

        String userId = "user-" + role.name().toLowerCase();

        when(tokenService.validateToken(token))
                .thenReturn(true);

        when(tokenService.getUserIdFromToken(token))
                .thenReturn(userId);

        when(tokenService.getRoleFromToken(token))
                .thenReturn(role);

        User user = new User();

        user.setId(userId);
        user.setEmail(userId + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);

        when(userInternalService.getById(userId))
                .thenReturn(user);

        return token;
    }
}

/**
 * Verifies fail-closed activation behavior without supplying
 * the customer Checkout activation property.
 */
@WebMvcTest(CustomerCheckoutController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class CustomerCheckoutDefaultDisabledSecurityTest {

    private static final String ENDPOINT = "/api/v1/payments/booking/booking-456/checkout";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerCheckoutService checkoutService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    /**
     * Without explicit activation, authenticated customers
     * must receive HTTP 503 before financial operations begin.
     */
    @Test
    void checkoutIsDisabledByDefault() throws Exception {

        String token = authenticateAs(Role.CUSTOMER);

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error")
                        .value("CUSTOMER_CHECKOUT_DISABLED"));

        verifyNoInteractions(checkoutService);
    }

    @Test
    void disabledCheckoutStillRequiresAuthentication()
            throws Exception {

        mockMvc.perform(post(ENDPOINT))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(checkoutService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = { "CUSTOMER" }, mode = EnumSource.Mode.EXCLUDE)
    void disabledCheckoutStillEnforcesCustomerRole(Role role)
            throws Exception {

        String token = authenticateAs(role);

        mockMvc.perform(post(ENDPOINT)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        verifyNoInteractions(checkoutService);
    }

    private String authenticateAs(Role role) {

        String token = "test-token-" + role.name().toLowerCase();

        String userId = "user-" + role.name().toLowerCase();

        when(tokenService.validateToken(token))
                .thenReturn(true);

        when(tokenService.getUserIdFromToken(token))
                .thenReturn(userId);

        when(tokenService.getRoleFromToken(token))
                .thenReturn(role);

        User user = new User();

        user.setId(userId);
        user.setEmail(userId + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);

        when(userInternalService.getById(userId))
                .thenReturn(user);

        return token;
    }
}