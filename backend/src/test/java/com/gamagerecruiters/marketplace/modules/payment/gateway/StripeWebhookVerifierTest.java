package com.gamagerecruiters.marketplace.modules.payment.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;

/**
 * Unit tests for Stripe webhook signature verification.
 *
 * <p>Tests use locally generated HMAC-SHA256 signatures.
 * No Stripe network requests or real credentials are required.</p>
 */
class StripeWebhookVerifierTest {

    private static final String WEBHOOK_SECRET = "whsec_test_local_secret";

    private static final String PAYLOAD = """
            {
              "id": "evt_test_webhook",
              "object": "event",
              "type": "checkout.session.completed",
              "data": {
                "object": {
                  "id": "cs_test_checkout",
                  "object": "checkout.session"
                }
              }
            }
            """;

    private StripeWebhookVerifier enabledVerifier() {
        return new StripeWebhookVerifier(
                new StripeGatewayProperties(
                        true,
                        "sk_test_local_placeholder",
                        WEBHOOK_SECRET));
    }

    /**
     * Creates a Stripe-compatible test signature over the exact raw payload.
     */
    private static String sign(String payload, long timestamp, String secret)
            throws Exception {

        String signedPayload = timestamp + "." + payload;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));

        byte[] digest = mac.doFinal(
                signedPayload.getBytes(StandardCharsets.UTF_8));

        StringBuilder hex = new StringBuilder();

        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }

        return "t=" + timestamp + ",v1=" + hex;
    }

    @Test
    void validSignatureReturnsVerifiedEvent() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signature = sign(PAYLOAD, timestamp, WEBHOOK_SECRET);

        Event event = enabledVerifier().verify(PAYLOAD, signature);

        assertNotNull(event);
        assertEquals("evt_test_webhook", event.getId());
        assertEquals("checkout.session.completed", event.getType());
    }

    @Test
    void invalidSignatureIsRejected() throws Exception {
        long timestamp = Instant.now().getEpochSecond();

        String signature = sign(
                PAYLOAD,
                timestamp,
                "whsec_wrong_secret");

        assertThrows(
                SignatureVerificationException.class,
                () -> enabledVerifier().verify(PAYLOAD, signature));
    }

    @Test
    void modifiedPayloadIsRejected() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signature = sign(PAYLOAD, timestamp, WEBHOOK_SECRET);

        String modifiedPayload = PAYLOAD.replace(
                "evt_test_webhook",
                "evt_modified");

        assertThrows(
                SignatureVerificationException.class,
                () -> enabledVerifier().verify(modifiedPayload, signature));
    }

    @Test
    void expiredTimestampIsRejected() throws Exception {
        long timestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        String signature = sign(PAYLOAD, timestamp, WEBHOOK_SECRET);

        assertThrows(
                SignatureVerificationException.class,
                () -> enabledVerifier().verify(PAYLOAD, signature));
    }

    @Test
    void missingSignatureIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> enabledVerifier().verify(PAYLOAD, null));
    }

    @Test
    void blankSignatureIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> enabledVerifier().verify(PAYLOAD, " "));
    }

    @Test
    void nullPayloadIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> enabledVerifier().verify(null, "signature"));
    }

    @Test
    void blankPayloadIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> enabledVerifier().verify(" ", "signature"));
    }

    @Test
    void disabledStripeConfigurationRejectsVerification() {
        StripeGatewayProperties properties =
                new StripeGatewayProperties(false, "", "");

        StripeWebhookVerifier verifier =
                new StripeWebhookVerifier(properties);

        assertThrows(
                IllegalStateException.class,
                () -> verifier.verify(PAYLOAD, "signature"));
    }
}