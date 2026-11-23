
package com.gamagerecruiters.marketplace.modules.provider.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfileActivityStatus;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

@ExtendWith(MockitoExtension.class)
class ProviderProfileActivityServiceImplTest {

    private static final String USER_ID = "provider-user-1";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private ProviderVerificationFacade providerVerificationFacade;

    @Mock
    private ProviderActivityFacade providerActivityFacade;

    @InjectMocks
    private ProviderProfileActivityServiceImpl activityService;

    @BeforeEach
    void setUp() {

        UserPrincipal principal = new UserPrincipal(
                USER_ID,
                "provider@test.com",
                Role.PROVIDER
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ProviderProfile createProfile(
            ProviderProfileActivityStatus status
    ) {

        ProviderProfile profile = new ProviderProfile();

        profile.setId("profile-1");
        profile.setUserId(USER_ID);
        profile.setActivityStatus(status);
        profile.setUpdatedAt(Instant.parse(
                "2026-11-20T00:00:00Z"
        ));

        return profile;
    }

    @Test
    void shouldReturnCurrentActivityStatus() {

        ProviderProfile profile = createProfile(null);

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        when(providerActivityFacade.canReceiveNewBookings(USER_ID))
                .thenReturn(true);

        var response = activityService.getMyActivity();

        assertEquals(
                ProviderProfileActivityStatus.ACTIVE,
                response.activityStatus()
        );

        assertTrue(response.canReceiveNewBookings());
    }

    @Test
    void shouldPauseApprovedProvider() {

        ProviderProfile activeProfile = createProfile(
                ProviderProfileActivityStatus.ACTIVE
        );

        ProviderProfile pausedProfile = createProfile(
                ProviderProfileActivityStatus.PAUSED
        );

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(
                        Optional.of(activeProfile),
                        Optional.of(pausedProfile)
                );

        when(providerVerificationFacade.isApprovedProvider(USER_ID))
                .thenReturn(true);

        when(providerActivityFacade.canReceiveNewBookings(USER_ID))
                .thenReturn(false);

        var response = activityService.pauseMyProfile();

        assertEquals(
                ProviderProfileActivityStatus.PAUSED,
                response.activityStatus()
        );

        assertFalse(response.canReceiveNewBookings());

        ArgumentCaptor<Update> updateCaptor =
                ArgumentCaptor.forClass(Update.class);

        verify(mongoTemplate).updateFirst(
                any(Query.class),
                updateCaptor.capture(),
                eq(ProviderProfile.class)
        );

        // Ensure only activityStatus and updatedAt are modified.
        Document updateDocument =
                updateCaptor.getValue().getUpdateObject();

        Document setDocument =
                updateDocument.get("$set", Document.class);

        assertNotNull(setDocument);
        assertEquals(2, setDocument.size());

        assertEquals(
                "PAUSED",
                String.valueOf(setDocument.get("activityStatus"))
        );

        assertTrue(setDocument.containsKey("updatedAt"));
    }

    @Test
    void shouldNotPauseAlreadyPausedProviderAgain() {

        ProviderProfile pausedProfile = createProfile(
                ProviderProfileActivityStatus.PAUSED
        );

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(pausedProfile));

        when(providerActivityFacade.canReceiveNewBookings(USER_ID))
                .thenReturn(false);

        var response = activityService.pauseMyProfile();

        assertEquals(
                ProviderProfileActivityStatus.PAUSED,
                response.activityStatus()
        );

        verifyNoInteractions(providerVerificationFacade);

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(ProviderProfile.class)
        );
    }

    @Test
    void shouldRejectPauseWhenProviderIsNotApproved() {

        ProviderProfile profile = createProfile(
                ProviderProfileActivityStatus.ACTIVE
        );

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        when(providerVerificationFacade.isApprovedProvider(USER_ID))
                .thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> activityService.pauseMyProfile()
        );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode()
        );

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(ProviderProfile.class)
        );
    }

    @Test
    void shouldReactivateApprovedPausedProvider() {

        ProviderProfile pausedProfile = createProfile(
                ProviderProfileActivityStatus.PAUSED
        );

        ProviderProfile activeProfile = createProfile(
                ProviderProfileActivityStatus.ACTIVE
        );

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(
                        Optional.of(pausedProfile),
                        Optional.of(activeProfile)
                );

        when(providerVerificationFacade.isApprovedProvider(USER_ID))
                .thenReturn(true);

        when(providerActivityFacade.canReceiveNewBookings(USER_ID))
                .thenReturn(true);

        var response = activityService.reactivateMyProfile();

        assertEquals(
                ProviderProfileActivityStatus.ACTIVE,
                response.activityStatus()
        );

        assertTrue(response.canReceiveNewBookings());

        ArgumentCaptor<Update> updateCaptor =
                ArgumentCaptor.forClass(Update.class);

        verify(mongoTemplate).updateFirst(
                any(Query.class),
                updateCaptor.capture(),
                eq(ProviderProfile.class)
        );

        Document updateDocument =
                updateCaptor.getValue().getUpdateObject();

        Document setDocument =
                updateDocument.get("$set", Document.class);

        assertNotNull(setDocument);
        assertEquals(2, setDocument.size());

        assertEquals(
                "ACTIVE",
                String.valueOf(setDocument.get("activityStatus"))
        );

        assertTrue(setDocument.containsKey("updatedAt"));
    }

    @Test
    void shouldRejectReactivationWhenVerificationIsNotApproved() {

        ProviderProfile pausedProfile = createProfile(
                ProviderProfileActivityStatus.PAUSED
        );

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(pausedProfile));

        when(providerVerificationFacade.isApprovedProvider(USER_ID))
                .thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> activityService.reactivateMyProfile()
        );

        assertEquals(
                HttpStatus.CONFLICT,
                exception.getStatusCode()
        );

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(ProviderProfile.class)
        );
    }

    @Test
    void shouldNotReactivateAlreadyActiveProviderAgain() {

        ProviderProfile activeProfile = createProfile(
                ProviderProfileActivityStatus.ACTIVE
        );

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(activeProfile));

        when(providerActivityFacade.canReceiveNewBookings(USER_ID))
                .thenReturn(true);

        var response = activityService.reactivateMyProfile();

        assertEquals(
                ProviderProfileActivityStatus.ACTIVE,
                response.activityStatus()
        );

        verifyNoInteractions(providerVerificationFacade);

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(ProviderProfile.class)
        );
    }

    @Test
    void shouldThrowWhenProviderProfileDoesNotExist() {

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> activityService.getMyActivity()
        );
    }
}
