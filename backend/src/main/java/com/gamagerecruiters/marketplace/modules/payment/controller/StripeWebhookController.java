package com.gamagerecruiters.marketplace.modules.payment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.payment.service.StripeWebhookProcessingService;
import com.stripe.exception.SignatureVerificationException;

/**
 * Receives Stripe webhook notifications without JWT authentication.
 *
 * <p>Every financial operation is delegated to the processing service,
 * which verifies the Stripe signature before accepting payment data.</p>
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/payments/stripe")
public class StripeWebhookController {

    private final StripeWebhookProcessingService processingService;

    public StripeWebhookController(
            StripeWebhookProcessingService processingService) {
        this.processingService = java.util.Objects.requireNonNull(
                processingService, "Stripe webhook processing service is required");
    }

    /**
     * Processes an authenticated Stripe event using the original request body.
     *
     * @param payload raw Stripe webhook payload
     * @param signature Stripe-Signature request header
     * @return 200 when accepted, 400 for invalid signatures, or 500 on failure
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> receiveWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false)
                    String signature) {

        try {
            processingService.process(payload, signature);
            return ResponseEntity.ok().build();
        } catch (SignatureVerificationException | IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (RuntimeException exception) {
            // A non-2xx response allows Stripe to retry transient failures.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}