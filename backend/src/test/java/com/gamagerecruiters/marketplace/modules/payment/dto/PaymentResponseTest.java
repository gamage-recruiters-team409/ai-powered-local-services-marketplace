
package com.gamagerecruiters.marketplace.modules.payment.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Unit tests for mapping payment entities to customer-facing responses.
 *
 * <p>
 * These tests verify response field mapping and ensure that internal
 * payment information is not included in the public response structure.
 * </p>
 */
class PaymentResponseTest {

    /**
     * Creates a representative payment entity for mapping tests.
     *
     * @return populated payment entity
     */
    private Payment createPayment() {
        Payment payment = new Payment();

        payment.setId("payment-123");
        payment.setBookingId("booking-456");
        payment.setAmount(new BigDecimal("2500.00"));
        payment.setCurrency("LKR");
        payment.setStatus(PaymentStatus.INITIATED);
        payment.setRefundedAmount(new BigDecimal("0.00"));
        payment.setCreatedAt(
                Instant.parse("2026-11-11T17:00:00Z"));

        // Internal fields must not appear in the customer response.
        payment.setGatewayRef("gateway-secret-reference");
        payment.setCommissionRate(new BigDecimal("0.10"));
        payment.setCommissionAmount(new BigDecimal("250.00"));

        return payment;
    }

    @Test
    void mapsPaymentIdentifier() {
        PaymentResponse response = PaymentResponse.from(createPayment());

        assertEquals("payment-123", response.paymentId());
    }

    @Test
    void mapsBookingIdentifier() {
        PaymentResponse response = PaymentResponse.from(createPayment());

        assertEquals("booking-456", response.bookingId());
    }

    @Test
    void mapsPaymentAmount() {
        PaymentResponse response = PaymentResponse.from(createPayment());

        assertEquals(new BigDecimal("2500.00"), response.amount());
    }

    @Test
    void mapsPaymentCurrency() {
        PaymentResponse response = PaymentResponse.from(createPayment());

        assertEquals("LKR", response.currency());
    }

    @Test
    void mapsPaymentStatus() {
        PaymentResponse response = PaymentResponse.from(createPayment());

        assertEquals(PaymentStatus.INITIATED, response.status());
    }

    @Test
    void mapsRefundedAmount() {
        PaymentResponse response = PaymentResponse.from(createPayment());

        assertEquals(
                new BigDecimal("0.00"),
                response.refundedAmount());
    }

    @Test
    void mapsCreationTimestamp() {
        PaymentResponse response = PaymentResponse.from(createPayment());

        assertEquals(
                Instant.parse("2026-11-11T17:00:00Z"),
                response.createdAt());
    }

    @Test
    void rejectsNullPaymentEntity() {
        assertThrows(
                NullPointerException.class,
                () -> PaymentResponse.from(null));
    }

    @Test
    void preservesNullOptionalFields() {
        Payment payment = createPayment();
        payment.setRefundedAmount(null);
        payment.setCreatedAt(null);

        PaymentResponse response = PaymentResponse.from(payment);

        assertNull(response.refundedAmount());
        assertNull(response.createdAt());
    }

    @Test
    void excludesInternalPaymentFieldsFromResponseRecord() {
        var recordComponents = PaymentResponse.class.getRecordComponents();

        assertEquals(7, recordComponents.length);

        for (var component : recordComponents) {
            String fieldName = component.getName();

            org.junit.jupiter.api.Assertions.assertFalse(
                    fieldName.equals("gatewayRef")
                            || fieldName.equals("commissionRate")
                            || fieldName.equals("commissionAmount")
                            || fieldName.equals("updatedAt"),
                    "Internal field must not be exposed: " + fieldName);
        }
    }
}
