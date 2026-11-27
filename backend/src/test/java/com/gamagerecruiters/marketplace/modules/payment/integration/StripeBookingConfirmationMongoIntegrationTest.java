package com.gamagerecruiters.marketplace.modules.payment.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.result.UpdateResult;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingPriceSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacadeImpl;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingPaymentPolicy;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.StripeWebhookEvent;
import com.gamagerecruiters.marketplace.modules.payment.service.BookingPaymentConfirmationService;
import com.gamagerecruiters.marketplace.modules.payment.service.StripeWebhookTransactionService;

/**
 * Real MongoDB integration tests for atomic WP07 payment capture
 * and WP05 booking confirmation.
 *
 * <p>
 * Production services and real MongoDB repositories share the
 * same database factory and transaction manager.
 * </p>
 *
 * <p>
 * No external Stripe requests are performed. Verified payment
 * data is supplied through deterministic test fixtures.
 * </p>
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class StripeBookingConfirmationMongoIntegrationTest {

    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private static final String PAYMENT_ID = "payment-001";
    private static final String CUSTOMER_ID = "customer-001";
    private static final String PROVIDER_ID = "provider-001";

    private static final String EVENT_ID = "evt_test_001";
    private static final String SESSION_ID = "cs_test_001";
    private static final String INTENT_ID = "pi_test001";

    private static final String CURRENCY = "LKR";

    private static final BigDecimal AMOUNT = new BigDecimal("5000.00");

    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.10");

    private static final Instant NOW = Instant.now();

    @BeforeAll
    static void startContainer() {
        if (!MONGO.isRunning()) {
            MONGO.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (MONGO.isRunning()) {
            MONGO.stop();
        }
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Payment, booking and webhook event changes must commit together.
     */
    @Test
    void verifiedCaptureConfirmsBookingAtomically() {
        withDatabase(context -> {
            Booking booking = seedBooking(
                    context,
                    BookingStatus.AWAITING_PAYMENT,
                    NOW.plusSeconds(600));

            seedPayment(context, booking.getId(), AMOUNT);
            seedEvent(context, EVENT_ID);

            assertTrue(capture(context.service(), EVENT_ID));

            assertCommitted(context, booking.getId(), EVENT_ID);
        });
    }

    /**
     * Repeated delivery of the same Stripe event must not
     * duplicate capture or booking timeline entries.
     */
    @Test
    void repeatedWebhookIsIdempotent() {
        withDatabase(context -> {
            Booking booking = seedBooking(
                    context,
                    BookingStatus.AWAITING_PAYMENT,
                    NOW.plusSeconds(600));

            seedPayment(context, booking.getId(), AMOUNT);
            seedEvent(context, EVENT_ID);

            assertTrue(capture(context.service(), EVENT_ID));

            Payment original = readPayment(context);

            int timelineSize = readBooking(
                    context, booking.getId())
                    .getTimeline().size();

            assertFalse(capture(context.service(), EVENT_ID));

            assertCommitted(context, booking.getId(), EVENT_ID);

            assertEquals(
                    original.getPaidAt(),
                    readPayment(context).getPaidAt());

            assertEquals(
                    timelineSize,
                    readBooking(context, booking.getId())
                            .getTimeline().size());
        });
    }

    /**
     * A second authenticated event for the same captured payment
     * must preserve the original payment and booking confirmation.
     */
    @Test
    void differentEventForSamePaymentDoesNotDuplicateConfirmation() {
        withDatabase(context -> {
            Booking booking = seedBooking(
                    context,
                    BookingStatus.AWAITING_PAYMENT,
                    NOW.plusSeconds(600));

            seedPayment(context, booking.getId(), AMOUNT);
            seedEvent(context, EVENT_ID);

            assertTrue(capture(context.service(), EVENT_ID));

            Payment original = readPayment(context);

            int timelineSize = readBooking(
                    context, booking.getId())
                    .getTimeline().size();

            String secondEventId = "evt_test_002";

            seedEvent(context, secondEventId);

            assertFalse(capture(
                    context.service(), secondEventId));

            assertCommitted(
                    context, booking.getId(), secondEventId);

            assertEquals(
                    original.getPaidAt(),
                    readPayment(context).getPaidAt());

            assertEquals(
                    timelineSize,
                    readBooking(context, booking.getId())
                            .getTimeline().size());
        });
    }

    /**
     * A verified gateway amount that differs from the authoritative
     * booking snapshot must not confirm the booking.
     */
    @Test
    void bookingAmountMismatchRollsBackLocalCapture() {
        withDatabase(context -> {
            Booking booking = seedBooking(
                    context,
                    BookingStatus.AWAITING_PAYMENT,
                    NOW.plusSeconds(600));

            BigDecimal incorrectAmount = new BigDecimal("4500.00");

            seedPayment(
                    context, booking.getId(), incorrectAmount);

            seedEvent(context, EVENT_ID);

            assertThrows(
                    IllegalStateException.class,
                    () -> context.service().captureVerifiedPayment(
                            EVENT_ID,
                            PAYMENT_ID,
                            SESSION_ID,
                            INTENT_ID,
                            incorrectAmount,
                            CURRENCY));

            assertRolledBack(
                    context,
                    booking.getId(),
                    BookingStatus.AWAITING_PAYMENT);
        });
    }

    /**
     * An expired booking must not be silently reopened.
     *
     * <p>
     * This verifies the current local rollback behavior only.
     * External late-payment reconciliation is a separate concern.
     * </p>
     */
    @Test
    void expiredBookingRejectsConfirmation() {
        withDatabase(context -> {
            Booking booking = seedBooking(
                    context,
                    BookingStatus.EXPIRED,
                    NOW.minusSeconds(1200));

            seedPayment(context, booking.getId(), AMOUNT);
            seedEvent(context, EVENT_ID);

            ResourceConflictException exception = assertThrows(
                    ResourceConflictException.class,
                    () -> capture(
                            context.service(), EVENT_ID));

            assertEquals(
                    "INVALID_STATUS_TRANSITION",
                    exception.getErrorCode());

            assertRolledBack(
                    context,
                    booking.getId(),
                    BookingStatus.EXPIRED);
        });
    }

    /**
     * WP05 accepts successful payments during its verification
     * grace period while the booking remains AWAITING_PAYMENT.
     */
    @Test
    void paymentDuringGracePeriodConfirmsBooking() {
        withDatabase(context -> {
            Booking booking = seedBooking(
                    context,
                    BookingStatus.AWAITING_PAYMENT,
                    NOW.minusSeconds(600));

            seedPayment(context, booking.getId(), AMOUNT);
            seedEvent(context, EVENT_ID);

            assertTrue(capture(context.service(), EVENT_ID));

            assertCommitted(context, booking.getId(), EVENT_ID);
        });
    }

    /**
     * Spring Security anonymous authentication must be restored
     * after trusted booking confirmation.
     */
    @Test
    void anonymousWebhookConfirmsBookingAndRestoresContext() {
        withDatabase(context -> {
            Booking booking = seedBooking(
                    context,
                    BookingStatus.AWAITING_PAYMENT,
                    NOW.plusSeconds(600));

            seedPayment(context, booking.getId(), AMOUNT);
            seedEvent(context, EVENT_ID);

            var anonymous = new AnonymousAuthenticationToken(
                    "wp07-integration-key",
                    "anonymousUser",
                    List.of(new SimpleGrantedAuthority(
                            "ROLE_ANONYMOUS")));

            var originalContext = SecurityContextHolder.createEmptyContext();

            originalContext.setAuthentication(anonymous);
            SecurityContextHolder.setContext(originalContext);

            assertTrue(capture(context.service(), EVENT_ID));

            assertCommitted(context, booking.getId(), EVENT_ID);

            assertSame(
                    originalContext,
                    SecurityContextHolder.getContext());

            assertSame(
                    anonymous,
                    SecurityContextHolder.getContext()
                            .getAuthentication());
        });
    }

    /**
     * A failure after the actual booking write must roll back
     * payment, booking and webhook event changes.
     *
     * <p>
     * Only the final event completion result is simulated.
     * Earlier MongoDB operations execute against the real database.
     * </p>
     */
    @Test
    void eventCompletionFailureRollsBackPaymentAndBooking() {
        withDatabase(context -> {
            Booking booking = seedBooking(
                    context,
                    BookingStatus.AWAITING_PAYMENT,
                    NOW.plusSeconds(600));

            seedPayment(context, booking.getId(), AMOUNT);
            seedEvent(context, EVENT_ID);

            int originalTimelineSize = readBooking(
                    context, booking.getId())
                    .getTimeline().size();

            MongoTemplate controlledTemplate = spy(context.template());

            doAnswer(invocation -> {
                Update update = invocation.getArgument(1);

                Object setOperations = update.getUpdateObject().get("$set");

                if (setOperations instanceof java.util.Map<?, ?> values
                        && StripeWebhookEvent.ProcessingStatus.PROCESSED
                                .equals(values.get("status"))) {

                    // Verify that the real booking write has already
                    // occurred inside the current transaction.
                    assertEquals(
                            BookingStatus.CONFIRMED,
                            readBooking(context, booking.getId())
                                    .getStatus());

                    return UpdateResult.acknowledged(
                            0L, 0L, null);
                }

                return invocation.callRealMethod();

            }).when(controlledTemplate).updateFirst(
                    any(Query.class),
                    any(Update.class),
                    eq(StripeWebhookEvent.class));

            StripeWebhookTransactionService controlledService = new StripeWebhookTransactionService(
                    controlledTemplate,
                    context.transactionManager(),
                    context.confirmationService(), true);

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> capture(
                            controlledService, EVENT_ID));

            assertEquals(
                    "Webhook completion update failed",
                    exception.getMessage());

            assertRolledBack(
                    context,
                    booking.getId(),
                    BookingStatus.AWAITING_PAYMENT);

            assertEquals(
                    originalTimelineSize,
                    readBooking(context, booking.getId())
                            .getTimeline().size());
        });
    }

    /**
     * Creates an isolated database and connects all production
     * services to the same MongoDB transaction infrastructure.
     */
    private void withDatabase(Consumer<TestContext> action) {
        String databaseName = "wp07_booking_confirmation_test_"
                + UUID.randomUUID()
                        .toString().replace("-", "");

        SecurityContextHolder.clearContext();

        try (MongoClient client = MongoClients.create(MONGO.getReplicaSetUrl())) {

            try {
                MongoTemplate template = new MongoTemplate(client, databaseName);

                template.createCollection(Booking.class);
                template.createCollection(Payment.class);
                template.createCollection(StripeWebhookEvent.class);

                MongoRepositoryFactory factory = new MongoRepositoryFactory(template);

                BookingRepository bookingRepository = factory.getRepository(
                        BookingRepository.class);

                MongoTransactionManager transactionManager = new MongoTransactionManager(
                        template.getMongoDatabaseFactory());

                BookingFacadeImpl bookingFacade = new BookingFacadeImpl(
                        bookingRepository,
                        new BookingPaymentPolicy(15, 15));

                BookingPaymentConfirmationService confirmationService = new BookingPaymentConfirmationService(
                        bookingFacade);

                StripeWebhookTransactionService service = new StripeWebhookTransactionService(
                        template,
                        transactionManager,
                        confirmationService, true);

                action.accept(new TestContext(
                        template,
                        bookingRepository,
                        transactionManager,
                        confirmationService,
                        service));

            } finally {
                // Drop only the uniquely named test database.
                client.getDatabase(databaseName).drop();

                SecurityContextHolder.clearContext();
            }
        }
    }

    private Booking seedBooking(
            TestContext context,
            BookingStatus status,
            Instant paymentDueAt) {

        Booking booking = new Booking();

        Instant acceptedAt = paymentDueAt.minusSeconds(900);

        Instant slotStart = NOW.plus(Duration.ofDays(2));

        booking.setRef("BK-IT-" + UUID.randomUUID());
        booking.setCustomerId(CUSTOMER_ID);
        booking.setProviderId(PROVIDER_ID);
        booking.setListingId("listing-001");

        booking.setSlotStart(slotStart);
        booking.setSlotEnd(
                slotStart.plus(Duration.ofHours(1)));

        booking.setDurationMinutes(60);
        booking.setStatus(status);
        booking.setCreatedAt(acceptedAt.minusSeconds(60));
        booking.setUpdatedAt(acceptedAt);
        booking.setAcceptedAt(acceptedAt);
        booking.setPaymentDueAt(paymentDueAt);

        booking.setPriceSnapshot(
                new BookingPriceSnapshot(
                        "Home Cleaning",
                        "category-001",
                        "FIXED",
                        AMOUNT,
                        List.of(),
                        AMOUNT,
                        CURRENCY,
                        COMMISSION_RATE,
                        60,
                        acceptedAt.minusSeconds(60)));

        return context.template().insert(booking);
    }

    private void seedPayment(
            TestContext context,
            String bookingId,
            BigDecimal amount) {

        Payment payment = new Payment();

        payment.setId(PAYMENT_ID);
        payment.setBookingId(bookingId);
        payment.setCustomerId(CUSTOMER_ID);

        payment.setAmount(amount);
        payment.setCurrency(CURRENCY);
        payment.setCommissionRate(COMMISSION_RATE);

        payment.setCommissionAmount(
                amount.multiply(COMMISSION_RATE)
                        .setScale(
                                2,
                                java.math.RoundingMode.HALF_UP));

        payment.setRefundedAmount(
                new BigDecimal("0.00"));

        payment.setStatus(PaymentStatus.INITIATED);
        payment.setGatewayRef(SESSION_ID);

        payment.setCreatedAt(NOW.minusSeconds(60));
        payment.setUpdatedAt(NOW.minusSeconds(60));

        context.template().insert(payment);
    }

    private void seedEvent(
            TestContext context,
            String eventId) {

        context.template().insert(
                new StripeWebhookEvent(
                        eventId,
                        "checkout.session.completed"));
    }

    private boolean capture(
            StripeWebhookTransactionService service,
            String eventId) {

        return service.captureVerifiedPayment(
                eventId,
                PAYMENT_ID,
                SESSION_ID,
                INTENT_ID,
                AMOUNT,
                CURRENCY);
    }

    private Payment readPayment(TestContext context) {
        Payment payment = context.template().findById(
                PAYMENT_ID, Payment.class);

        assertNotNull(payment);

        return payment;
    }

    private Booking readBooking(
            TestContext context,
            String bookingId) {

        return context.bookingRepository()
                .findById(bookingId)
                .orElseThrow();
    }

    private StripeWebhookEvent readEvent(
            TestContext context,
            String eventId) {

        StripeWebhookEvent event = context.template().findById(
                eventId, StripeWebhookEvent.class);

        assertNotNull(event);

        return event;
    }

    private void assertCommitted(
            TestContext context,
            String bookingId,
            String eventId) {

        Payment payment = readPayment(context);

        assertEquals(
                PaymentStatus.CAPTURED,
                payment.getStatus());

        assertEquals(
                INTENT_ID,
                payment.getGatewayPaymentIntentId());

        assertNotNull(payment.getPaidAt());

        Booking booking = readBooking(context, bookingId);

        assertEquals(
                BookingStatus.CONFIRMED,
                booking.getStatus());

        assertEquals(
                1L,
                booking.getTimeline().stream()
                        .filter(change -> PAYMENT_ID.equals(
                                change.getReference()))
                        .count());

        StripeWebhookEvent event = readEvent(context, eventId);

        assertEquals(
                StripeWebhookEvent.ProcessingStatus.PROCESSED,
                event.getStatus());

        assertEquals(PAYMENT_ID, event.getPaymentId());
        assertEquals(SESSION_ID, event.getCheckoutSessionId());

        assertNotNull(event.getProcessedAt());
    }

    private void assertRolledBack(
            TestContext context,
            String bookingId,
            BookingStatus expectedBookingStatus) {

        Payment payment = readPayment(context);

        assertEquals(
                PaymentStatus.INITIATED,
                payment.getStatus());

        assertNull(payment.getGatewayPaymentIntentId());
        assertNull(payment.getPaidAt());

        Booking booking = readBooking(context, bookingId);

        assertEquals(
                expectedBookingStatus,
                booking.getStatus());

        assertTrue(
                booking.getTimeline().stream()
                        .noneMatch(change -> PAYMENT_ID.equals(
                                change.getReference())));

        StripeWebhookEvent event = readEvent(context, EVENT_ID);

        assertEquals(
                StripeWebhookEvent.ProcessingStatus.RECEIVED,
                event.getStatus());

        assertNull(event.getPaymentId());
        assertNull(event.getCheckoutSessionId());
        assertNull(event.getProcessedAt());
    }

    private record TestContext(
            MongoTemplate template,
            BookingRepository bookingRepository,
            MongoTransactionManager transactionManager,
            BookingPaymentConfirmationService confirmationService,
            StripeWebhookTransactionService service) {
    }
}