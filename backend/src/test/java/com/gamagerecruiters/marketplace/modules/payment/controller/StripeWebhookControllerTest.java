package com.gamagerecruiters.marketplace.modules.payment.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.modules.payment.service.StripeWebhookProcessingService;
import com.stripe.exception.SignatureVerificationException;

/**
 * Tests Stripe webhook HTTP response handling without external network calls.
 */
class StripeWebhookControllerTest {

    private StripeWebhookProcessingService processingService;
    private StripeWebhookController controller;

    @BeforeEach
    void setUp() {
        processingService = mock(StripeWebhookProcessingService.class);
        controller = new StripeWebhookController(processingService);
    }

    @Test
    void successfulCaptureReturnsOk() throws Exception {
        when(processingService.process("payload", "signature")).thenReturn(true);

        assertEquals(HttpStatus.OK,
                controller.receiveWebhook("payload", "signature").getStatusCode());
        verify(processingService).process("payload", "signature");
    }

    @Test
    void duplicateOrIgnoredEventReturnsOk() throws Exception {
        when(processingService.process("payload", "signature")).thenReturn(false);

        assertEquals(HttpStatus.OK,
                controller.receiveWebhook("payload", "signature").getStatusCode());
    }

    @Test
    void invalidSignatureReturnsBadRequest() throws Exception {
        doThrow(new SignatureVerificationException(
                "Invalid Stripe signature", "sig_header"))
                .when(processingService).process("payload", "invalid");

        assertEquals(HttpStatus.BAD_REQUEST,
                controller.receiveWebhook("payload", "invalid").getStatusCode());
    }

    @Test
    void invalidPayloadReturnsBadRequest() throws Exception {
        when(processingService.process("payload", "signature"))
                .thenThrow(new IllegalArgumentException("Invalid payload"));

        assertEquals(HttpStatus.BAD_REQUEST,
                controller.receiveWebhook("payload", "signature").getStatusCode());
    }

    @Test
    void processingFailureReturnsServerErrorForRetry() throws Exception {
        when(processingService.process("payload", "signature"))
                .thenThrow(new IllegalStateException("Database unavailable"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                controller.receiveWebhook("payload", "signature").getStatusCode());
    }
}