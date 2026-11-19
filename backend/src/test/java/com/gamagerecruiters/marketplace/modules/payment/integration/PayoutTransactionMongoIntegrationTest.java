
package com.gamagerecruiters.marketplace.modules.payment.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionLookup;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PayoutAllocationRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PayoutRepository;
import com.gamagerecruiters.marketplace.modules.payment.service.BookingFinancialSnapshotService;
import com.gamagerecruiters.marketplace.modules.payment.service.BookingPayoutAllocationCalculator;
import com.gamagerecruiters.marketplace.modules.payment.service.EarningsLedgerService;
import com.gamagerecruiters.marketplace.modules.payment.service.PayoutTransactionService;

/**
 * Verifies payout reservation rollback using a real MongoDB transaction.
 *
 * Runs against the isolated local wp07rs replica set, never the shared
 * application database.
 */
class PayoutTransactionMongoIntegrationTest {

    private static final String LOCAL_URI = "mongodb://127.0.0.1:27018/?replicaSet=wp07rs";

    @Test
    void insufficientEligibleEarningsRollsBackPersistedPayout() {

        // Use a unique database to isolate this test from other data.
        String databaseName = "wp07_payout_rollback_test_"
                + UUID.randomUUID().toString().replace("-", "");

        try (MongoClient client = MongoClients.create(LOCAL_URI)) {

            MongoDatabase database = client.getDatabase(databaseName);

            try {
                MongoTemplate template = new MongoTemplate(client, databaseName);

                // Collections must exist before the transaction starts.
                template.createCollection("provider_payouts");
                template.createCollection("payout_allocations");
                template.createCollection("earnings_ledger");

                MongoRepositoryFactory factory = new MongoRepositoryFactory(template);

                PayoutRepository payoutRepository = factory.getRepository(PayoutRepository.class);

                PayoutAllocationRepository allocationRepository = factory
                        .getRepository(PayoutAllocationRepository.class);

                EarningsLedgerRepository ledgerRepository = factory.getRepository(EarningsLedgerRepository.class);

                MongoTransactionManager transactionManager = new MongoTransactionManager(
                        template.getMongoDatabaseFactory());

                // Unrelated services are mocked; persistence and transaction
                // handling remain real.
                PayoutTransactionService service = new PayoutTransactionService(
                        payoutRepository,
                        allocationRepository,
                        ledgerRepository,
                        mock(EarningsLedgerService.class),
                        mock(BookingPayoutAllocationCalculator.class),
                        mock(BookingFinancialSnapshotService.class),
                        mock(DisputePayoutHoldRepository.class),
                        mock(BookingFacade.class),
                        mock(CompletionLookup.class),
                        transactionManager);

                Payout requested = new Payout(
                        "rollback-test-request",
                        "rollback-test-provider",
                        new BigDecimal("5000.00"),
                        "LKR");

                assertEquals(0L, payoutRepository.count());

                // No eligible ledger entries exist for this provider.
                // The service saves the payout first, then rejects the
                // reservation. MongoDB must roll back that initial save.
                ApiException exception = assertThrows(
                        ApiException.class,
                        () -> service.reserve(requested));

                assertEquals(HttpStatus.CONFLICT, exception.getStatus());
                assertEquals(
                        "INSUFFICIENT_ELIGIBLE_EARNINGS",
                        exception.getErrorCode());

                // Verify the payout write was rolled back in MongoDB.
                assertEquals(0L, payoutRepository.count());
                assertFalse(
                        payoutRepository.findByRequestKey(
                                "rollback-test-request").isPresent());

                // No allocation or ledger writes should remain.
                assertEquals(0L, allocationRepository.count());
                assertEquals(0L, ledgerRepository.count());

            } finally {
                // Remove only this test's uniquely named database.
                database.drop();
            }
        }
    }
}
