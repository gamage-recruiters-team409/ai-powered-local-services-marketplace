package com.gamagerecruiters.marketplace.modules.payment.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.model.StripeCollection;
import com.stripe.param.RefundListParams;

/**
 * Verifies read-only Stripe refund reconciliation without live API calls.
 * Ambiguous or incomplete gateway evidence must never authorize settlement.
 */
class StripeRefundLookupGatewayTest {

    private static final String SECRET = "sk_test_lookup";
    private static final String OPERATION = "refund-001";
    private static final String PAYMENT = "pi_test001";
    private static final String REFERENCE = "re_test001";
    private static final BigDecimal AMOUNT = new BigDecimal("200.00");

    private final StripeGatewayProperties properties =
            mock(StripeGatewayProperties.class);

    private final StripeRefundGateway.StripeRefundCreator creator =
            mock(StripeRefundGateway.StripeRefundCreator.class);

    private final StripeRefundGateway.StripeRefundRetriever retriever =
            mock(StripeRefundGateway.StripeRefundRetriever.class);

    private final StripeRefundGateway.StripeRefundLister lister =
            mock(StripeRefundGateway.StripeRefundLister.class);

    private StripeRefundGateway gateway;

    @BeforeEach
    void setUp() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getSecretKey()).thenReturn(SECRET);

        gateway = new StripeRefundGateway(
                properties, creator, retriever, lister);
    }

    @Test
    void retrievesKnownRefundReference() throws StripeException {
        when(retriever.retrieve(SECRET, REFERENCE))
                .thenReturn(refund(REFERENCE, OPERATION));

        assertTrue(lookup(REFERENCE).isPresent());

        verify(retriever).retrieve(SECRET, REFERENCE);
        verifyNoInteractions(lister, creator);
    }

    @Test
    void recoversMissingReferenceUsingMetadata() throws StripeException {
        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenReturn(page(false, refund(REFERENCE, OPERATION)));

        assertTrue(lookup(null).isPresent());

        verifyNoInteractions(retriever, creator);
    }

    @Test
    void searchesMultiplePagesWithCorrectCursor() throws StripeException {
        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenReturn(
                        page(true, refund("re_other001", "other-operation")),
                        page(false, refund(REFERENCE, OPERATION)));

        assertTrue(lookup(null).isPresent());

        ArgumentCaptor<RefundListParams> captor =
                ArgumentCaptor.forClass(RefundListParams.class);

        verify(lister, times(2)).list(eq(SECRET), captor.capture());

        var requests = captor.getAllValues();

        assertEquals(PAYMENT, requests.get(0).getPaymentIntent());
        assertEquals(Long.valueOf(100), requests.get(0).getLimit());
        assertNull(requests.get(0).getStartingAfter());

        assertEquals(PAYMENT, requests.get(1).getPaymentIntent());
        assertEquals("re_other001", requests.get(1).getStartingAfter());

        verifyNoInteractions(creator, retriever);
    }

    @Test
    void rejectsDuplicateMatchesAcrossPages() throws StripeException {
        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenReturn(
                        page(true, refund(REFERENCE, OPERATION)),
                        page(false, refund("re_test002", OPERATION)));

        assertThrows(IllegalStateException.class, () -> lookup(null));
        verifyNoInteractions(creator);
    }

    @Test
    void missingMatchReturnsEmptyWithoutCreatingRefund()
            throws StripeException {

        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenReturn(page(false,
                        refund("re_other001", "other-operation")));

        assertTrue(lookup(null).isEmpty());
        verifyNoInteractions(creator, retriever);
    }

    @Test
    void emptyCompletedListReturnsEmpty() throws StripeException {
        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenReturn(page(false));

        assertTrue(lookup(null).isEmpty());
        verifyNoInteractions(creator);
    }

    @Test
    void rejectsNullRefundList() throws StripeException {
        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenReturn(null);

        assertThrows(IllegalStateException.class, () -> lookup(null));
        verifyNoInteractions(creator);
    }

    @Test
    void rejectsIncompletePaginationMetadata() throws StripeException {
        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenReturn(page(null, refund(REFERENCE, OPERATION)));

        assertThrows(IllegalStateException.class, () -> lookup(null));
        verifyNoInteractions(creator);
    }

    @Test
    void rejectsEmptyPageWhenMorePagesAreExpected()
            throws StripeException {

        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenReturn(page(true));

        assertThrows(IllegalStateException.class, () -> lookup(null));
        verifyNoInteractions(creator);
    }

    @Test
    void rejectsRepeatedPaginationCursor() throws StripeException {
        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenReturn(page(true,
                        refund("re_other001", "other-operation")));

        assertThrows(IllegalStateException.class, () -> lookup(null));

        verify(lister, times(2))
                .list(eq(SECRET), any(RefundListParams.class));

        verifyNoInteractions(creator);
    }

    @Test
    void rejectsIncompleteSearchEvenWhenMatchWasFound()
            throws StripeException {

        var calls = new java.util.concurrent.atomic.AtomicInteger();

        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenAnswer(invocation -> {
                    int number = calls.incrementAndGet();

                    return page(true, refund(
                            "re_page" + number,
                            number == 1 ? OPERATION : "other-operation"));
                });

        assertThrows(IllegalStateException.class, () -> lookup(null));

        verify(lister, times(20))
                .list(eq(SECRET), any(RefundListParams.class));

        verifyNoInteractions(creator);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "reference", "metadata", "payment", "amount", "currency"
    })
    void rejectsMismatchedRefundEvidence(String mismatch)
            throws StripeException {

        Refund response = refund(REFERENCE, OPERATION);

        switch (mismatch) {
            case "reference" -> response.setId("re_other001");
            case "metadata" -> response.setMetadata(
                    Map.of("refundOperationId", "other-operation"));
            case "payment" -> response.setPaymentIntent("pi_other001");
            case "amount" -> response.setAmount(10000L);
            case "currency" -> response.setCurrency("usd");
            default -> throw new AssertionError(mismatch);
        }

        when(retriever.retrieve(SECRET, REFERENCE))
                .thenReturn(response);

        assertThrows(IllegalStateException.class,
                () -> lookup(REFERENCE));

        verifyNoInteractions(creator, lister);
    }

    @Test
    void rejectsMissingMetadataOnKnownReference()
            throws StripeException {

        Refund response = refund(REFERENCE, OPERATION);
        response.setMetadata(null);

        when(retriever.retrieve(SECRET, REFERENCE))
                .thenReturn(response);

        assertThrows(IllegalStateException.class,
                () -> lookup(REFERENCE));

        verifyNoInteractions(creator);
    }

    @Test
    void retrievalFailureRemainsUncertain() throws StripeException {
        when(retriever.retrieve(SECRET, REFERENCE))
                .thenThrow(mock(StripeException.class));

        assertThrows(IllegalStateException.class,
                () -> lookup(REFERENCE));

        verifyNoInteractions(creator, lister);
    }

    @Test
    void listingFailureRemainsUncertain() throws StripeException {
        when(lister.list(eq(SECRET), any(RefundListParams.class)))
                .thenThrow(mock(StripeException.class));

        assertThrows(IllegalStateException.class, () -> lookup(null));
        verifyNoInteractions(creator, retriever);
    }

    @Test
    void disabledGatewayRejectsLookupBeforeExternalCall() {
        when(properties.isEnabled()).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> lookup(REFERENCE));

        verifyNoInteractions(creator, retriever, lister);
    }

    private java.util.Optional<PaymentRefundGateway.RefundResult> lookup(
            String reference) {

        return gateway.findRefund(
                OPERATION, PAYMENT, reference, AMOUNT, "LKR");
    }

    private Refund refund(String reference, String operation) {
        Refund refund = new Refund();

        refund.setId(reference);
        refund.setPaymentIntent(PAYMENT);
        refund.setAmount(20000L);
        refund.setCurrency("lkr");
        refund.setStatus("succeeded");
        refund.setMetadata(Map.of("refundOperationId", operation));

        return refund;
    }

    private StripeCollection<Refund> page(
            Boolean hasMore, Refund... refunds) {

        StripeCollection<Refund> page = new StripeCollection<>();

        page.setData(List.of(refunds));
        page.setHasMore(hasMore);

        return page;
    }
}