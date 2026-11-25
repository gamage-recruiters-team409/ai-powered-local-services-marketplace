
package com.gamagerecruiters.marketplace.modules.payment.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        assertEquals(11, recordComponents.length);

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

    @Test
    void mapsPersistedPaymentMetadata() {
        Payment payment = new Payment();

        payment.setReceiptReference("receipt-123");
        payment.setRefundReference("refund-456");
        payment.setRefundStatus("PROCESSING");
        payment.setPaidAt(java.time.Instant.parse("2026-11-19T06:30:00Z"));

        PaymentResponse response = PaymentResponse.from(payment);

        org.junit.jupiter.api.Assertions.assertEquals(
                "receipt-123", response.receiptReference());
        org.junit.jupiter.api.Assertions.assertEquals(
                "refund-456", response.refundReference());
        org.junit.jupiter.api.Assertions.assertEquals(
                "PROCESSING", response.refundStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                java.time.Instant.parse("2026-11-19T06:30:00Z"),
                response.paidAt());
    }

    @Test
    void serializesToExpectedCamelCaseJsonContract() throws Exception {
        Payment payment = createPayment();
        payment.setReceiptReference("rcpt-999");
        payment.setRefundReference("ref-888");
        payment.setRefundStatus("PROCESSING");
        payment.setPaidAt(Instant.parse("2026-11-11T17:05:00Z"));

        PaymentResponse response = PaymentResponse.from(payment);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        String json = objectMapper.writeValueAsString(response);
        JsonNode tree = objectMapper.readTree(json);

        // Required customer contract fields in camelCase
        assertTrue(tree.has("paymentId"), "Must have paymentId");
        assertTrue(tree.has("bookingId"), "Must have bookingId");
        assertTrue(tree.has("amount"), "Must have amount");
        assertTrue(tree.has("currency"), "Must have currency");
        assertTrue(tree.has("status"), "Must have status");
        assertTrue(tree.has("refundedAmount"), "Must have refundedAmount");
        assertTrue(tree.has("createdAt"), "Must have createdAt");
        assertTrue(tree.has("receiptReference"), "Must have receiptReference");
        assertTrue(tree.has("refundReference"), "Must have refundReference");
        assertTrue(tree.has("refundStatus"), "Must have refundStatus");
        assertTrue(tree.has("paidAt"), "Must have paidAt");

        // Values match
        assertEquals("payment-123", tree.get("paymentId").asText());
        assertEquals("rcpt-999", tree.get("receiptReference").asText());
        assertEquals("ref-888", tree.get("refundReference").asText());
        assertEquals("PROCESSING", tree.get("refundStatus").asText());

        // Internal fields never exposed
        assertFalse(tree.has("gatewayRef"), "Internal gatewayRef must not be exposed");
        assertFalse(tree.has("commissionRate"), "Internal commissionRate must not be exposed");
        assertFalse(tree.has("commissionAmount"), "Internal commissionAmount must not be exposed");
    }
}
