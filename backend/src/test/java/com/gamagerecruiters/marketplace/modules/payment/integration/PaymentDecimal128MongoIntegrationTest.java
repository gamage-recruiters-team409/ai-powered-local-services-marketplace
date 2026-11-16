
package com.gamagerecruiters.marketplace.modules.payment.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.bson.BsonType;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * Verifies real MongoDB Decimal128 storage and BigDecimal round-trip precision.
 *
 * Uses a dedicated local test database, never the configured Atlas database.
 * Only the document created by this test is deleted during cleanup.
 */
class PaymentDecimal128MongoIntegrationTest {

    private static final String LOCAL_URI = "mongodb://127.0.0.1:27017";

    private static final String TEST_DATABASE = "wp07_payment_integration_test";

    @Test
    void monetaryFieldsAreStoredAsDecimal128AndPreservePrecision()
            throws Exception {

        try (MongoClient client = MongoClients.create(LOCAL_URI)) {

            MongoDatabase database = client.getDatabase(TEST_DATABASE);

            // Register MongoDB conversions before initializing the mapping context.
            // This prevents BigDecimal from being inspected as a regular Java entity.
            MongoMappingContext mappingContext = new MongoMappingContext();
            mappingContext.setSimpleTypeHolder(
                    MongoCustomConversions.create(
                            adapter -> {
                            }).getSimpleTypeHolder());
            mappingContext.afterPropertiesSet();

            MappingMongoConverter converter = new MappingMongoConverter(
                    new DefaultDbRefResolver(
                            new org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory(
                                    client, TEST_DATABASE)),
                    mappingContext);
            converter.afterPropertiesSet();

            MongoTemplate template = new MongoTemplate(
                    new org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory(
                            client, TEST_DATABASE),
                    converter);

            Payment payment = new Payment();
            payment.setId("decimal128-test-" + UUID.randomUUID());
            payment.setBookingId("booking-decimal128-test");
            payment.setAmount(new BigDecimal("12345.67"));
            payment.setRefundedAmount(new BigDecimal("125.25"));
            payment.setCommissionAmount(new BigDecimal("987.65"));
            payment.setCommissionRate(new BigDecimal("0.0750"));

            try {
                template.save(payment);

                Document stored = database.getCollection("payments")
                        .find(new Document("_id", payment.getId()))
                        .first();

                assertNotNull(stored, "Payment document must exist");

                // Verify actual BSON types in MongoDB.
                assertEquals(BsonType.DECIMAL128,
                        stored.toBsonDocument().get("amount").getBsonType());
                assertEquals(BsonType.DECIMAL128,
                        stored.toBsonDocument().get("refundedAmount").getBsonType());
                assertEquals(BsonType.DECIMAL128,
                        stored.toBsonDocument().get("commissionAmount").getBsonType());
                assertEquals(BsonType.DECIMAL128,
                        stored.toBsonDocument().get("commissionRate").getBsonType());

                // Verify BigDecimal values after reading from MongoDB.
                Payment restored = template.findById(
                        payment.getId(), Payment.class);

                assertNotNull(restored);
                assertEquals(payment.getAmount(), restored.getAmount());
                assertEquals(payment.getRefundedAmount(),
                        restored.getRefundedAmount());
                assertEquals(payment.getCommissionAmount(),
                        restored.getCommissionAmount());
                assertEquals(payment.getCommissionRate(),
                        restored.getCommissionRate());

            } finally {
                // Delete only this test's payment document.
                template.remove(
                        new org.springframework.data.mongodb.core.query.Query(
                                org.springframework.data.mongodb.core.query.Criteria
                                        .where("_id").is(payment.getId())),
                        Payment.class);
            }
        }
    }
}
