package com.gamagerecruiters.marketplace.modules.customer.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerFavourite;
import com.gamagerecruiters.marketplace.modules.customer.domain.FavouriteType;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerFavouritePageResponse;
import com.gamagerecruiters.marketplace.modules.customer.initializer.CustomerFavouriteIndexInitializer;
import com.gamagerecruiters.marketplace.modules.customer.repository.CustomerFavouriteRepository;
import com.gamagerecruiters.marketplace.modules.customer.service.CustomerFavouriteService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.mongodb.client.MongoCollection;

@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
public class CustomerFavouriteMongoIntegrationTest {

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
    private CustomerFavouriteService customerFavouriteService;

    @Autowired
    private CustomerFavouriteRepository customerFavouriteRepository;

    @Autowired
    private CustomerFavouriteIndexInitializer customerFavouriteIndexInitializer;

    @Autowired
    private ProviderProfileRepository providerProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setupCleanDatabase() {
        mongoTemplate.remove(new Query(), CustomerFavourite.class);
        mongoTemplate.remove(new Query(), ProviderProfile.class);
        mongoTemplate.remove(new Query(), User.class);
        customerFavouriteIndexInitializer.initializeIndexes();
    }

    private ProviderProfile createTestProvider(String userId, String profileId, String name, String city) {
        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@example.lk");
        user.setName(name);
        user.setRole(Role.PROVIDER);
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        ProviderProfile profile = new ProviderProfile();
        profile.setId(profileId);
        profile.setUserId(userId);
        profile.setDisplayName(name);
        profile.setCity(city);
        profile.setDistrict(city);
        profile.setProviderType(ProviderType.INDIVIDUAL);
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        return providerProfileRepository.save(profile);
    }

    @Test
    @DisplayName("Unique index prevents duplicates on (customerId, type, targetId)")
    void testUniqueIndex_preventsDuplicates() {
        CustomerFavourite fav1 = new CustomerFavourite("cust-idx-1", FavouriteType.PROVIDER, "target-1", Instant.now());
        customerFavouriteRepository.save(fav1);

        CustomerFavourite fav2 = new CustomerFavourite("cust-idx-1", FavouriteType.PROVIDER, "target-1", Instant.now());
        assertThrows(DuplicateKeyException.class, () -> customerFavouriteRepository.save(fav2));
    }

    @Test
    @DisplayName("Concurrent double add of same favourite gives exactly one document")
    void testConcurrentDoubleAdd_givesOneDocument() throws Exception {
        createTestProvider("u-prov-conc", "p-prov-conc", "Nuwan Perera", "Colombo");

        String customerId = "cust-concurrent-1";
        String targetId = "p-prov-conc";

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    customerFavouriteService.addFavourite(customerId, FavouriteType.PROVIDER, targetId);
                    successCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Both calls must complete without uncaught error (idempotent duplicate key handling)
        assertEquals(threadCount, successCount.get(), "Both concurrent add calls should succeed idempotently");

        // Exactly one document must exist in database
        long count = customerFavouriteRepository.countByCustomerIdAndType(customerId, FavouriteType.PROVIDER);
        assertEquals(1, count, "Exactly one favourite record should exist in the database");
    }

    @Test
    @DisplayName("Remove favourite is idempotent")
    void testRemoveFavourite_isIdempotent() {
        createTestProvider("u-prov-rem", "p-prov-rem", "Priyani De Silva", "Dehiwala");

        String customerId = "cust-rem-1";
        String targetId = "p-prov-rem";

        customerFavouriteService.addFavourite(customerId, FavouriteType.PROVIDER, targetId);
        assertTrue(customerFavouriteRepository.existsByCustomerIdAndTypeAndTargetId(customerId, FavouriteType.PROVIDER, targetId));

        // First remove deletes it
        customerFavouriteService.removeFavourite(customerId, FavouriteType.PROVIDER, targetId);
        assertFalse(customerFavouriteRepository.existsByCustomerIdAndTypeAndTargetId(customerId, FavouriteType.PROVIDER, targetId));

        // Second remove is a harmless no-op
        customerFavouriteService.removeFavourite(customerId, FavouriteType.PROVIDER, targetId);
        assertFalse(customerFavouriteRepository.existsByCustomerIdAndTypeAndTargetId(customerId, FavouriteType.PROVIDER, targetId));
    }

    @Test
    @DisplayName("Pagination returns favourites in newest-first order")
    void testPagination_orderNewestFirst() {
        createTestProvider("u-p1", "p-1", "Provider One", "Colombo");
        createTestProvider("u-p2", "p-2", "Provider Two", "Kandy");
        createTestProvider("u-p3", "p-3", "Provider Three", "Galle");

        String customerId = "cust-page-1";
        Instant baseTime = Instant.now();

        CustomerFavourite f1 = new CustomerFavourite(customerId, FavouriteType.PROVIDER, "p-1", baseTime.minusSeconds(300));
        CustomerFavourite f2 = new CustomerFavourite(customerId, FavouriteType.PROVIDER, "p-2", baseTime.minusSeconds(200));
        CustomerFavourite f3 = new CustomerFavourite(customerId, FavouriteType.PROVIDER, "p-3", baseTime.minusSeconds(100));

        customerFavouriteRepository.save(f1);
        customerFavouriteRepository.save(f2);
        customerFavouriteRepository.save(f3);

        // Page 0, size 2 -> should return p-3 (newest) and p-2
        CustomerFavouritePageResponse page0 = customerFavouriteService.getFavourites(customerId, FavouriteType.PROVIDER, 0, 2);
        assertEquals(2, page0.content().size());
        assertEquals("p-3", page0.content().get(0).targetId());
        assertEquals("p-2", page0.content().get(1).targetId());
        assertEquals(3, page0.totalElements());
        assertEquals(2, page0.totalPages());

        // Page 1, size 2 -> should return p-1 (oldest)
        CustomerFavouritePageResponse page1 = customerFavouriteService.getFavourites(customerId, FavouriteType.PROVIDER, 1, 2);
        assertEquals(1, page1.content().size());
        assertEquals("p-1", page1.content().get(0).targetId());
    }
}
