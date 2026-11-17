
package com.gamagerecruiters.marketplace.modules.payment.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * Integration tests for customer-scoped payment history queries.
 *
 * <p>
 * Uses a uniquely named local MongoDB database to verify the real
 * Spring Data MongoDB repository behavior without accessing Atlas
 * or any shared application database.
 * </p>
 *
 * <p>
 * The temporary database is dropped after each test, including
 * when an assertion fails.
 * </p>
 */
class PaymentCustomerHistoryMongoIntegrationTest {

    private static final String LOCAL_URI = "mongodb://127.0.0.1:27017";

    private static final String CUSTOMER_A = "customer-a";
    private static final String CUSTOMER_B = "customer-b";

    private static final Sort NEWEST_FIRST = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    /**
     * Ensures the MongoDB query returns only records belonging
     * to the requested customer.
     */
    @Test
    void customerHistoryDoesNotExposeOtherCustomersPayments() {
        withRepository(repository -> {
            repository.save(payment(
                    "payment-a1",
                    CUSTOMER_A,
                    "2026-11-10T10:00:00Z"));

            repository.save(payment(
                    "payment-a2",
                    CUSTOMER_A,
                    "2026-11-11T10:00:00Z"));

            repository.save(payment(
                    "payment-b1",
                    CUSTOMER_B,
                    "2026-11-12T10:00:00Z"));

            Page<Payment> result = repository.findByCustomerId(
                    CUSTOMER_A,
                    PageRequest.of(0, 10, NEWEST_FIRST));

            assertEquals(2, result.getTotalElements());
            assertEquals(2, result.getContent().size());

            assertTrue(result.getContent().stream()
                    .allMatch(p -> CUSTOMER_A.equals(p.getCustomerId())));

            assertFalse(result.getContent().stream()
                    .anyMatch(p -> CUSTOMER_B.equals(p.getCustomerId())));
        });
    }

    /**
     * Verifies descending creation-time ordering and the
     * descending payment ID tie-breaker for equal timestamps.
     */
    @Test
    void customerHistoryUsesDeterministicNewestFirstSorting() {
        withRepository(repository -> {
            repository.save(payment(
                    "payment-001",
                    CUSTOMER_A,
                    "2026-11-10T10:00:00Z"));

            repository.save(payment(
                    "payment-002",
                    CUSTOMER_A,
                    "2026-11-12T10:00:00Z"));

            repository.save(payment(
                    "payment-003",
                    CUSTOMER_A,
                    "2026-11-12T10:00:00Z"));

            Page<Payment> result = repository.findByCustomerId(
                    CUSTOMER_A,
                    PageRequest.of(0, 10, NEWEST_FIRST));

            List<String> ids = result.getContent()
                    .stream()
                    .map(Payment::getId)
                    .toList();

            assertEquals(
                    List.of(
                            "payment-003",
                            "payment-002",
                            "payment-001"),
                    ids);
        });
    }

    /**
     * Verifies actual MongoDB skip/limit pagination and
     * the total count for the selected customer.
     */
    @Test
    void customerHistoryReturnsCorrectPaginationMetadata() {
        withRepository(repository -> {
            for (int i = 1; i <= 5; i++) {
                repository.save(payment(
                        "payment-a" + i,
                        CUSTOMER_A,
                        "2026-11-1" + i + "T10:00:00Z"));
            }

            repository.save(payment(
                    "payment-b1",
                    CUSTOMER_B,
                    "2026-11-16T10:00:00Z"));

            Page<Payment> firstPage = repository.findByCustomerId(
                    CUSTOMER_A,
                    PageRequest.of(0, 2, NEWEST_FIRST));

            Page<Payment> secondPage = repository.findByCustomerId(
                    CUSTOMER_A,
                    PageRequest.of(1, 2, NEWEST_FIRST));

            Page<Payment> thirdPage = repository.findByCustomerId(
                    CUSTOMER_A,
                    PageRequest.of(2, 2, NEWEST_FIRST));

            assertEquals(5, firstPage.getTotalElements());
            assertEquals(3, firstPage.getTotalPages());
            assertEquals(2, firstPage.getContent().size());

            assertEquals(
                    List.of("payment-a5", "payment-a4"),
                    paymentIds(firstPage));

            assertEquals(
                    List.of("payment-a3", "payment-a2"),
                    paymentIds(secondPage));

            assertEquals(
                    List.of("payment-a1"),
                    paymentIds(thirdPage));

            assertEquals(5, secondPage.getTotalElements());
            assertEquals(5, thirdPage.getTotalElements());
        });
    }

    /**
     * Legacy payment records without a customerId must not
     * accidentally appear in customer-scoped history.
     */
    @Test
    void customerHistoryExcludesLegacyPaymentsWithoutCustomerId() {
        withRepository(repository -> {
            repository.save(payment(
                    "payment-current",
                    CUSTOMER_A,
                    "2026-11-12T10:00:00Z"));

            repository.save(payment(
                    "payment-legacy",
                    null,
                    "2026-11-11T10:00:00Z"));

            Page<Payment> result = repository.findByCustomerId(
                    CUSTOMER_A,
                    PageRequest.of(0, 10, NEWEST_FIRST));

            assertEquals(1, result.getTotalElements());
            assertEquals(
                    List.of("payment-current"),
                    paymentIds(result));
        });
    }

    /**
     * Creates an isolated MongoDB repository for a single test.
     *
     * <p>
     * Only the database created by this method is dropped.
     * No application or shared test database is modified.
     * </p>
     */
    private void withRepository(RepositoryTestAction action) {
        String databaseName = "wp07_customer_history_test_"
                + UUID.randomUUID().toString().replace("-", "");

        try (MongoClient client = MongoClients.create(LOCAL_URI)) {
            MongoDatabase database = client.getDatabase(databaseName);

            try {
                MongoTemplate template = new MongoTemplate(
                        client,
                        databaseName);

                MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(template);

                PaymentRepository repository = repositoryFactory.getRepository(PaymentRepository.class);

                action.execute(repository);

            } finally {
                // Drop only this test's uniquely named local database.
                database.drop();
            }
        }
    }

    /**
     * Creates a valid payment document with a customer ownership
     * snapshot for repository-level integration testing.
     */
    private Payment payment(
            String paymentId,
            String customerId,
            String createdAt) {

        Payment payment = new Payment();

        payment.setId(paymentId);
        payment.setBookingId("booking-" + paymentId);
        payment.setCustomerId(customerId);

        payment.setAmount(new BigDecimal("2500.00"));
        payment.setRefundedAmount(BigDecimal.ZERO);
        payment.setCurrency("LKR");
        payment.setStatus(PaymentStatus.INITIATED);

        payment.setCreatedAt(Instant.parse(createdAt));
        payment.setUpdatedAt(Instant.parse(createdAt));

        return payment;
    }

    /**
     * Extracts payment IDs while preserving MongoDB query order.
     */
    private List<String> paymentIds(Page<Payment> page) {
        return page.getContent()
                .stream()
                .map(Payment::getId)
                .toList();
    }

    @FunctionalInterface
    private interface RepositoryTestAction {
        void execute(PaymentRepository repository);
    }
}
