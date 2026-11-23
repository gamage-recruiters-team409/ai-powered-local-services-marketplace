package com.gamagerecruiters.marketplace.modules.provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.filestorage.ProfileImageStorageService;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationReverificationService;

@ExtendWith(MockitoExtension.class)
class ProviderServiceAreaContractTest {

    private static final String PROVIDER_USER_ID = "provider-user-1";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private CategoryService categoryService;

    @Mock
    private DistrictCatalog districtCatalog;

    @Mock
    private ProviderVerificationReverificationService
            providerVerificationReverificationService;

    @Mock
    private ProfileImageStorageService profileImageStorageService;

    @InjectMocks
    private ProviderProfileServiceImpl providerProfileService;

    @Test
    void getServiceAreaDistricts_shouldReturnConfiguredDistrictNames() {
        ProviderProfile profile = new ProviderProfile();
        profile.setUserId(PROVIDER_USER_ID);
        profile.setServiceAreaDistricts(List.of("Colombo", "Gampaha", "Kalutara"));

        when(providerProfileRepository.findByUserId(PROVIDER_USER_ID))
                .thenReturn(Optional.of(profile));

        Set<String> result =
                providerProfileService.getServiceAreaDistricts(PROVIDER_USER_ID);

        assertEquals(
                Set.of("Colombo", "Gampaha", "Kalutara"),
                result
        );
        verify(providerProfileRepository).findByUserId(PROVIDER_USER_ID);
    }

    @Test
    void getServiceAreaDistricts_shouldReturnEmptySetWhenProfileDoesNotExist() {
        when(providerProfileRepository.findByUserId(PROVIDER_USER_ID))
                .thenReturn(Optional.empty());

        Set<String> result =
                providerProfileService.getServiceAreaDistricts(PROVIDER_USER_ID);

        assertEquals(Set.of(), result);
        verify(providerProfileRepository).findByUserId(PROVIDER_USER_ID);
    }

    @Test
    void getServiceAreaDistricts_shouldReturnEmptySetWhenNoServiceAreaIsConfigured() {
        ProviderProfile profile = new ProviderProfile();
        profile.setUserId(PROVIDER_USER_ID);
        profile.setDistrict("Colombo");
        profile.setServiceAreaDistricts(List.of());

        when(providerProfileRepository.findByUserId(PROVIDER_USER_ID))
                .thenReturn(Optional.of(profile));

        Set<String> result =
                providerProfileService.getServiceAreaDistricts(PROVIDER_USER_ID);

        // Do not fall back to the primary profile district for the WP05 contract.
        assertEquals(Set.of(), result);
    }

    @Test
    void getServiceAreaDistricts_shouldReturnEmptySetForNullOrBlankUserId() {
        assertEquals(
                Set.of(),
                providerProfileService.getServiceAreaDistricts(null)
        );
        assertEquals(
                Set.of(),
                providerProfileService.getServiceAreaDistricts("   ")
        );

        verify(providerProfileRepository, never()).findByUserId(anyString());
    }

    @Test
    void getServiceAreaDistricts_shouldReturnAnImmutableSet() {
        ProviderProfile profile = new ProviderProfile();
        profile.setUserId(PROVIDER_USER_ID);
        profile.setServiceAreaDistricts(List.of("Colombo", "Gampaha"));

        when(providerProfileRepository.findByUserId(PROVIDER_USER_ID))
                .thenReturn(Optional.of(profile));

        Set<String> result =
                providerProfileService.getServiceAreaDistricts(PROVIDER_USER_ID);

        assertThrows(
                UnsupportedOperationException.class,
                () -> result.add("Kandy")
        );
    }
}
