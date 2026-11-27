
package com.gamagerecruiters.marketplace.modules.payment.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentRetrievalService;
import com.gamagerecruiters.marketplace.modules.payment.service.CustomerRefundRetrievalService;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentReceiptPdfService;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.CustomerRefundResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.CustomerRefundPageResponse;

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
        private static final String MY_PAYMENTS_URL = "/api/v1/payments/me";

        private static final String PAYMENT_ID = "payment-123";
        private static final String BOOKING_ID = "booking-456";

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private PaymentRetrievalService paymentRetrievalService;

        @MockitoBean
        private CustomerRefundRetrievalService refundRetrievalService;

        @MockitoBean
        private PaymentReceiptPdfService receiptPdfService;

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
         * Unauthenticated requests must be rejected by the security
         * filter chain before the payment retrieval service is called.
         */
        @Test
        void myPaymentsEndpointReturns401WithoutToken() throws Exception {
                mockMvc.perform(get(MY_PAYMENTS_URL))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.status").value(401))
                                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

                verifyNoInteractions(paymentRetrievalService);
        }

        /**
         * Only customers may access their payment history.
         * Providers and administrators must receive HTTP 403.
         */
        @ParameterizedTest
        @EnumSource(value = Role.class, names = { "PROVIDER", "ADMIN" })
        void myPaymentsEndpointReturns403ForNonCustomer(Role role)
                        throws Exception {

                String token = authenticateAs(role);

                mockMvc.perform(
                                get(MY_PAYMENTS_URL)
                                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.status").value(403))
                                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

                verifyNoInteractions(paymentRetrievalService);
        }

        /**
         * Verifies the customer-facing paginated response and confirms
         * internal gateway and commission fields are not exposed.
         */
        @Test
        void myPaymentsEndpointReturns200ForCustomer() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                Page<Payment> paymentPage = new PageImpl<>(
                                List.of(createPayment()),
                                PageRequest.of(0, 10),
                                1);

                when(paymentRetrievalService.getPaymentsForCustomer(
                                "user-customer", 0, 10))
                                .thenReturn(paymentPage);

                mockMvc.perform(
                                get(MY_PAYMENTS_URL)
                                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content[0].paymentId")
                                                .value(PAYMENT_ID))
                                .andExpect(jsonPath("$.content[0].bookingId")
                                                .value(BOOKING_ID))
                                .andExpect(jsonPath("$.content[0].amount")
                                                .value(2500.00))
                                .andExpect(jsonPath("$.content[0].currency")
                                                .value("LKR"))
                                .andExpect(jsonPath("$.content[0].status")
                                                .value("INITIATED"))
                                .andExpect(jsonPath("$.content[0].gatewayRef")
                                                .doesNotExist())
                                .andExpect(jsonPath("$.content[0].commissionRate")
                                                .doesNotExist())
                                .andExpect(jsonPath("$.content[0].commissionAmount")
                                                .doesNotExist())
                                .andExpect(jsonPath("$.page").value(0))
                                .andExpect(jsonPath("$.size").value(10))
                                .andExpect(jsonPath("$.totalElements").value(1))
                                .andExpect(jsonPath("$.totalPages").value(1));

                verify(paymentRetrievalService).getPaymentsForCustomer(
                                "user-customer", 0, 10);
        }

        /**
         * Ensures query parameters cannot override the authenticated
         * customer identity obtained from the verified JWT.
         */
        @Test
        void myPaymentsEndpointIgnoresSuppliedCustomerId() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(paymentRetrievalService.getPaymentsForCustomer(
                                "user-customer", 0, 10))
                                .thenReturn(Page.empty(PageRequest.of(0, 10)));

                mockMvc.perform(
                                get(MY_PAYMENTS_URL)
                                                .param("customerId", "another-customer")
                                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk());

                verify(paymentRetrievalService).getPaymentsForCustomer(
                                "user-customer", 0, 10);

                verify(paymentRetrievalService, never())
                                .getPaymentsForCustomer(
                                                "another-customer", 0, 10);
        }

        /**
         * Confirms custom pagination values are forwarded correctly
         * through the HTTP endpoint.
         */
        @Test
        void myPaymentsEndpointAcceptsCustomPagination() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(paymentRetrievalService.getPaymentsForCustomer(
                                "user-customer", 2, 20))
                                .thenReturn(Page.empty(PageRequest.of(2, 20)));

                mockMvc.perform(
                                get(MY_PAYMENTS_URL)
                                                .param("page", "2")
                                                .param("size", "20")
                                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.page").value(2))
                                .andExpect(jsonPath("$.size").value(20))
                                .andExpect(jsonPath("$.content").isEmpty());

                verify(paymentRetrievalService).getPaymentsForCustomer(
                                "user-customer", 2, 20);
        }

        /**
         * Verifies that invalid page numbers produce the expected
         * HTTP 400 response.
         */
        @Test
        void myPaymentsEndpointRejectsNegativePage() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(paymentRetrievalService.getPaymentsForCustomer(
                                "user-customer", -1, 10))
                                .thenThrow(new ApiException(
                                                HttpStatus.BAD_REQUEST,
                                                "INVALID_PAYMENT_PAGINATION",
                                                "Page number must not be negative"));

                mockMvc.perform(
                                get(MY_PAYMENTS_URL)
                                                .param("page", "-1")
                                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error")
                                                .value("INVALID_PAYMENT_PAGINATION"));
        }

        /**
         * Verifies that excessive page sizes are rejected
         * instead of returning unbounded payment records.
         */
        @Test
        void myPaymentsEndpointRejectsExcessivePageSize() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(paymentRetrievalService.getPaymentsForCustomer(
                                "user-customer", 0, 101))
                                .thenThrow(new ApiException(
                                                HttpStatus.BAD_REQUEST,
                                                "INVALID_PAYMENT_PAGINATION",
                                                "Page size must be between 1 and 100"));

                mockMvc.perform(
                                get(MY_PAYMENTS_URL)
                                                .param("size", "101")
                                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error")
                                                .value("INVALID_PAYMENT_PAGINATION"));
        }

        /**
         * Confirms that a customer with no payments receives
         * a valid empty pagination response.
         */
        @Test
        void myPaymentsEndpointReturnsEmptyPage() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(paymentRetrievalService.getPaymentsForCustomer(
                                "user-customer", 0, 10))
                                .thenReturn(Page.empty(PageRequest.of(0, 10)));

                mockMvc.perform(
                                get(MY_PAYMENTS_URL)
                                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content").isEmpty())
                                .andExpect(jsonPath("$.totalElements").value(0))
                                .andExpect(jsonPath("$.totalPages").value(0));
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

        // Receipt and refund endpoints must enforce customer-only access.

        @Test
        void receiptEndpointReturns401WithoutToken() throws Exception {
                mockMvc.perform(get(PAYMENT_URL + "/receipt"))
                                .andExpect(status().isUnauthorized());

                verifyNoInteractions(
                                paymentRetrievalService,
                                refundRetrievalService);
        }

        @Test
        void refundEndpointReturns401WithoutToken() throws Exception {
                mockMvc.perform(get(PAYMENT_URL + "/refunds"))
                                .andExpect(status().isUnauthorized());

                verifyNoInteractions(
                                paymentRetrievalService,
                                refundRetrievalService);
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = { "PROVIDER", "ADMIN" })
        void receiptEndpointReturns403ForNonCustomer(Role role)
                        throws Exception {

                String token = authenticateAs(role);

                mockMvc.perform(get(PAYMENT_URL + "/receipt")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isForbidden());

                verifyNoInteractions(
                                paymentRetrievalService,
                                refundRetrievalService);
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = { "PROVIDER", "ADMIN" })
        void refundEndpointReturns403ForNonCustomer(Role role)
                        throws Exception {

                String token = authenticateAs(role);

                mockMvc.perform(get(PAYMENT_URL + "/refunds")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isForbidden());

                verifyNoInteractions(
                                paymentRetrievalService,
                                refundRetrievalService);
        }

        @Test
        void receiptEndpointReturnsVerifiedReceiptForCustomer()
                        throws Exception {

                String token = authenticateAs(Role.CUSTOMER);

                PaymentReceiptResponse receipt = new PaymentReceiptResponse(
                                "RCT-payment-123",
                                PAYMENT_ID,
                                BOOKING_ID,
                                new BigDecimal("2500.00"),
                                "LKR",
                                Instant.parse("2026-11-19T06:30:00Z"));

                when(paymentRetrievalService.getReceiptForCustomer(
                                PAYMENT_ID, "user-customer"))
                                .thenReturn(receipt);

                mockMvc.perform(get(PAYMENT_URL + "/receipt")
                                .param("customerId", "another-customer")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.receiptReference")
                                                .value("RCT-payment-123"))
                                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID))
                                .andExpect(jsonPath("$.bookingId").value(BOOKING_ID))
                                .andExpect(jsonPath("$.amount").value(2500.00))
                                .andExpect(jsonPath("$.currency").value("LKR"))
                                .andExpect(jsonPath("$.paidAt").exists())
                                .andExpect(jsonPath("$.gatewayRef").doesNotExist())
                                .andExpect(jsonPath("$.commissionAmount").doesNotExist())
                                .andExpect(org.springframework.test.web.servlet
                                                .result.MockMvcResultMatchers.header()
                                                .string("Cache-Control", "no-store"));

                verify(paymentRetrievalService).getReceiptForCustomer(
                                PAYMENT_ID, "user-customer");
        }

        @Test
        void receiptEndpointHidesUnauthorizedPayment() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(paymentRetrievalService.getReceiptForCustomer(
                                PAYMENT_ID, "user-customer"))
                                .thenThrow(new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "PAYMENT_NOT_FOUND",
                                                "Payment not found"));

                mockMvc.perform(get(PAYMENT_URL + "/receipt")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error")
                                                .value("PAYMENT_NOT_FOUND"))
                                .andExpect(jsonPath("$.receiptReference")
                                                .doesNotExist());
        }

        @Test
        void receiptEndpointReturns404WhenReceiptUnavailable()
                        throws Exception {

                String token = authenticateAs(Role.CUSTOMER);

                when(paymentRetrievalService.getReceiptForCustomer(
                                PAYMENT_ID, "user-customer"))
                                .thenThrow(new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "RECEIPT_NOT_FOUND",
                                                "Payment receipt is not available"));

                mockMvc.perform(get(PAYMENT_URL + "/receipt")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error")
                                                .value("RECEIPT_NOT_FOUND"));
        }

        @Test
        void refundEndpointReturnsCustomerSafeRefundHistory()
                        throws Exception {

                String token = authenticateAs(Role.CUSTOMER);

                Instant createdAt = Instant.parse("2026-11-19T06:30:00Z");
                Instant completedAt = createdAt.plusSeconds(60);

                CustomerRefundResponse refund = new CustomerRefundResponse(
                                "refund-123",
                                PAYMENT_ID,
                                BOOKING_ID,
                                new BigDecimal("1500.00"),
                                "LKR",
                                CustomerRefundResponse.CustomerRefundStatus.COMPLETED,
                                createdAt,
                                completedAt,
                                completedAt);

                when(refundRetrievalService.getRefundsForCustomer(
                                PAYMENT_ID, "user-customer", 0, 10))
                                .thenReturn(new CustomerRefundPageResponse(
                                                List.of(refund), 0, 10, 1, 1));

                mockMvc.perform(get(PAYMENT_URL + "/refunds")
                                .param("customerId", "another-customer")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content[0].refundId")
                                                .value("refund-123"))
                                .andExpect(jsonPath("$.content[0].paymentId")
                                                .value(PAYMENT_ID))
                                .andExpect(jsonPath("$.content[0].amount")
                                                .value(1500.00))
                                .andExpect(jsonPath("$.content[0].status")
                                                .value("COMPLETED"))
                                .andExpect(jsonPath("$.content[0].completedAt").exists())
                                .andExpect(jsonPath("$.content[0].gatewayRefundReference")
                                                .doesNotExist())
                                .andExpect(jsonPath("$.content[0].idempotencyKey")
                                                .doesNotExist())
                                .andExpect(jsonPath("$.content[0].disputeId")
                                                .doesNotExist())
                                .andExpect(jsonPath("$.content[0].reservation")
                                                .doesNotExist())
                                .andExpect(jsonPath("$.page").value(0))
                                .andExpect(jsonPath("$.size").value(10))
                                .andExpect(jsonPath("$.totalElements").value(1))
                                .andExpect(jsonPath("$.totalPages").value(1))
                                .andExpect(org.springframework.test.web.servlet
                                                .result.MockMvcResultMatchers.header()
                                                .string("Cache-Control", "no-store"));

                verify(refundRetrievalService).getRefundsForCustomer(
                                PAYMENT_ID, "user-customer", 0, 10);
        }

        @Test
        void refundEndpointHidesUnauthorizedPayment() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(refundRetrievalService.getRefundsForCustomer(
                                PAYMENT_ID, "user-customer", 0, 10))
                                .thenThrow(new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "PAYMENT_NOT_FOUND",
                                                "Payment not found"));

                mockMvc.perform(get(PAYMENT_URL + "/refunds")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error")
                                                .value("PAYMENT_NOT_FOUND"))
                                .andExpect(jsonPath("$.content").doesNotExist());
        }

        @Test
        void refundEndpointRejectsNegativePage() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(refundRetrievalService.getRefundsForCustomer(
                                PAYMENT_ID, "user-customer", -1, 10))
                                .thenThrow(new ApiException(
                                                HttpStatus.BAD_REQUEST,
                                                "INVALID_PAGINATION",
                                                "Invalid pagination"));

                mockMvc.perform(get(PAYMENT_URL + "/refunds")
                                .param("page", "-1")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error")
                                                .value("INVALID_PAGINATION"));
        }

        @Test
        void refundEndpointRejectsExcessivePageSize() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(refundRetrievalService.getRefundsForCustomer(
                                PAYMENT_ID, "user-customer", 0, 101))
                                .thenThrow(new ApiException(
                                                HttpStatus.BAD_REQUEST,
                                                "INVALID_PAGINATION",
                                                "Invalid pagination"));

                mockMvc.perform(get(PAYMENT_URL + "/refunds")
                                .param("size", "101")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error")
                                                .value("INVALID_PAGINATION"));
        }

        @Test
        void refundEndpointAcceptsCustomPaginationAndEmptyPage()
                        throws Exception {

                String token = authenticateAs(Role.CUSTOMER);

                when(refundRetrievalService.getRefundsForCustomer(
                                PAYMENT_ID, "user-customer", 1, 5))
                                .thenReturn(new CustomerRefundPageResponse(
                                                List.of(), 1, 5, 0, 0));

                mockMvc.perform(get(PAYMENT_URL + "/refunds")
                                .param("page", "1")
                                .param("size", "5")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content").isEmpty())
                                .andExpect(jsonPath("$.page").value(1))
                                .andExpect(jsonPath("$.size").value(5))
                                .andExpect(jsonPath("$.totalElements").value(0));

                verify(refundRetrievalService).getRefundsForCustomer(
                                PAYMENT_ID, "user-customer", 1, 5);
        }

        @Test
        void pdfDownloadReturns401WithoutToken() throws Exception {
                mockMvc.perform(get(PAYMENT_URL + "/receipt/download"))
                                .andExpect(status().isUnauthorized());

                verifyNoInteractions(receiptPdfService);
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {
                "PROVIDER", "ADMIN", "FINANCE_ADMIN"
        })
        void pdfDownloadReturns403ForNonCustomer(Role role)
                        throws Exception {

                String token = authenticateAs(role);

                mockMvc.perform(get(PAYMENT_URL + "/receipt/download")
                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isForbidden());

                verifyNoInteractions(receiptPdfService);
        }

        @Test
        void pdfDownloadReturnsSecureAttachmentForCustomer()
                        throws Exception {

                String token = authenticateAs(Role.CUSTOMER);

                byte[] pdf = "%PDF-1.7".getBytes(
                        java.nio.charset.StandardCharsets.US_ASCII);

                when(receiptPdfService.generateForCustomer(
                        PAYMENT_ID, "user-customer"))
                        .thenReturn(pdf);

                mockMvc.perform(get(PAYMENT_URL + "/receipt/download")
                                .param("customerId", "another-customer")
                                .header("Authorization", "Bearer " + token)
                                .accept("application/pdf"))
                                .andExpect(status().isOk())
                                .andExpect(org.springframework.test.web.servlet.result
                                        .MockMvcResultMatchers.content()
                                        .contentType("application/pdf"))
                                .andExpect(org.springframework.test.web.servlet.result
                                        .MockMvcResultMatchers.header()
                                        .string("Content-Disposition",
                                                "attachment; filename=\"payment-receipt.pdf\""))
                                .andExpect(org.springframework.test.web.servlet.result
                                        .MockMvcResultMatchers.header()
                                        .string("Cache-Control", "no-store"))
                                .andExpect(org.springframework.test.web.servlet.result
                                        .MockMvcResultMatchers.header()
                                        .string("X-Content-Type-Options", "nosniff"))
                                .andExpect(org.springframework.test.web.servlet.result
                                        .MockMvcResultMatchers.content().bytes(pdf));

                verify(receiptPdfService).generateForCustomer(
                        PAYMENT_ID, "user-customer");
        }

        @Test
        void pdfDownloadHidesUnauthorizedPayment() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(receiptPdfService.generateForCustomer(
                        PAYMENT_ID, "user-customer"))
                        .thenThrow(new ApiException(
                                HttpStatus.NOT_FOUND,
                                "PAYMENT_NOT_FOUND",
                                "Payment not found"));

                mockMvc.perform(get(PAYMENT_URL + "/receipt/download")
                                .header("Authorization", "Bearer " + token)
                                .accept("application/pdf", "application/json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error")
                                        .value("PAYMENT_NOT_FOUND"))
                                .andExpect(org.springframework.test.web.servlet.result
                                        .MockMvcResultMatchers.header()
                                        .doesNotExist("Content-Disposition"));
        }

        @Test
        void pdfDownloadReturns404WhenReceiptUnavailable()
                        throws Exception {

                String token = authenticateAs(Role.CUSTOMER);

                when(receiptPdfService.generateForCustomer(
                        PAYMENT_ID, "user-customer"))
                        .thenThrow(new ApiException(
                                HttpStatus.NOT_FOUND,
                                "RECEIPT_NOT_FOUND",
                                "Payment receipt is not available"));

                mockMvc.perform(get(PAYMENT_URL + "/receipt/download")
                                .header("Authorization", "Bearer " + token)
                                .accept("application/pdf", "application/json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error")
                                        .value("RECEIPT_NOT_FOUND"));
        }


        @Test
        void pdfOnlyAcceptReturnsStructuredError() throws Exception {
                String token = authenticateAs(Role.CUSTOMER);

                when(receiptPdfService.generateForCustomer(
                        PAYMENT_ID, "user-customer"))
                        .thenThrow(new ApiException(
                                HttpStatus.NOT_FOUND,
                                "RECEIPT_NOT_FOUND",
                                "Payment receipt is not available"));

                mockMvc.perform(get(PAYMENT_URL + "/receipt/download")
                                .header("Authorization", "Bearer " + token)
                                .accept("application/pdf"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error")
                                        .value("RECEIPT_NOT_FOUND"));
        }

}
