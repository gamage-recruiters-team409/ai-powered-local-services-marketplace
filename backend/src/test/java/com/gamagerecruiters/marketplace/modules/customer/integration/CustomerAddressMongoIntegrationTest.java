package com.gamagerecruiters.marketplace.modules.customer.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerAddress;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressResponse;
import com.gamagerecruiters.marketplace.modules.customer.initializer.CustomerIndexInitializer;
import com.gamagerecruiters.marketplace.modules.customer.repository.CustomerAddressRepository;
import com.gamagerecruiters.marketplace.modules.customer.service.CustomerService;
import com.mongodb.client.MongoCollection;

/**
 * Concurrency and real MongoDB integration tests verifying:
 * 1. Stored BSON field names ("isDefault", not "default").
 * 2. Independent index validation & coexistence with unrelated compound indexes.
 * 3. Single default address under concurrent setDefault / create requests.
 * 4. Atomic slot allocation under concurrent creates (limit 10, 25 simultaneous creates -> exactly 10 succeed, 15 get 409).
 * 5. Legacy no-slot address lazy backfill (8 legacy + 5 concurrent creates -> exactly 2 succeed).
 * 6. Slot reuse after deletion.
 *
 * Requires Docker. Skip with -DSKIP_DOCKER_TESTS=true.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
public class CustomerAddressMongoIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CustomerAddressMongoIntegrationTest.class);

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl();
        });
    }

    @BeforeAll
    static void startContainer() {
        if (!mongoDBContainer.isRunning()) {
            mongoDBContainer.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerAddressRepository customerAddressRepository;

    @Autowired
    private CustomerIndexInitializer customerIndexInitializer;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setupCleanDatabase() {
        mongoTemplate.remove(new Query(), CustomerAddress.class);
        customerIndexInitializer.initializeIndexes();
    }

    @Test
    @DisplayName("Verify real stored field name is 'isDefault' in MongoDB BSON")
    void verifyRealStoredFieldNameIsDefault() {
        Instant now = Instant.now();
        CustomerAddress address = new CustomerAddress(
                "cust-verify-field",
                "Home",
                "123 Galle Road",
                null,
                "Colombo 03",
                "Colombo",
                "00300",
                true,
                now,
                now
        );
        address.setSlot(1);
        customerAddressRepository.save(address);

        MongoCollection<Document> collection = mongoTemplate.getCollection("customer_addresses");
        Document rawDoc = collection.find(new Document("customerId", "cust-verify-field")).first();

        assertNotNull(rawDoc, "Saved customer address must be retrieved as raw BSON");
        log.info("Raw BSON document retrieved from MongoDB: {}", rawDoc.toJson());

        assertTrue(rawDoc.containsKey("isDefault"), "MongoDB BSON document MUST contain field 'isDefault'");
        assertFalse(rawDoc.containsKey("default"), "MongoDB BSON document must NOT contain field 'default'");
        assertEquals(true, rawDoc.getBoolean("isDefault"), "Stored 'isDefault' value must match true");
        assertEquals(1, rawDoc.getInteger("slot"), "Stored 'slot' value must match 1");
    }

    @Test
    @DisplayName("Verify listIndexes includes all 3 required indexes with exact specifications")
    void verifyRequiredIndexesAreCreated() {
        MongoCollection<Document> collection = mongoTemplate.getCollection("customer_addresses");
        List<Document> indexes = new ArrayList<>();
        collection.listIndexes().into(indexes);

        log.info("=== Current MongoDB Indexes for customer_addresses ===");
        for (Document idx : indexes) {
            log.info("Index: name={}, key={}, unique={}, partialFilter={}",
                    idx.getString("name"),
                    idx.get("key"),
                    idx.get("unique"),
                    idx.get("partialFilterExpression"));
        }

        assertThat(indexes).anySatisfy(idx -> {
            assertThat(idx.getString("name")).isEqualTo("customerId_1");
            assertThat(idx.get("unique")).isNull();
        });

        assertThat(indexes).anySatisfy(idx -> {
            assertThat(idx.getString("name")).isEqualTo("customerId_1_isDefault_1_unique_default");
            assertThat(idx.getBoolean("unique")).isTrue();
            assertThat(idx.get("partialFilterExpression", Document.class)).isEqualTo(new Document("isDefault", true));
        });

        assertThat(indexes).anySatisfy(idx -> {
            assertThat(idx.getString("name")).isEqualTo("customerId_1_slot_1_unique");
            assertThat(idx.getBoolean("unique")).isTrue();
            assertThat(idx.get("partialFilterExpression", Document.class)).isEqualTo(new Document("slot", new Document("$gt", 0)));
        });
    }

    @Test
    @DisplayName("Coexistence: Unrelated compound index {customerId: 1, createdAt: 1} does not fail initializer")
    void shouldTolerateUnrelatedCompoundIndex() {
        mongoTemplate.indexOps(CustomerAddress.class).ensureIndex(
                new Index().on("customerId", Sort.Direction.ASC).on("createdAt", Sort.Direction.ASC).named("customerId_1_createdAt_1")
        );

        // Re-run initializer
        customerIndexInitializer.initializeIndexes();

        MongoCollection<Document> collection = mongoTemplate.getCollection("customer_addresses");
        List<Document> indexNames = new ArrayList<>();
        collection.listIndexes().into(indexNames);

        assertThat(indexNames).anySatisfy(idx -> assertThat(idx.getString("name")).isEqualTo("customerId_1_createdAt_1"));
    }

    @Test
    @DisplayName("Partial unique index: A second default address inserted directly is rejected by the index")
    void secondDefaultInsertedDirectlyIsRejectedByUniqueIndex() {
        MongoCollection<Document> collection = mongoTemplate.getCollection("customer_addresses");
        String customerId = "cust-direct-insert-test";

        Document doc1 = new Document("customerId", customerId)
                .append("label", "Home")
                .append("addressLine1", "123 Main St")
                .append("city", "Colombo")
                .append("district", "Colombo")
                .append("isDefault", true)
                .append("slot", 1)
                .append("createdAt", Instant.now())
                .append("updatedAt", Instant.now());
        collection.insertOne(doc1);

        Document doc2 = new Document("customerId", customerId)
                .append("label", "Office")
                .append("addressLine1", "456 Office St")
                .append("city", "Colombo")
                .append("district", "Colombo")
                .append("isDefault", true)
                .append("slot", 2)
                .append("createdAt", Instant.now())
                .append("updatedAt", Instant.now());

        com.mongodb.MongoWriteException ex = assertThrows(
                com.mongodb.MongoWriteException.class,
                () -> collection.insertOne(doc2)
        );
        assertEquals(11000, ex.getError().getCode(), "Direct insert of second default address must be rejected by unique index (E11000)");
        assertTrue(ex.getMessage().contains("customerId_1_isDefault_1_unique_default"));
    }

    @Test
    @DisplayName("Failure injection: Interruption between unset and set never leaves two defaults, self-heals to exactly one")
    void failureInjectionBetweenUnsetAndSetNeverLeavesTwoDefaults() {
        String customerId = "cust-failure-injection";

        // Create 2 addresses: addr1 is default, addr2 is not
        CustomerAddressResponse addr1 = customerService.createAddress(
                customerId, new CustomerAddressRequest("Addr 1", "Street 1", null, "City", "Colombo", null, true)
        );
        CustomerAddressResponse addr2 = customerService.createAddress(
                customerId, new CustomerAddressRequest("Addr 2", "Street 2", null, "City", "Colombo", null, false)
        );

        assertEquals(1, countDefaultsInDb(customerId));

        // Inject simulated mid-switch failure: unsetting addr1 in the DB directly (as if interrupted before setting addr2)
        MongoCollection<Document> collection = mongoTemplate.getCollection("customer_addresses");
        collection.updateOne(
                new Document("_id", new org.bson.types.ObjectId(addr1.id())),
                new Document("$set", new Document("isDefault", false))
        );

        // At worst 0 defaults for a moment, NEVER 2
        int countBeforeHeal = countDefaultsInDb(customerId);
        assertTrue(countBeforeHeal <= 1, "Interrupted state must never have more than 1 default");

        // The self-healing read/write path automatically heals this on the next call:
        List<CustomerAddressResponse> addresses = customerService.getAddresses(customerId);
        int countAfterHeal = countDefaultsInDb(customerId);
        assertEquals(1, countAfterHeal, "Self-healing must restore/promote exactly one default address");
        assertTrue(addresses.stream().anyMatch(CustomerAddressResponse::isDefault));
    }

    @Test
    @DisplayName("Concurrency: Simultaneous setDefault and createAddress result in exactly 1 default in MongoDB")
    void shouldMaintainExactlyOneDefaultUnderConcurrentUpdates() throws Exception {
        String customerId = "cust-concurrency-default";
        List<CustomerAddressResponse> initial = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            CustomerAddressRequest req = new CustomerAddressRequest(
                    "Addr-" + i, "Street " + i, null, "City", "Colombo", null, false
            );
            initial.add(customerService.createAddress(customerId, req));
        }

        // Verify initial state has 1 default (the first created)
        assertEquals(1, countDefaultsInDb(customerId));

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i % initial.size();
            final String addrId = initial.get(index).id();
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    customerService.setDefaultAddress(customerId, addrId);
                } catch (Exception ignored) {
                    // 409 CONCURRENT_MODIFICATION or lost races are valid outcomes under high contention
                }
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        // Direct DB verification: EXACTLY 1 default address exists in MongoDB
        int finalDefaults = countDefaultsInDb(customerId);
        log.info("RESULT: concurrent set-default -> {} default document", finalDefaults);
        assertEquals(1, finalDefaults, "Database must contain exactly 1 default address after concurrent setDefault calls");
    }

    @Test
    @DisplayName("Concurrency: 25 simultaneous creates on empty customer with limit 10 -> exactly 10 succeed, 15 get 409")
    void shouldAtomicallyLimitConcurrentCreatesToMaxAddresses() throws Exception {
        String customerId = "cust-concurrency-slots";
        int totalRequests = 25;
        int maxAllowed = 10;

        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= totalRequests; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    CustomerAddressRequest req = new CustomerAddressRequest(
                            "Label " + index, "Address " + index, null, "City", "Colombo", null, false
                    );
                    customerService.createAddress(customerId, req);
                    successCount.incrementAndGet();
                } catch (ResourceConflictException ex) {
                    if ("MAX_ADDRESSES_REACHED".equals(ex.getErrorCode())) {
                        conflictCount.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (Exception ex) {
                    otherErrors.incrementAndGet();
                }
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));

        log.info("Concurrency Results: successes={}, conflicts (MAX_ADDRESSES_REACHED)={}, other errors={}",
                successCount.get(), conflictCount.get(), otherErrors.get());

        // Verify direct DB count
        long dbCount = customerAddressRepository.countByCustomerId(customerId);
        log.info("RESULT: 25 simultaneous creates, limit 10 -> {} created, {} MAX_ADDRESSES_REACHED, {} documents in collection",
                successCount.get(), conflictCount.get(), dbCount);

        assertEquals(maxAllowed, successCount.get(), "Exactly 10 creates must succeed");
        assertEquals(totalRequests - maxAllowed, conflictCount.get(), "Exactly 15 creates must fail with MAX_ADDRESSES_REACHED");
        assertEquals(0, otherErrors.get(), "No other errors or exceptions should occur");
        assertEquals(maxAllowed, dbCount, "Direct DB document count must equal 10");

        // Verify slot numbers in DB are 1..10
        List<CustomerAddress> saved = customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        List<Integer> slots = saved.stream().map(CustomerAddress::getSlot).sorted().toList();
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), slots, "Slots 1..10 must all be assigned and unique");
    }

    @Test
    @DisplayName("Legacy addresses: 8 legacy no-slot addresses + 5 concurrent creates -> exactly 2 succeed")
    void shouldLazilyBackfillAndEnforceLimitWithLegacyAddresses() throws Exception {
        String customerId = "cust-legacy-backfill";

        // Insert 8 legacy documents directly with slot = null
        Instant now = Instant.now();
        for (int i = 1; i <= 8; i++) {
            CustomerAddress legacy = new CustomerAddress(
                    customerId,
                    "Legacy " + i,
                    "Road " + i,
                    null,
                    "Colombo 07",
                    "Colombo",
                    "00700",
                    i == 1,
                    now.plusSeconds(i),
                    now.plusSeconds(i)
            );
            legacy.setSlot(null); // Explicit legacy address without slot
            customerAddressRepository.save(legacy);
        }

        assertEquals(8, customerAddressRepository.countByCustomerId(customerId));

        // 5 simultaneous creates
        int concurrentCreates = 5;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentCreates);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= concurrentCreates; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    CustomerAddressRequest req = new CustomerAddressRequest(
                            "New " + index, "New St " + index, null, "City", "Colombo", null, false
                    );
                    customerService.createAddress(customerId, req);
                    successCount.incrementAndGet();
                } catch (ResourceConflictException ex) {
                    if ("MAX_ADDRESSES_REACHED".equals(ex.getErrorCode())) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        log.info("RESULT: 8 legacy + 5 creates -> {} created", successCount.get());

        assertEquals(2, successCount.get(), "Exactly 2 of 5 concurrent creates must succeed to reach max 10");
        assertEquals(3, conflictCount.get(), "Exactly 3 must be rejected with MAX_ADDRESSES_REACHED");
        assertEquals(10, customerAddressRepository.countByCustomerId(customerId), "Total documents in DB must be 10");
    }

    @Test
    @DisplayName("Delete address frees slot and enables subsequent address creation")
    void shouldReuseFreedSlotAfterAddressDeletion() {
        String customerId = "cust-delete-slot";

        List<CustomerAddressResponse> created = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            CustomerAddressRequest req = new CustomerAddressRequest(
                    "Addr " + i, "Street " + i, null, "City", "Colombo", null, false
            );
            created.add(customerService.createAddress(customerId, req));
        }

        // 11th creation should fail
        assertThrows(ResourceConflictException.class, () -> customerService.createAddress(
                customerId, new CustomerAddressRequest("Addr 11", "Street 11", null, "City", "Colombo", null, false)
        ));

        // Delete address #5
        customerService.deleteAddress(customerId, created.get(4).id());
        assertEquals(9, customerAddressRepository.countByCustomerId(customerId));

        // Creation should now succeed and occupy the freed slot
        CustomerAddressResponse newAddr = customerService.createAddress(
                customerId, new CustomerAddressRequest("Addr Replaced", "Street New", null, "City", "Colombo", null, false)
        );
        assertNotNull(newAddr);
        assertEquals(10, customerAddressRepository.countByCustomerId(customerId));
    }

    @Test
    @DisplayName("Fresh DB: collection missing -> createCollection ensures collection exists and creates 3 required indexes")
    void shouldInitializeIndexesSafelyOnFreshDatabase() {
        if (mongoTemplate.collectionExists("customer_addresses")) {
            mongoTemplate.dropCollection("customer_addresses");
        }
        assertFalse(mongoTemplate.collectionExists("customer_addresses"), "Collection must not exist prior to fresh DB test");

        customerIndexInitializer.initializeIndexes();

        assertTrue(mongoTemplate.collectionExists("customer_addresses"), "Collection must exist after initialization");

        MongoCollection<Document> collection = mongoTemplate.getCollection("customer_addresses");
        List<Document> indexes = new ArrayList<>();
        collection.listIndexes().into(indexes);

        List<Document> nonIdIndexes = indexes.stream()
                .filter(idx -> !"_id_".equals(idx.getString("name")))
                .toList();

        for (Document idx : nonIdIndexes) {
            log.info("FRESH-DB INDEX: name={}, key={}, unique={}, partialFilter={}",
                    idx.getString("name"),
                    idx.get("key"),
                    idx.get("unique"),
                    idx.get("partialFilterExpression"));
        }

        assertEquals(3, nonIdIndexes.size(), "Exactly 3 custom indexes must be created on fresh database");

        assertThat(nonIdIndexes).anySatisfy(idx -> {
            assertThat(idx.getString("name")).isEqualTo("customerId_1");
            assertThat(idx.get("unique")).isNull();
        });

        assertThat(nonIdIndexes).anySatisfy(idx -> {
            assertThat(idx.getString("name")).isEqualTo("customerId_1_isDefault_1_unique_default");
            assertThat(idx.getBoolean("unique")).isTrue();
            assertThat(idx.get("partialFilterExpression", Document.class)).isEqualTo(new Document("isDefault", true));
        });

        assertThat(nonIdIndexes).anySatisfy(idx -> {
            assertThat(idx.getString("name")).isEqualTo("customerId_1_slot_1_unique");
            assertThat(idx.getBoolean("unique")).isTrue();
            assertThat(idx.get("partialFilterExpression", Document.class)).isEqualTo(new Document("slot", new Document("$gt", 0)));
        });
    }

    @Test
    @DisplayName("Idempotency: running initializeIndexes multiple times maintains exactly 3 indexes")
    void shouldBeIdempotentWhenInitializedMultipleTimes() {
        customerIndexInitializer.initializeIndexes();
        customerIndexInitializer.initializeIndexes();

        MongoCollection<Document> collection = mongoTemplate.getCollection("customer_addresses");
        List<Document> indexes = new ArrayList<>();
        collection.listIndexes().into(indexes);

        List<Document> nonIdIndexes = indexes.stream()
                .filter(idx -> !"_id_".equals(idx.getString("name")))
                .toList();
        assertEquals(3, nonIdIndexes.size(), "Multiple initializations must remain idempotent with exactly 3 indexes");
    }

    @Test
    @DisplayName("Concurrent startup: multiple threads initializing on fresh database all succeed")
    void shouldHandleConcurrentInitializationOnFreshDatabase() throws Exception {
        if (mongoTemplate.collectionExists("customer_addresses")) {
            mongoTemplate.dropCollection("customer_addresses");
        }
        assertFalse(mongoTemplate.collectionExists("customer_addresses"));

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    customerIndexInitializer.initializeIndexes();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        for (Future<?> f : futures) {
            f.get(); // ensures no exceptions were thrown
        }

        assertTrue(mongoTemplate.collectionExists("customer_addresses"));
        MongoCollection<Document> collection = mongoTemplate.getCollection("customer_addresses");
        List<Document> indexes = new ArrayList<>();
        collection.listIndexes().into(indexes);
        List<Document> nonIdIndexes = indexes.stream()
                .filter(idx -> !"_id_".equals(idx.getString("name")))
                .toList();
        assertEquals(3, nonIdIndexes.size(), "Concurrent startup must finish with exactly 3 indexes");
    }

    private int countDefaultsInDb(String customerId) {
        MongoCollection<Document> collection = mongoTemplate.getCollection("customer_addresses");
        return (int) collection.countDocuments(new Document("customerId", customerId).append("isDefault", true));
    }
}
