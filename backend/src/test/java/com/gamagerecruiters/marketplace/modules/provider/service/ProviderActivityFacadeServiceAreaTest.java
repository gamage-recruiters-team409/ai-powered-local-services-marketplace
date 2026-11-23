
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

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

@ExtendWith(MockitoExtension.class)
class ProviderActivityFacadeServiceAreaTest {

    private static final String PROVIDER_USER_ID = "provider-user-1";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private ProviderVerificationFacade providerVerificationFacade;

    @InjectMocks
    private ProviderActivityFacadeImpl providerActivityFacade;

    @Test
    void shouldReturnConfiguredServiceAreaDistricts() {

        ProviderProfile profile = new ProviderProfile();

        profile.setUserId(PROVIDER_USER_ID);

        profile.setServiceAreaDistricts(
                List.of("Colombo", "Gampaha", "Kalutara")
        );

        when(providerProfileRepository.findByUserId(PROVIDER_USER_ID))
                .thenReturn(Optional.of(profile));

        Set<String> result =
                providerActivityFacade.getServiceAreaDistricts(
                        PROVIDER_USER_ID
                );

        assertEquals(
                Set.of("Colombo", "Gampaha", "Kalutara"),
                result
        );

        verify(providerProfileRepository)
                .findByUserId(PROVIDER_USER_ID);
    }

    @Test
    void shouldReturnEmptySetWhenProfileDoesNotExist() {

        when(providerProfileRepository.findByUserId(PROVIDER_USER_ID))
                .thenReturn(Optional.empty());

        Set<String> result =
                providerActivityFacade.getServiceAreaDistricts(
                        PROVIDER_USER_ID
                );

        assertEquals(Set.of(), result);
    }

    @Test
    void shouldReturnEmptySetWhenNoDistrictsAreConfigured() {

        ProviderProfile profile = new ProviderProfile();

        profile.setUserId(PROVIDER_USER_ID);
        profile.setServiceAreaDistricts(List.of());

        when(providerProfileRepository.findByUserId(PROVIDER_USER_ID))
                .thenReturn(Optional.of(profile));

        Set<String> result =
                providerActivityFacade.getServiceAreaDistricts(
                        PROVIDER_USER_ID
                );

        assertEquals(Set.of(), result);
    }

    @Test
    void shouldReturnEmptySetForNullOrBlankProviderId() {

        assertEquals(
                Set.of(),
                providerActivityFacade.getServiceAreaDistricts(null)
        );

        assertEquals(
                Set.of(),
                providerActivityFacade.getServiceAreaDistricts("   ")
        );

        verify(
                providerProfileRepository,
                never()
        ).findByUserId(anyString());
    }

    @Test
    void shouldTrimProviderIdBeforeLookup() {

        ProviderProfile profile = new ProviderProfile();

        profile.setServiceAreaDistricts(List.of("Colombo"));

        when(providerProfileRepository.findByUserId(PROVIDER_USER_ID))
                .thenReturn(Optional.of(profile));

        Set<String> result =
                providerActivityFacade.getServiceAreaDistricts(
                        "  " + PROVIDER_USER_ID + "  "
                );

        assertEquals(Set.of("Colombo"), result);

        verify(providerProfileRepository)
                .findByUserId(PROVIDER_USER_ID);
    }

    @Test
    void shouldReturnImmutableDistrictSet() {

        ProviderProfile profile = new ProviderProfile();

        profile.setServiceAreaDistricts(
                List.of("Colombo", "Gampaha")
        );

        when(providerProfileRepository.findByUserId(PROVIDER_USER_ID))
                .thenReturn(Optional.of(profile));

        Set<String> result =
                providerActivityFacade.getServiceAreaDistricts(
                        PROVIDER_USER_ID
                );

        assertThrows(
                UnsupportedOperationException.class,
                () -> result.add("Kandy")
        );
    }
}
