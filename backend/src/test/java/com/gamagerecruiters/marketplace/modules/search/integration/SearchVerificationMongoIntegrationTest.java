package com.gamagerecruiters.marketplace.modules.search.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.catalog.domain.Category;
import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ServiceListing;
import com.gamagerecruiters.marketplace.modules.listing.repository.ListingRepository;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.search.dto.PublicProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchRequest;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchResponse;
import com.gamagerecruiters.marketplace.modules.search.service.PublicProviderProfileService;
import com.gamagerecruiters.marketplace.modules.search.service.SearchService;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class SearchVerificationMongoIntegrationTest {

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
    private MongoTemplate mongoTemplate;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private ProviderVerificationRepository verificationRepository;

    @Autowired
    private SearchService searchService;

    @Autowired
    private PublicProviderProfileService publicProviderProfileService;

    @BeforeEach
    void cleanData() {
        mongoTemplate.remove(new Query(), ProviderVerification.class);
        mongoTemplate.remove(new Query(), ServiceListing.class);
        mongoTemplate.remove(new Query(), ProviderProfile.class);
        mongoTemplate.remove(new Query(), Category.class);
    }

    @Test
    @DisplayName("Real MongoDB: search and public profiles resolve verification by profile id with distinct user ids")
    void verificationUsesProfileIdWithDistinctUserId() {
        Category category = mongoTemplate.insert(new Category(
                "Electrical", "search-verification-electrical", null, new BigDecimal("0.10")));
        ProviderProfile profile = new ProviderProfile();
        profile.setId("profile-doc-1");
        profile.setUserId("user-1");
        profile.setDisplayName("Ada Electrical");
        profile.setDistrict("Colombo");
        profile.setCity("Dehiwala");
        profile.setCategoryIds(List.of(category.getId()));
        mongoTemplate.insert(profile);

        ProviderVerification verification = new ProviderVerification();
        verification.setProviderId("profile-doc-1");
        verification.setStatus(ProviderVerificationStatus.APPROVED);
        verificationRepository.save(verification);

        listingRepository.save(sampleListing("user-1", category.getId()));

        SearchResponse response = searchService.search(
                new SearchRequest(null, null, null, null, true, "recommended", 0, 12, Set.of()));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).providerId()).isEqualTo("user-1");
        assertThat(response.results().get(0).profileId()).isEqualTo("profile-doc-1");
        assertThat(response.results().get(0).verified()).isTrue();

        PublicProviderProfileResponse profileResponse =
                publicProviderProfileService.getPublicProfile("user-1");

        assertThat(profileResponse.providerId()).isEqualTo("user-1");
        assertThat(profileResponse.profileId()).isEqualTo("profile-doc-1");
        assertThat(profileResponse.verified()).isTrue();
    }

    private static ServiceListing sampleListing(String providerId, String categoryId) {
        Instant now = Instant.now();
        ServiceListing listing = new ServiceListing();
        listing.setProviderId(providerId);
        listing.setCategoryId(categoryId);
        listing.setTitle("Residential Wiring");
        listing.setDescription("Certified home wiring service.");
        listing.setPricingModel(PricingModel.FIXED);
        listing.setBasePrice(new BigDecimal("3500.00"));
        listing.setDurationMin(90);
        listing.setExecutionModel(ExecutionModel.ON_SITE);
        listing.setDistrict("Colombo");
        listing.setBaseCity("Dehiwala");
        listing.setLocation(new GeoJsonPoint(79.8612, 6.9271));
        listing.setCoverageRadiusKm(15);
        listing.setServiceAreas(List.of("Dehiwala"));
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setCreatedAt(now);
        listing.setUpdatedAt(now);
        return listing;
    }
}
