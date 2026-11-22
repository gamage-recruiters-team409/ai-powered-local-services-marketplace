package com.gamagerecruiters.marketplace.modules.provider.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.provider.crypto.ProviderPayoutEncryptionService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfileActivityStatus;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderPayoutDetailsRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.filestorage.ProfileImageStorageService;
import com.gamagerecruiters.marketplace.modules.provider.filestorage.StoredProfileImage;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.provider.service.ProviderActivityFacade;
import com.gamagerecruiters.marketplace.modules.provider.service.ProviderPayoutDetailsServiceImpl;
import com.gamagerecruiters.marketplace.modules.provider.service.ProviderProfileActivityServiceImpl;
import com.gamagerecruiters.marketplace.modules.provider.service.ProviderProfileServiceImpl;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationReverificationService;

/**
 * Real-MongoDB regression coverage: independent writes to one provider profile
 * cannot overwrite one another, and stale image operations cannot delete
 * a newer asset. Does not require changes in any other team's module.
 */
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class ProviderProfileConcurrentWritesIntegrationTest {

    private static final String USER_ID = "concurrent-provider-user";
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");
    private static MongoClient client;
    private static MongoTemplate template;

    private ProviderProfileRepository repository;
    private ProfileImageStorageService images;
    private ProviderProfileServiceImpl profileService;
    private ProviderPayoutDetailsServiceImpl payoutService;
    private ProviderProfileActivityServiceImpl activityService;

    @BeforeAll
    static void openDatabase() {
        MONGO.start();
        client = MongoClients.create(MONGO.getReplicaSetUrl());
        template = new MongoTemplate(client, "wp02_concurrent_writes_test");
    }

    @AfterAll
    static void closeDatabase() {
        if (client != null) {
            client.close();
        }
        MONGO.stop();
    }

    @BeforeEach
    void setup() {
        template.dropCollection(ProviderProfile.class);
        ProviderProfile initial = new ProviderProfile();
        initial.setUserId(USER_ID);
        initial.setDisplayName("Original provider");
        initial.setBio("Original bio");
        initial.setDistrict("Colombo");
        initial.setCity("Colombo");
        initial.setServiceAreaDistricts(List.of("Colombo"));
        initial.setActivityStatus(ProviderProfileActivityStatus.ACTIVE);
        initial.setProfileImageUrl("https://example.com/old.jpg");
        initial.setProfileImagePublicId("old-id");
        template.save(initial);

        repository = mock(ProviderProfileRepository.class);
        when(repository.findByUserId(USER_ID)).thenAnswer(invocation ->
                Optional.ofNullable(template.findOne(
                        Query.query(Criteria.where("userId").is(USER_ID)),
                        ProviderProfile.class)));

        images = mock(ProfileImageStorageService.class);
        CategoryService categories = mock(CategoryService.class);
        DistrictCatalog districts = mock(DistrictCatalog.class);
        ProviderVerificationReverificationService reverify =
                mock(ProviderVerificationReverificationService.class);
        ProviderVerificationFacade verification = mock(ProviderVerificationFacade.class);
        ProviderActivityFacade activityFacade = mock(ProviderActivityFacade.class);
        ProviderPayoutEncryptionService encryption =
                mock(ProviderPayoutEncryptionService.class);
        when(verification.isApprovedProvider(USER_ID)).thenReturn(true);
        when(encryption.encrypt("123456789012")).thenReturn("v1:encrypted");

        profileService = new ProviderProfileServiceImpl(repository, categories,
                districts, reverify, images, template);
        payoutService = new ProviderPayoutDetailsServiceImpl(repository,
                encryption, template);
        activityService = new ProviderProfileActivityServiceImpl(repository,
                template, verification, activityFacade);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void concurrentPayoutPauseAndProfileUpdatePreserveAllFields() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> payout = executor.submit(() -> {
                authenticate();
                ready.countDown();
                await(start);
                UpdateProviderPayoutDetailsRequest request = new UpdateProviderPayoutDetailsRequest();
                request.setBankName("Commercial Bank");
                request.setAccountHolderName("Test Provider");
                request.setAccountNumber("123456789012");
                payoutService.updateMyPayoutDetails(request);
                SecurityContextHolder.clearContext();
            });
            Future<?> pause = executor.submit(() -> {
                authenticate();
                ready.countDown();
                await(start);
                activityService.pauseMyProfile();
                SecurityContextHolder.clearContext();
            });
            Future<?> edit = executor.submit(() -> {
                authenticate();
                ready.countDown();
                await(start);
                UpdateProviderProfileRequest request = new UpdateProviderProfileRequest();
                request.setBio("Updated bio");
                profileService.updateMyProfile(request);
                SecurityContextHolder.clearContext();
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            payout.get(30, TimeUnit.SECONDS);
            pause.get(30, TimeUnit.SECONDS);
            edit.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        ProviderProfile result = persisted();
        assertEquals(ProviderProfileActivityStatus.PAUSED, result.getActivityStatus());
        assertEquals("Updated bio", result.getBio());
        assertEquals("Commercial Bank", result.getPayoutBankName());
        assertEquals("Test Provider", result.getPayoutAccountHolderName());
        assertEquals("v1:encrypted", result.getPayoutAccountNumberEncrypted());
        assertEquals("old-id", result.getProfileImagePublicId());
        assertEquals(List.of("Colombo"), result.getServiceAreaDistricts());
    }

    @Test
    void staleImageUploadCannotRestoreImageRemovedByAnotherRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "new.png", "image/png", new byte[] {1, 2, 3});
        CountDownLatch uploadStarted = new CountDownLatch(1);
        CountDownLatch continueUpload = new CountDownLatch(1);
        when(images.upload(any(String.class), eq(file))).thenAnswer(invocation -> {
            uploadStarted.countDown();
            await(continueUpload);
            return new StoredProfileImage("new-id", "https://example.com/new.jpg");
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> uploading = executor.submit(() -> {
                authenticate();
                try {
                    return profileService.uploadMyProfileImage(file);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });

            assertTrue(uploadStarted.await(10, TimeUnit.SECONDS));
            authenticate();
            profileService.removeMyProfileImage();
            SecurityContextHolder.clearContext();
            continueUpload.countDown();

            try {
                uploading.get(30, TimeUnit.SECONDS);
                throw new AssertionError("Stale upload should fail with HTTP 409");
            } catch (java.util.concurrent.ExecutionException exception) {
                assertTrue(exception.getCause() instanceof ResponseStatusException);
                ResponseStatusException conflict =
                        (ResponseStatusException) exception.getCause();
                assertEquals(409, conflict.getStatusCode().value());
            }
        } finally {
            continueUpload.countDown();
            executor.shutdownNow();
        }

        assertNull(persisted().getProfileImagePublicId());
        assertNull(persisted().getProfileImageUrl());
        verify(images).delete("old-id");
        verify(images).delete("new-id");
    }

    private static void authenticate() {
        UserPrincipal principal = new UserPrincipal(
                USER_ID, "concurrent@test.com", Role.PROVIDER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
    }

    private ProviderProfile persisted() {
        return template.findOne(
                Query.query(Criteria.where("userId").is(USER_ID)),
                ProviderProfile.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent operation");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating test", exception);
        }
    }
}
