package com.gamagerecruiters.marketplace.modules.provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.provider.crypto.ProviderPayoutEncryptionService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfileActivityStatus;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderPayoutDetailsResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderPayoutDetailsRequest;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

@ExtendWith(MockitoExtension.class)
class ProviderPayoutDetailsServiceImplTest {

    private static final String USER_ID = "provider-user-1";
    private static final String EMAIL = "provider@test.com";
    private static final String ACCOUNT_NUMBER = "123456789012";
    private static final String ENCRYPTED_ACCOUNT_NUMBER = "v1:test-encrypted-value";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private ProviderPayoutEncryptionService encryptionService;

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private ProviderPayoutDetailsServiceImpl providerPayoutDetailsService;

    @BeforeEach
    void setUpSecurityContext() {
        UserPrincipal principal = new UserPrincipal(USER_ID, EMAIL, Role.PROVIDER);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()
                );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getMyPayoutDetails_shouldReturnMaskedAccountNumber() {
        ProviderProfile profile = createProfileWithPayoutDetails();
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
        when(encryptionService.decrypt(ENCRYPTED_ACCOUNT_NUMBER))
                .thenReturn(ACCOUNT_NUMBER);

        ProviderPayoutDetailsResponse response =
                providerPayoutDetailsService.getMyPayoutDetails();

        assertTrue(response.configured());
        assertEquals("Commercial Bank", response.bankName());
        assertEquals("Hiba Perera", response.accountHolderName());
        assertEquals("********9012", response.maskedAccountNumber());
    }

    @Test
    void getMyPayoutDetails_shouldReturnUnconfiguredWhenNoDetailsExist() {
        ProviderProfile profile = new ProviderProfile();
        profile.setUserId(USER_ID);
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        ProviderPayoutDetailsResponse response =
                providerPayoutDetailsService.getMyPayoutDetails();

        assertFalse(response.configured());
        assertNull(response.bankName());
        assertNull(response.accountHolderName());
        assertNull(response.maskedAccountNumber());
        verify(encryptionService, never()).decrypt(any());
    }

    @Test
    void updateMyPayoutDetails_shouldEncryptAndAtomicallyUpdateOnlyPayoutFields() {
        ProviderProfile profile = createProfileWithoutPayoutDetails();
        when(encryptionService.encrypt(ACCOUNT_NUMBER))
                .thenReturn(ENCRYPTED_ACCOUNT_NUMBER);
        stubAtomicPayoutUpdate(profile);

        ProviderPayoutDetailsResponse response =
                providerPayoutDetailsService.updateMyPayoutDetails(payoutRequest());

        assertTrue(response.configured());
        assertEquals("Commercial Bank", response.bankName());
        assertEquals("Hiba Perera", response.accountHolderName());
        assertEquals("********9012", response.maskedAccountNumber());
        assertEquals("Commercial Bank", profile.getPayoutBankName());
        assertEquals("Hiba Perera", profile.getPayoutAccountHolderName());
        assertEquals(ENCRYPTED_ACCOUNT_NUMBER,
                profile.getPayoutAccountNumberEncrypted());
        verify(encryptionService).encrypt(ACCOUNT_NUMBER);
        verify(providerProfileRepository, never()).save(any(ProviderProfile.class));
        verify(providerProfileRepository, never()).findByUserId(any());
    }

    @Test
    void updateMyPayoutDetails_shouldPreservePausedStatusAndOtherFields() {
        ProviderProfile profile = createProfileWithoutPayoutDetails();
        profile.setActivityStatus(ProviderProfileActivityStatus.PAUSED);
        profile.setProfileImageUrl("https://example.com/old-image.jpg");
        profile.setServiceAreaDistricts(List.of("Gampaha", "Colombo"));
        profile.setCategoryIds(List.of("category-1"));
        profile.setDisplayName("Existing Provider");

        when(encryptionService.encrypt(ACCOUNT_NUMBER))
                .thenReturn(ENCRYPTED_ACCOUNT_NUMBER);
        stubAtomicPayoutUpdate(profile);

        providerPayoutDetailsService.updateMyPayoutDetails(payoutRequest());

        assertEquals(ProviderProfileActivityStatus.PAUSED,
                profile.getActivityStatus());
        assertEquals("https://example.com/old-image.jpg",
                profile.getProfileImageUrl());
        assertEquals(List.of("Gampaha", "Colombo"),
                profile.getServiceAreaDistricts());
        assertEquals(List.of("category-1"), profile.getCategoryIds());
        assertEquals("Existing Provider", profile.getDisplayName());
        verify(providerProfileRepository, never()).save(any(ProviderProfile.class));
    }

    @Test
    void updateMyPayoutDetails_shouldThrowNotFoundWhenAtomicMatchFails() {
        when(encryptionService.encrypt(ACCOUNT_NUMBER))
                .thenReturn(ENCRYPTED_ACCOUNT_NUMBER);
        // MongoTemplate returns null when the user ID matches no profile.

        assertThrows(
                ResourceNotFoundException.class,
                () -> providerPayoutDetailsService.updateMyPayoutDetails(
                        payoutRequest()
                )
        );
        verify(providerProfileRepository, never()).save(any(ProviderProfile.class));
    }

    @Test
    void getPayoutDetailsForPayoutProcessing_shouldReturnDecryptedDetailsForWp07() {
        ProviderProfile profile = createProfileWithPayoutDetails();
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
        when(encryptionService.decrypt(ENCRYPTED_ACCOUNT_NUMBER))
                .thenReturn(ACCOUNT_NUMBER);

        Optional<ProviderPayoutDetails> result =
                providerPayoutDetailsService.getPayoutDetailsForPayoutProcessing(USER_ID);

        assertTrue(result.isPresent());
        assertEquals("Commercial Bank", result.get().getBankName());
        assertEquals("Hiba Perera", result.get().getAccountHolderName());
        assertEquals(ACCOUNT_NUMBER, result.get().getAccountNumber());
    }

    @Test
    void getPayoutDetailsForPayoutProcessing_shouldReturnEmptyWhenProviderDoesNotExist() {
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty());

        Optional<ProviderPayoutDetails> result =
                providerPayoutDetailsService.getPayoutDetailsForPayoutProcessing(USER_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    void getPayoutDetailsForPayoutProcessing_shouldReturnEmptyWhenDetailsAreNotConfigured() {
        ProviderProfile profile = new ProviderProfile();
        profile.setUserId(USER_ID);
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        Optional<ProviderPayoutDetails> result =
                providerPayoutDetailsService.getPayoutDetailsForPayoutProcessing(USER_ID);

        assertTrue(result.isEmpty());
        verify(encryptionService, never()).decrypt(any());
    }

    @Test
    void getPayoutDetailsForPayoutProcessing_shouldReturnEmptyForBlankUserId() {
        assertTrue(providerPayoutDetailsService
                .getPayoutDetailsForPayoutProcessing(null).isEmpty());
        assertTrue(providerPayoutDetailsService
                .getPayoutDetailsForPayoutProcessing("   ").isEmpty());

        verify(providerProfileRepository, never()).findByUserId(any());
    }

    private UpdateProviderPayoutDetailsRequest payoutRequest() {
        UpdateProviderPayoutDetailsRequest request =
                new UpdateProviderPayoutDetailsRequest();
        request.setBankName(" Commercial Bank ");
        request.setAccountHolderName(" Hiba Perera ");
        request.setAccountNumber("1234-5678-9012");
        return request;
    }

    private void stubAtomicPayoutUpdate(ProviderProfile profile) {
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(ProviderProfile.class)
        )).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            Update update = invocation.getArgument(1);
            FindAndModifyOptions options = invocation.getArgument(2);

            Document queryObject = query.getQueryObject();
            assertEquals(USER_ID, queryObject.get("userId"));
            assertTrue(options.isReturnNew());

            Document set = update.getUpdateObject().get("$set", Document.class);
            assertEquals(4, set.size(), "Only payout fields and updatedAt may change");
            assertEquals("Commercial Bank", set.getString("payoutBankName"));
            assertEquals("Hiba Perera", set.getString("payoutAccountHolderName"));
            assertEquals(ENCRYPTED_ACCOUNT_NUMBER,
                    set.getString("payoutAccountNumberEncrypted"));
            assertTrue(set.containsKey("updatedAt"));

            // Simulate MongoDB's atomic field-level update, preserving all other fields.
            profile.setPayoutBankName(set.getString("payoutBankName"));
            profile.setPayoutAccountHolderName(
                    set.getString("payoutAccountHolderName"));
            profile.setPayoutAccountNumberEncrypted(
                    set.getString("payoutAccountNumberEncrypted"));
            profile.setUpdatedAt((java.time.Instant) set.get("updatedAt"));
            return profile;
        });
    }

    private ProviderProfile createProfileWithoutPayoutDetails() {
        ProviderProfile profile = new ProviderProfile();
        profile.setId("profile-1");
        profile.setUserId(USER_ID);
        return profile;
    }

    private ProviderProfile createProfileWithPayoutDetails() {
        ProviderProfile profile = createProfileWithoutPayoutDetails();
        profile.setPayoutBankName("Commercial Bank");
        profile.setPayoutAccountHolderName("Hiba Perera");
        profile.setPayoutAccountNumberEncrypted(ENCRYPTED_ACCOUNT_NUMBER);
        return profile;
    }
}
