package com.gamagerecruiters.marketplace.modules.payment.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider.BookingPaymentQuote;
import com.gamagerecruiters.marketplace.modules.payment.initializer.PaymentIndexInitializer;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;
import com.gamagerecruiters.marketplace.modules.payment.service.BookingPaymentCreationService;
import com.gamagerecruiters.marketplace.modules.payment.service.CommissionCalculator;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentBookingAuthorizationService;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentCreationService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

/**
 * Verifies concurrent initial payment creation against real MongoDB.
 *
 * <p>Each execution uses an isolated temporary database. No application
 * database or existing financial records are modified.</p>
 */
class BookingPaymentCreationMongoIntegrationTest {

    private static final String LOCAL_URI =
            "mongodb://127.0.0.1:27017";

    private static final String BOOKING_ID = "booking-concurrent-001";

    private static final String CUSTOMER_ID = "customer-001";

    private static final int REQUEST_COUNT = 8;

    @Test
    void concurrentRequestsCreateExactlyOneInitialPayment()
            throws Exception {

        String databaseName = "wp07_payment_creation_test_"
                + UUID.randomUUID().toString().replace("-", "");

        try (MongoClient client = MongoClients.create(LOCAL_URI)) {

            ExecutorService executor =
                    Executors.newFixedThreadPool(REQUEST_COUNT);

            CountDownLatch ready =
                    new CountDownLatch(REQUEST_COUNT);

            CountDownLatch start = new CountDownLatch(1);

            try {
                MongoTemplate template = new MongoTemplate(
                        new SimpleMongoClientDatabaseFactory(
                                client, databaseName));

                new PaymentIndexInitializer(template)
                        .initializeIndexes();

                PaymentRepository repository =
                        new MongoRepositoryFactory(template)
                                .getRepository(PaymentRepository.class);

                PaymentBookingAuthorizationService authorization =
                        mock(PaymentBookingAuthorizationService.class);

                Instant now = Instant.now();

                TrustedBookingPaymentQuoteProvider quoteProvider =
                        bookingId -> new BookingPaymentQuote(
                                new BigDecimal("10000.00"),
                                "LKR",
                                new BigDecimal("0.10"),
                                now.minusSeconds(60),
                                now.plusSeconds(840));

                PaymentCreationService creationService =
                        new PaymentCreationService(
                                new CommissionCalculator());

                // Synchronize all initial reads before allowing inserts.
                // This forces the concurrent creation conflict rather than
                // relying only on unpredictable thread scheduling.
                PaymentRepository synchronizedRepository =
                        mock(PaymentRepository.class);

                // Delegate persistence operations to the real MongoDB repository.
                when(synchronizedRepository.insert(any(Payment.class)))
                        .thenAnswer(invocation ->
                                repository.insert(
                                        invocation.getArgument(0, Payment.class)));

                when(synchronizedRepository.findById(anyString()))
                        .thenAnswer(invocation ->
                                repository.findById(
                                        invocation.getArgument(0, String.class)));

                CountDownLatch initialReads =
                        new CountDownLatch(REQUEST_COUNT);

                doAnswer(invocation -> {
                    List<Payment> existing =
                            repository.findByBookingId(BOOKING_ID);

                    initialReads.countDown();

                    if (!initialReads.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Concurrent payment reads timed out");
                    }

                    return existing;
                }).when(synchronizedRepository)
                        .findByBookingId(BOOKING_ID);

                List<Future<Payment>> results = new ArrayList<>();

                for (int i = 0; i < REQUEST_COUNT; i++) {

                    // Separate service instances demonstrate that
                    // protection does not depend on JVM-local locking.
                    BookingPaymentCreationService service =
                            new BookingPaymentCreationService(
                                    authorization,
                                    quoteProvider,
                                    creationService,
                                    synchronizedRepository);

                    results.add(executor.submit(() -> {
                        ready.countDown();

                        if (!start.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Concurrent payment start timed out");
                        }

                        return service.createOrGetPayment(
                                BOOKING_ID, CUSTOMER_ID);
                    }));
                }

                assertTrue(ready.await(30, TimeUnit.SECONDS));

                start.countDown();

                String expectedPaymentId = null;

                for (Future<Payment> future : results) {

                    Payment payment =
                            future.get(60, TimeUnit.SECONDS);

                    assertNotNull(payment.getId());

                    if (expectedPaymentId == null) {
                        expectedPaymentId = payment.getId();
                    }

                    assertEquals(
                            expectedPaymentId, payment.getId());

                    assertEquals(
                            BOOKING_ID, payment.getBookingId());

                    assertEquals(
                            CUSTOMER_ID, payment.getCustomerId());

                    assertEquals(
                            PaymentStatus.INITIATED,
                            payment.getStatus());

                    assertEquals(
                            0,
                            new BigDecimal("10000.00")
                                    .compareTo(payment.getAmount()));

                    assertEquals(
                            0,
                            new BigDecimal("1000.00")
                                    .compareTo(
                                            payment.getCommissionAmount()));
                }

                List<Payment> persisted =
                        repository.findByBookingId(BOOKING_ID);

                assertEquals(1, persisted.size());
                assertEquals(1L, repository.count());

                assertEquals(
                        expectedPaymentId,
                        persisted.get(0).getId());

                assertNull(persisted.get(0).getGatewayRef());

                assertNull(
                        persisted.get(0).getCheckoutReservationToken());

                // A later request must reuse the persisted payment.
                BookingPaymentCreationService retryService =
                        new BookingPaymentCreationService(
                                authorization,
                                quoteProvider,
                                creationService,
                                repository);

                Payment retry = retryService.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID);

                assertEquals(expectedPaymentId, retry.getId());
                assertEquals(1L, repository.count());

                verify(authorization, times(REQUEST_COUNT + 1))
                        .requirePaymentInitiationStatus(
                                BOOKING_ID, CUSTOMER_ID);

            } finally {
                start.countDown();
                executor.shutdownNow();

                if (!executor.awaitTermination(
                        60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Concurrent test workers did not terminate");
                }

                // Drop only the isolated database created by this test.
                client.getDatabase(databaseName).drop();
            }
        }
    }
}
