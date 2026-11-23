
package com.gamagerecruiters.marketplace.modules.provider.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfileActivityStatus;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

@ExtendWith(MockitoExtension.class)
class ProviderActivityFacadeImplTest {

    private static final String USER_ID = "provider-user-1";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private ProviderVerificationFacade providerVerificationFacade;

    @InjectMocks
    private ProviderActivityFacadeImpl facade;

    private ProviderProfile createProfile(
            ProviderProfileActivityStatus status
    ) {
        ProviderProfile profile = new ProviderProfile();
        profile.setUserId(USER_ID);
        profile.setActivityStatus(status);
        return profile;
    }

    @Test
    void shouldAllowBookingsForApprovedActiveProvider() {

        when(providerVerificationFacade.isApprovedProvider(USER_ID))
                .thenReturn(true);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(
                        createProfile(
                                ProviderProfileActivityStatus.ACTIVE
                        )
                ));

        assertTrue(
                facade.canReceiveNewBookings(USER_ID)
        );
    }

    @Test
    void shouldBlockBookingsForPausedProvider() {

        when(providerVerificationFacade.isApprovedProvider(USER_ID))
                .thenReturn(true);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(
                        createProfile(
                                ProviderProfileActivityStatus.PAUSED
                        )
                ));

        assertFalse(
                facade.canReceiveNewBookings(USER_ID)
        );
    }

    @Test
    void shouldBlockBookingsWhenVerificationIsNotApproved() {

        when(providerVerificationFacade.isApprovedProvider(USER_ID))
                .thenReturn(false);

        assertFalse(
                facade.canReceiveNewBookings(USER_ID)
        );

        verifyNoInteractions(providerProfileRepository);
    }

    @Test
    void shouldBlockBookingsWhenProviderProfileDoesNotExist() {

        when(providerVerificationFacade.isApprovedProvider(USER_ID))
                .thenReturn(true);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty());

        assertFalse(
                facade.canReceiveNewBookings(USER_ID)
        );
    }

    @Test
    void shouldReturnFalseForInvalidProviderId() {

        assertFalse(facade.canReceiveNewBookings(null));
        assertFalse(facade.canReceiveNewBookings(""));
        assertFalse(facade.canReceiveNewBookings("   "));

        assertFalse(facade.isProviderPaused(null));
        assertFalse(facade.isProviderPaused(""));

        verifyNoInteractions(
                providerProfileRepository,
                providerVerificationFacade
        );
    }

    @Test
    void shouldIdentifyPausedProvider() {

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(
                        createProfile(
                                ProviderProfileActivityStatus.PAUSED
                        )
                ));

        assertTrue(
                facade.isProviderPaused(USER_ID)
        );
    }

    @Test
    void shouldReturnFalseWhenProviderIsActive() {

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(
                        createProfile(
                                ProviderProfileActivityStatus.ACTIVE
                        )
                ));

        assertFalse(
                facade.isProviderPaused(USER_ID)
        );
    }

    @Test
    void legacyProfileWithoutActivityStatusShouldBeActive() {

        ProviderProfile profile = createProfile(null);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        assertFalse(
                facade.isProviderPaused(USER_ID)
        );
    }
}
