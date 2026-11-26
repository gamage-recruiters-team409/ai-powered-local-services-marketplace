package com.gamagerecruiters.marketplace.modules.payment.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.stripe.model.StripeCollection;
import com.stripe.model.checkout.Session;

/**
 * Verifies read-only Stripe checkout recovery without external network calls.
 */
class StripeCheckoutRecoveryGatewayTest {

    private static final String PAYMENT_ID = "payment-1";
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final String CURRENCY = "USD";

    private final Instant reservedAt = Instant.now().minusSeconds(60);

    @Test
    void findsUniqueOpenSession() {
        StripeCheckoutGateway gateway = gateway(
                (key, params) -> page(false, session("cs_test_123")));

        var result = gateway.findCheckoutSession(
                PAYMENT_ID, AMOUNT, CURRENCY, reservedAt);

        assertTrue(result.isPresent());
        assertEquals("cs_test_123", result.get().sessionId());
        assertEquals("open", result.get().status());
    }

    @Test
    void findsCompletedPaidSessionWithoutCheckoutUrl() {
        Session session = session("cs_test_123");
        session.setStatus("complete");
        session.setPaymentStatus("paid");
        session.setUrl(null);

        StripeCheckoutGateway gateway = gateway(
                (key, params) -> page(false, session));

        var result = gateway.findCheckoutSession(
                PAYMENT_ID, AMOUNT, CURRENCY, reservedAt);

        assertTrue(result.isPresent());
        assertEquals("paid", result.get().paymentStatus());
        assertNull(result.get().checkoutUrl());
    }

    @Test
    void returnsEmptyWhenNoMatchingSessionExists() {
        Session unrelated = session("cs_test_other");
        unrelated.setClientReferenceId("another-payment");

        StripeCheckoutGateway gateway = gateway(
                (key, params) -> page(false, unrelated));

        assertTrue(gateway.findCheckoutSession(
                PAYMENT_ID, AMOUNT, CURRENCY, reservedAt).isEmpty());
    }

    @Test
    void findsMatchingSessionAcrossMultiplePages() {
        AtomicInteger calls = new AtomicInteger();

        Session unrelated = session("cs_test_other");
        unrelated.setClientReferenceId("another-payment");

        StripeCheckoutGateway gateway = gateway((key, params) -> {
            if (calls.incrementAndGet() == 1) {
                assertNull(params.getStartingAfter());
                return page(true, unrelated);
            }

            assertEquals(
                    "cs_test_other", params.getStartingAfter());

            return page(false, session("cs_test_123"));
        });

        var result = gateway.findCheckoutSession(
                PAYMENT_ID, AMOUNT, CURRENCY, reservedAt);

        assertTrue(result.isPresent());
        assertEquals("cs_test_123", result.get().sessionId());
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsDuplicateMatchingSessions() {
        StripeCheckoutGateway gateway = gateway(
                (key, params) -> page(false,
                        session("cs_test_123"),
                        session("cs_test_456")));

        assertRejected(gateway);
    }

    @Test
    void rejectsAmountMismatch() {
        Session session = session("cs_test_123");
        session.setAmountTotal(9999L);

        assertRejected(gateway(
                (key, params) -> page(false, session)));
    }

    @Test
    void rejectsCurrencyMismatch() {
        Session session = session("cs_test_123");
        session.setCurrency("eur");

        assertRejected(gateway(
                (key, params) -> page(false, session)));
    }

    @Test
    void rejectsExpiredOpenSession() {
        Session session = session("cs_test_123");
        session.setExpiresAt(
                Instant.now().minusSeconds(60).getEpochSecond());

        assertRejected(gateway(
                (key, params) -> page(false, session)));
    }

    @Test
    void rejectsIncompleteGatewayResponse() {
        assertRejected(gateway((key, params) -> null));
    }

    @Test
    void rejectsEmptyPageWithMoreResults() {
        assertRejected(gateway(
                (key, params) -> page(true)));
    }

    @Test
    void rejectsRepeatedSessionAcrossPages() {
        Session unrelated = session("cs_test_other");
        unrelated.setClientReferenceId("another-payment");

        assertRejected(gateway(
                (key, params) -> page(true, unrelated)));
    }

    @Test
    void rejectsSearchLimitExhaustion() {
        AtomicInteger calls = new AtomicInteger();

        StripeCheckoutGateway gateway = gateway((key, params) -> {
            Session unrelated = session(
                    "cs_test_" + calls.incrementAndGet());

            unrelated.setClientReferenceId("another-payment");

            return page(true, unrelated);
        });

        assertRejected(gateway);
        assertEquals(20, calls.get());
    }

    private void assertRejected(StripeCheckoutGateway gateway) {
        assertThrows(IllegalStateException.class,
                () -> gateway.findCheckoutSession(
                        PAYMENT_ID, AMOUNT, CURRENCY, reservedAt));
    }

    private StripeCheckoutGateway gateway(
            StripeCheckoutGateway.StripeSessionLister lister) {

        StripeGatewayProperties properties =
                new StripeGatewayProperties(
                        true, "sk_test_dummy", "whsec_dummy");

        return new StripeCheckoutGateway(
                properties,
                (key, params, options) -> {
                    throw new AssertionError(
                            "Recovery must never create a Stripe session");
                },
                lister);
    }

    private Session session(String id) {
        Session session = new Session();

        session.setId(id);
        session.setClientReferenceId(PAYMENT_ID);
        session.setAmountTotal(10000L);
        session.setCurrency("usd");
        session.setMode("payment");
        session.setStatus("open");
        session.setPaymentStatus("unpaid");
        session.setCreated(reservedAt.getEpochSecond());
        session.setExpiresAt(
                Instant.now().plusSeconds(3600).getEpochSecond());
        session.setUrl("https://checkout.stripe.com/test");

        return session;
    }

    private static StripeCollection<Session> page(
            boolean hasMore, Session... sessions) {

        StripeCollection<Session> result = new StripeCollection<>();

        result.setData(List.of(sessions));
        result.setHasMore(hasMore);

        return result;
    }
}