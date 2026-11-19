package com.gamagerecruiters.marketplace.modules.provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.provider.crypto.ProviderPayoutEncryptionService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
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

    @InjectMocks
    private ProviderPayoutDetailsServiceImpl providerPayoutDetailsService;

    @BeforeEach
    void setUpSecurityContext() {
        UserPrincipal principal = new UserPrincipal(
                USER_ID,
                EMAIL,
                Role.PROVIDER
        );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
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
    void updateMyPayoutDetails_shouldEncryptAndSaveAccountNumber() {
        ProviderProfile profile = new ProviderProfile();
        profile.setId("profile-1");
        profile.setUserId(USER_ID);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        when(encryptionService.encrypt(ACCOUNT_NUMBER))
                .thenReturn(ENCRYPTED_ACCOUNT_NUMBER);

        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProviderPayoutDetailsRequest request =
                new UpdateProviderPayoutDetailsRequest();

        request.setBankName(" Commercial Bank ");
        request.setAccountHolderName(" Hiba Perera ");
        request.setAccountNumber("1234-5678-9012");

        ProviderPayoutDetailsResponse response =
                providerPayoutDetailsService.updateMyPayoutDetails(request);

        assertTrue(response.configured());
        assertEquals("Commercial Bank", response.bankName());
        assertEquals("Hiba Perera", response.accountHolderName());
        assertEquals("********9012", response.maskedAccountNumber());

        assertEquals("Commercial Bank", profile.getPayoutBankName());
        assertEquals("Hiba Perera", profile.getPayoutAccountHolderName());
        assertEquals(
                ENCRYPTED_ACCOUNT_NUMBER,
                profile.getPayoutAccountNumberEncrypted()
        );

        verify(encryptionService).encrypt(ACCOUNT_NUMBER);
        verify(providerProfileRepository).save(profile);
    }

    @Test
    void getPayoutDetailsForPayoutProcessing_shouldReturnDecryptedDetailsForWp07() {
        ProviderProfile profile = createProfileWithPayoutDetails();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        when(encryptionService.decrypt(ENCRYPTED_ACCOUNT_NUMBER))
                .thenReturn(ACCOUNT_NUMBER);

        Optional<ProviderPayoutDetails> result =
                providerPayoutDetailsService
                        .getPayoutDetailsForPayoutProcessing(USER_ID);

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
                providerPayoutDetailsService
                        .getPayoutDetailsForPayoutProcessing(USER_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    void getPayoutDetailsForPayoutProcessing_shouldReturnEmptyWhenDetailsAreNotConfigured() {
        ProviderProfile profile = new ProviderProfile();
        profile.setUserId(USER_ID);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        Optional<ProviderPayoutDetails> result =
                providerPayoutDetailsService
                        .getPayoutDetailsForPayoutProcessing(USER_ID);

        assertTrue(result.isEmpty());
        verify(encryptionService, never()).decrypt(any());
    }

    @Test
    void getPayoutDetailsForPayoutProcessing_shouldReturnEmptyForBlankUserId() {
        Optional<ProviderPayoutDetails> nullResult =
                providerPayoutDetailsService
                        .getPayoutDetailsForPayoutProcessing(null);

        Optional<ProviderPayoutDetails> blankResult =
                providerPayoutDetailsService
                        .getPayoutDetailsForPayoutProcessing("   ");

        assertTrue(nullResult.isEmpty());
        assertTrue(blankResult.isEmpty());

        verify(providerProfileRepository, never()).findByUserId(any());
    }

    private ProviderProfile createProfileWithPayoutDetails() {
        ProviderProfile profile = new ProviderProfile();
        profile.setId("profile-1");
        profile.setUserId(USER_ID);
        profile.setPayoutBankName("Commercial Bank");
        profile.setPayoutAccountHolderName("Hiba Perera");
        profile.setPayoutAccountNumberEncrypted(
                ENCRYPTED_ACCOUNT_NUMBER
        );

        return profile;
    }
}
