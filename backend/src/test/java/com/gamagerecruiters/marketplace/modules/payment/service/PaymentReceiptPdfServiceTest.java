package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptDetailsResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptDetailsResponse.LineItem;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptResponse;

/**
 * Verifies PDF generation, receipt content and authorization delegation.
 */
class PaymentReceiptPdfServiceTest {

    private static final String PAYMENT_ID = "payment-123";
    private static final String CUSTOMER_ID = "customer-123";

    private PaymentReceiptDetailsService detailsService;
    private PaymentReceiptPdfService service;

    @BeforeEach
    void setUp() {
        detailsService = mock(PaymentReceiptDetailsService.class);
        service = new PaymentReceiptPdfService(detailsService);
    }

    private PaymentReceiptDetailsResponse createDetails(
            String bookingReference,
            String providerId) {

        PaymentReceiptResponse receipt = new PaymentReceiptResponse(
                "RCT-payment-123",
                PAYMENT_ID,
                "booking-456",
                new BigDecimal("12500.50"),
                "LKR",
                Instant.parse("2026-11-27T08:00:00Z"));

        return new PaymentReceiptDetailsResponse(
                receipt,
                bookingReference,
                null,
                null,
                null,
                null,
                providerId,
                null,
                List.of(new LineItem(
                        "Verified booking payment",
                        receipt.amount())));
    }

    @Test
    void generatesReadableSinglePageA4Receipt() throws Exception {
        when(detailsService.getDetailsForCustomer(
                PAYMENT_ID, CUSTOMER_ID))
                .thenReturn(createDetails("BK-456", "provider-789"));

        byte[] pdf = service.generateForCustomer(
                PAYMENT_ID, CUSTOMER_ID);

        assertNotNull(pdf);
        assertTrue(pdf.length > 0);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());

            PDRectangle pageSize =
                    document.getPage(0).getMediaBox();

            assertEquals(PDRectangle.A4.getWidth(),
                    pageSize.getWidth(), 0.1f);
            assertEquals(PDRectangle.A4.getHeight(),
                    pageSize.getHeight(), 0.1f);

            assertEquals("LocalServe Payment Receipt",
                    document.getDocumentInformation().getTitle());
        }

        verify(detailsService).getDetailsForCustomer(
                PAYMENT_ID, CUSTOMER_ID);
    }

    @Test
    void includesVerifiedReceiptInformation() throws Exception {
        when(detailsService.getDetailsForCustomer(
                PAYMENT_ID, CUSTOMER_ID))
                .thenReturn(createDetails("BK-456", "provider-789"));

        byte[] pdf = service.generateForCustomer(
                PAYMENT_ID, CUSTOMER_ID);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);

            assertTrue(text.contains("LocalServe"));
            assertTrue(text.contains("RCT-payment-123"));
            assertTrue(text.contains(PAYMENT_ID));
            assertTrue(text.contains("booking-456"));
            assertTrue(text.contains("BK-456"));
            assertTrue(text.contains("provider-789"));
            assertTrue(text.contains("2026-11-27 08:00:00 UTC"));
            assertTrue(text.contains("LKR 12500.50"));
            assertTrue(text.contains("Total Paid"));
            assertTrue(text.contains("Subsequent refunds"));
        }
    }

    @Test
    void handlesUnavailableOptionalFields() throws Exception {
        when(detailsService.getDetailsForCustomer(
                PAYMENT_ID, CUSTOMER_ID))
                .thenReturn(createDetails(null, null));

        byte[] pdf = service.generateForCustomer(
                PAYMENT_ID, CUSTOMER_ID);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);

            assertTrue(text.contains("Not available"));
            assertTrue(text.contains("LKR 12500.50"));
            assertFalse(text.contains("null"));
        }
    }

    @Test
    void propagatesUnauthorizedReceiptAccess() {
        ApiException failure = new ApiException(
                HttpStatus.NOT_FOUND,
                "PAYMENT_NOT_FOUND",
                "Payment not found");

        when(detailsService.getDetailsForCustomer(
                PAYMENT_ID, CUSTOMER_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(ApiException.class,
                () -> service.generateForCustomer(
                        PAYMENT_ID, CUSTOMER_ID)));
    }

    @Test
    void propagatesUnavailableReceiptFailure() {
        ApiException failure = new ApiException(
                HttpStatus.NOT_FOUND,
                "RECEIPT_NOT_FOUND",
                "Receipt not found");

        when(detailsService.getDetailsForCustomer(
                PAYMENT_ID, CUSTOMER_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(ApiException.class,
                () -> service.generateForCustomer(
                        PAYMENT_ID, CUSTOMER_ID)));
    }
}
