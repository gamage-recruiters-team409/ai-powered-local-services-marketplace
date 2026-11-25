package com.gamagerecruiters.marketplace.modules.listing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.catalog.domain.Category;
import com.gamagerecruiters.marketplace.modules.listing.domain.AddOnItem;
import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ServiceListing;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.initializer.ListingIndexInitializer;
import com.gamagerecruiters.marketplace.modules.listing.repository.ListingRepository;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

/**
 * Real MongoDB (Testcontainers) integration tests verifying:
 * 1. Fresh database collection & all 4 indexes (including location_2dsphere).
 * 2. GeoJsonPoint, BigDecimal pricing, AddOnItem persistence round-trip.
 * 3. Geospatial near queries, compound repository queries, and category blocking lookup.
 * 4. @Version optimistic locking protection.
 *
 * <p>Requires Docker. Skip explicitly with -DSKIP_DOCKER_TESTS=true.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class ListingMongoIntegrationTest {

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
    private ListingRepository listingRepository;

    @Autowired
    private ListingService listingService;

    @Autowired
    private ListingIndexInitializer indexInitializer;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanData() {
        mongoTemplate.remove(new Query(), ServiceListing.class);
        mongoTemplate.remove(new Query(), Category.class);
        indexInitializer.initializeIndexes();
    }

    private ServiceListing sampleListing(
            String providerId,
            String categoryId,
            ListingStatus status,
            double longitude,
            double latitude
    ) {
        Instant now = Instant.now();
        ServiceListing listing = new ServiceListing();
        listing.setProviderId(providerId);
        listing.setCategoryId(categoryId);
        listing.setTitle("Residential Wiring & Panel Inspection");
        listing.setDescription("Complete home electrical fault check, breaker panel test, and safety report.");
        listing.setPricingModel(PricingModel.FIXED);
        listing.setBasePrice(new BigDecimal("3500.00"));
        listing.setDurationMin(90);
        listing.setExecutionModel(ExecutionModel.ON_SITE);
        listing.setDistrict("Colombo");
        listing.setBaseCity("Dehiwala");
        listing.setLocation(new GeoJsonPoint(longitude, latitude));
        listing.setCoverageRadiusKm(15);
        listing.setServiceAreas(List.of("Dehiwala", "Mount Lavinia"));
        listing.setImages(List.of("https://cdn.example.com/listings/electrical-1.jpg"));
        listing.setCoverImageUrl("https://cdn.example.com/listings/electrical-1.jpg");
        listing.setAddOns(List.of(new AddOnItem("addon-1", "Same-day emergency check",
                new BigDecimal("1000.00"), 30, true)));
        listing.setStatus(status);
        listing.setCreatedAt(now);
        listing.setUpdatedAt(now);
        return listing;
    }

    @Test
    @DisplayName("Real MongoDB, fresh database: service_listings collection and all 4 indexes are created")
    void freshDatabaseCreatesCollectionAndIndexes() {
        mongoTemplate.dropCollection(ServiceListing.class);
        assertThat(mongoTemplate.collectionExists("service_listings")).isFalse();

        indexInitializer.initializeIndexes();

        assertThat(mongoTemplate.collectionExists("service_listings")).isTrue();

        Document providerStatus = findIndex("providerId_1_status_1");
        Document categoryStatus = findIndex("categoryId_1_status_1");
        Document statusCreated = findIndex("status_1_createdAt_1");
        Document location2dsphere = findIndex("location_2dsphere");

        assertThat(new ArrayList<>(providerStatus.get("key", Document.class).keySet()))
                .containsExactly("providerId", "status");
        assertThat(providerStatus.getBoolean("unique", false)).isFalse();

        assertThat(new ArrayList<>(categoryStatus.get("key", Document.class).keySet()))
                .containsExactly("categoryId", "status");
        assertThat(new ArrayList<>(statusCreated.get("key", Document.class).keySet()))
                .containsExactly("status", "createdAt");

        assertThat(location2dsphere.get("key", Document.class).getString("location")).isEqualTo("2dsphere");
        assertThat(location2dsphere.getBoolean("unique", false)).isFalse();
        assertThat(location2dsphere.getBoolean("sparse", false)).isFalse();
        assertThat(location2dsphere.containsKey("partialFilterExpression")).isFalse();
    }

    @Test
    @DisplayName("Real MongoDB: persists ServiceListing with GeoJsonPoint and queries via 2dsphere near search")
    void persistsAndQueriesGeospatialListings() {
        ServiceListing colomboActive = listingRepository.save(
                sampleListing("provider-1", "cat-elec", ListingStatus.ACTIVE, 79.8612, 6.9271));
        listingRepository.save(
                sampleListing("provider-2", "cat-elec", ListingStatus.PAUSED, 79.8650, 6.9200));
        listingRepository.save(
                sampleListing("provider-3", "cat-elec", ListingStatus.ACTIVE, 80.6337, 7.2906)); // Kandy (~95km away)

        List<ServiceListing> nearColombo = listingRepository.findByStatusAndLocationNear(
                ListingStatus.ACTIVE,
                new Point(79.8600, 6.9250),
                new Distance(15, Metrics.KILOMETERS));

        assertThat(nearColombo).hasSize(1);
        assertThat(nearColombo.get(0).getId()).isEqualTo(colomboActive.getId());
        assertThat(nearColombo.get(0).getBasePrice()).isEqualByComparingTo("3500.00");
        assertThat(nearColombo.get(0).getAddOns()).hasSize(1);
        assertThat(nearColombo.get(0).getLocation().getX()).isEqualTo(79.8612);
        assertThat(nearColombo.get(0).getLocation().getY()).isEqualTo(6.9271);
    }

    @Test
    @DisplayName("Real MongoDB: supports provider queries, moderation queue, category blocking check, and optimistic locking")
    void repositoryQueriesAndOptimisticLockingWork() {
        ServiceListing active = listingRepository.save(
                sampleListing("provider-10", "cat-plumb", ListingStatus.ACTIVE, 79.8612, 6.9271));
        ServiceListing flagged = listingRepository.save(
                sampleListing("provider-10", "cat-clean", ListingStatus.FLAGGED, 79.8612, 6.9271));
        listingRepository.save(
                sampleListing("provider-10", "cat-draft", ListingStatus.DRAFT, 79.8612, 6.9271));

        assertThat(listingRepository.existsByCategoryIdAndStatusIn(
                "cat-plumb", ListingStatus.CATEGORY_BLOCKING_STATUSES)).isTrue();
        assertThat(listingRepository.existsByCategoryIdAndStatusIn(
                "cat-draft", ListingStatus.CATEGORY_BLOCKING_STATUSES)).isFalse();

        Page<ServiceListing> queue = listingRepository.findByStatusOrderByCreatedAtAsc(
                ListingStatus.FLAGGED, PageRequest.of(0, 10));
        assertThat(queue.getContent()).extracting(ServiceListing::getId).containsExactly(flagged.getId());

        ServiceListing copyA = listingRepository.findById(active.getId()).orElseThrow();
        ServiceListing copyB = listingRepository.findById(active.getId()).orElseThrow();

        copyA.setStatus(ListingStatus.PAUSED);
        listingRepository.save(copyA);

        copyB.setTitle("Concurrent stale edit title update");
        assertThatThrownBy(() -> listingRepository.save(copyB))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("Real MongoDB: public search and provider profiles expose ACTIVE listings only")
    void publicDiscoveryExposesActiveListingsOnly() {
        Category category = mongoTemplate.insert(
                new Category("Electrical", "public-discovery-electrical", null, new BigDecimal("0.10")));
        ServiceListing active = listingRepository.save(sampleListing(
                "public-provider", category.getId(), ListingStatus.ACTIVE, 79.8612, 6.9271));
        listingRepository.save(sampleListing(
                "public-provider", category.getId(), ListingStatus.PAUSED, 79.8612, 6.9271));
        listingRepository.save(sampleListing(
                "public-provider", category.getId(), ListingStatus.DRAFT, 79.8612, 6.9271));
        listingRepository.save(sampleListing(
                "public-provider", category.getId(), ListingStatus.REJECTED, 79.8612, 6.9271));

        Page<ListingResponse> searchPage = listingService.searchPublicListings(
                null, null, null, null, Set.of("public-provider"), PageRequest.of(0, 10));

        assertThat(searchPage.getTotalElements()).isEqualTo(1);
        assertThat(searchPage.getContent()).extracting(ListingResponse::id).containsExactly(active.getId());
        assertThat(listingService.getPublicListingsByProviderId("public-provider"))
                .extracting(ListingResponse::id)
                .containsExactly(active.getId());
    }

    private Document findIndex(String indexName) {
        for (Document index : mongoTemplate.getCollection("service_listings").listIndexes()) {
            if (indexName.equals(index.getString("name"))) {
                return index;
            }
        }
        throw new AssertionError("Index " + indexName + " not found on service_listings");
    }
}
