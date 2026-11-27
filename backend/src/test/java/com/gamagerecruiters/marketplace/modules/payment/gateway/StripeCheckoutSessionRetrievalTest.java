package com.gamagerecruiters.marketplace.modules.payment.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway.RecoveredCheckoutSession;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;

/**
 * Verifies read-only retrieval of previously attached Stripe Checkout Sessions.
 *
 * <p>
 * Tests enforce payment identity, financial consistency and supported
 * session states without submitting external Stripe requests.
 * </p>
 */
class StripeCheckoutSessionRetrievalTest {

    private static final String SECRET_KEY = "sk_test_unit_test";
    private static final String WEBHOOK_SECRET = "whsec_unit_test";

    private static final String SESSION_ID = "cs_test_existing_session";
    private static final String PAYMENT_ID = "wp07-initial-test-payment";

    private static final String CURRENCY = "LKR";

    private static final BigDecimal AMOUNT = new BigDecimal("10000.00");

    private static final long MINOR_UNITS = 1000000L;

    private static final String CHECKOUT_URL = "https://checkout.stripe.com/c/pay/cs_test_existing_session";

    private StripeCheckoutGateway.StripeSessionCreator sessionCreator;

    private StripeCheckoutGateway.StripeSessionLister sessionLister;

    private StripeCheckoutGateway.StripeSessionRetriever sessionRetriever;

    private StripeCheckoutGateway gateway;

    @BeforeEach
    void setUp() {

        sessionCreator = mock(
                StripeCheckoutGateway.StripeSessionCreator.class);

        sessionLister = mock(
                StripeCheckoutGateway.StripeSessionLister.class);

        sessionRetriever = mock(
                StripeCheckoutGateway.StripeSessionRetriever.class);

        gateway = new StripeCheckoutGateway(
                new StripeGatewayProperties(
                        true,
                        SECRET_KEY,
                        WEBHOOK_SECRET),
                sessionCreator,
                sessionLister,
                sessionRetriever);
    }

    /**
     * An eligible open session can be returned without creating
     * another Stripe Checkout Session.
     */
    @Test
    void retrievesValidOpenUnpaidSession() throws StripeException {

        stubSession(validSession());

        RecoveredCheckoutSession result = retrieve();

        assertEquals(SESSION_ID, result.sessionId());
        assertEquals(CHECKOUT_URL, result.checkoutUrl());
        assertEquals("open", result.status());
        assertEquals("unpaid", result.paymentStatus());

        verifyReadOnlyRetrieval();
    }

    /**
     * Completed sessions must not expose another checkout URL.
     * Retrieval does not independently confirm payment capture.
     */
    @ParameterizedTest
    @ValueSource(strings = { "paid", "unpaid" })
    void retrievesCompletedSessionWithoutCheckoutUrl(
            String paymentStatus) throws StripeException {

        Session session = validSession();

        session.setStatus("complete");
        session.setPaymentStatus(paymentStatus);

        stubSession(session);

        RecoveredCheckoutSession result = retrieve();

        assertEquals(SESSION_ID, result.sessionId());
        assertEquals("complete", result.status());
        assertEquals(paymentStatus, result.paymentStatus());
        assertNull(result.checkoutUrl());

        verifyReadOnlyRetrieval();
    }

    @Test
    void acceptsNormalizedSupportedCurrency() throws StripeException {

        stubSession(validSession());

        RecoveredCheckoutSession result = gateway.retrieveCheckoutSession(
                SESSION_ID,
                PAYMENT_ID,
                AMOUNT,
                " lkr ");

        assertEquals(SESSION_ID, result.sessionId());

        verifyReadOnlyRetrieval();
    }

    @Test
    void rejectsIncorrectSessionIdentity() throws StripeException {

        Session session = validSession();

        session.setId("cs_test_different_session");

        assertRejectedSession(session);
    }

    @Test
    void rejectsIncorrectPaymentIdentity() throws StripeException {

        Session session = validSession();

        session.setClientReferenceId("different-payment");

        assertRejectedSession(session);
    }

    @Test
    void rejectsMissingPaymentIdentity() throws StripeException {

        Session session = validSession();

        session.setClientReferenceId(null);

        assertRejectedSession(session);
    }

    @Test
    void rejectsAmountMismatch() throws StripeException {

        Session session = validSession();

        session.setAmountTotal(MINOR_UNITS + 1L);

        assertRejectedSession(session);
    }

    @Test
    void rejectsMissingAmount() throws StripeException {

        Session session = validSession();

        session.setAmountTotal(null);

        assertRejectedSession(session);
    }

    @Test
    void rejectsCurrencyMismatch() throws StripeException {

        Session session = validSession();

        session.setCurrency("usd");

        assertRejectedSession(session);
    }

    @Test
    void rejectsMissingCurrency() throws StripeException {

        Session session = validSession();

        session.setCurrency(null);

        assertRejectedSession(session);
    }

    @Test
    void rejectsInvalidPaymentMode() throws StripeException {

        Session session = validSession();

        session.setMode("subscription");

        assertRejectedSession(session);
    }

    @Test
    void rejectsMissingCreationTimestamp() throws StripeException {

        Session session = validSession();

        session.setCreated(null);

        assertRejectedSession(session);
    }

    @Test
    void rejectsExpiredOpenSession() throws StripeException {

        Session session = validSession();

        session.setExpiresAt(
                Instant.now().minusSeconds(60).getEpochSecond());

        assertRejectedSession(session);
    }

    @Test
    void rejectsMissingExpirationTimestamp() throws StripeException {

        Session session = validSession();

        session.setExpiresAt(null);

        assertRejectedSession(session);
    }

    @Test
    void rejectsExplicitlyExpiredSession() throws StripeException {

        Session session = validSession();

        session.setStatus("expired");

        assertRejectedSession(session);
    }

    @Test
    void rejectsOpenAlreadyPaidSession() throws StripeException {

        Session session = validSession();

        session.setPaymentStatus("paid");

        assertRejectedSession(session);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t" })
    void rejectsMissingOpenCheckoutUrl(
            String checkoutUrl) throws StripeException {

        Session session = validSession();

        session.setUrl(checkoutUrl);

        assertRejectedSession(session);
    }

    @Test
    void rejectsUnsupportedSessionState() throws StripeException {

        Session session = validSession();

        session.setStatus("unknown");

        assertRejectedSession(session);
    }

    @Test
    void rejectsUnsupportedCompletedPaymentState()
            throws StripeException {

        Session session = validSession();

        session.setStatus("complete");
        session.setPaymentStatus("no_payment_required");

        assertRejectedSession(session);
    }

    @Test
    void rejectsNullGatewayResponse() throws StripeException {

        stubSession(null);

        assertThrows(
                IllegalStateException.class,
                this::retrieve);

        verifyReadOnlyRetrieval();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t", "pi_test_invalid_reference" })
    void rejectsInvalidSessionReferenceBeforeGatewayCall(
            String sessionId) {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.retrieveCheckoutSession(
                        sessionId,
                        PAYMENT_ID,
                        AMOUNT,
                        CURRENCY));

        verifyNoGatewayCalls();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t" })
    void rejectsMissingPaymentIdBeforeGatewayCall(
            String paymentId) {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.retrieveCheckoutSession(
                        SESSION_ID,
                        paymentId,
                        AMOUNT,
                        CURRENCY));

        verifyNoGatewayCalls();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "JPY", "XXX" })
    void rejectsUnsupportedCurrencyBeforeGatewayCall(
            String currency) {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.retrieveCheckoutSession(
                        SESSION_ID,
                        PAYMENT_ID,
                        AMOUNT,
                        currency));

        verifyNoGatewayCalls();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0",
            "-1.00",
            "0.001",
            "10000.001",
            "999999999999999999999999.00"
    })
    void rejectsInvalidAmountBeforeGatewayCall(String amount) {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.retrieveCheckoutSession(
                        SESSION_ID,
                        PAYMENT_ID,
                        new BigDecimal(amount),
                        CURRENCY));

        verifyNoGatewayCalls();
    }

    @Test
    void rejectsNullAmountBeforeGatewayCall() {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.retrieveCheckoutSession(
                        SESSION_ID,
                        PAYMENT_ID,
                        null,
                        CURRENCY));

        verifyNoGatewayCalls();
    }

    @Test
    void rejectsDisabledGatewayBeforeExternalCall() {

        StripeCheckoutGateway disabledGateway = new StripeCheckoutGateway(
                new StripeGatewayProperties(
                        false, "", ""),
                sessionCreator,
                sessionLister,
                sessionRetriever);

        assertThrows(
                IllegalStateException.class,
                () -> disabledGateway.retrieveCheckoutSession(
                        SESSION_ID,
                        PAYMENT_ID,
                        AMOUNT,
                        CURRENCY));

        verifyNoGatewayCalls();
    }

    /**
     * Gateway failures must not trigger a replacement session
     * or an alternative external creation request.
     */
    @Test
    void gatewayFailureFailsClosed() throws StripeException {

        StripeException gatewayFailure = mock(StripeException.class);

        when(sessionRetriever.retrieve(
                SECRET_KEY, SESSION_ID))
                .thenThrow(gatewayFailure);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                this::retrieve);

        assertEquals(
                "Unable to retrieve Stripe checkout session.",
                exception.getMessage());

        assertSame(gatewayFailure, exception.getCause());

        verifyReadOnlyRetrieval();
    }

    /**
     * Builds a valid gateway response for isolated validation tests.
     */
    private Session validSession() {

        Session session = new Session();

        Instant now = Instant.now();

        session.setId(SESSION_ID);
        session.setClientReferenceId(PAYMENT_ID);
        session.setAmountTotal(MINOR_UNITS);
        session.setCurrency("lkr");
        session.setMode("payment");
        session.setStatus("open");
        session.setPaymentStatus("unpaid");
        session.setUrl(CHECKOUT_URL);

        session.setCreated(
                now.minusSeconds(60).getEpochSecond());

        session.setExpiresAt(
                now.plusSeconds(1800).getEpochSecond());

        return session;
    }

    private void stubSession(Session session)
            throws StripeException {

        when(sessionRetriever.retrieve(
                SECRET_KEY, SESSION_ID))
                .thenReturn(session);
    }

    private RecoveredCheckoutSession retrieve() {

        return gateway.retrieveCheckoutSession(
                SESSION_ID,
                PAYMENT_ID,
                AMOUNT,
                CURRENCY);
    }

    private void assertRejectedSession(Session session)
            throws StripeException {

        stubSession(session);

        assertThrows(
                IllegalStateException.class,
                this::retrieve);

        verifyReadOnlyRetrieval();
    }

    /**
     * Ensures retrieval performs exactly one read-only gateway call
     * and never falls back to session creation or session listing.
     */
    private void verifyReadOnlyRetrieval()
            throws StripeException {

        verify(sessionRetriever, times(1))
                .retrieve(SECRET_KEY, SESSION_ID);

        verifyNoMoreInteractions(sessionRetriever);

        verifyNoInteractions(
                sessionCreator,
                sessionLister);
    }

    private void verifyNoGatewayCalls() {

        verifyNoInteractions(
                sessionCreator,
                sessionLister,
                sessionRetriever);
    }
}