
package com.gamagerecruiters.marketplace.modules.provider.service;

import java.time.Instant;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfileActivityStatus;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileActivityResponse;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

@Service
public class ProviderProfileActivityServiceImpl
        implements ProviderProfileActivityService {

    private final ProviderProfileRepository providerProfileRepository;

    private final MongoTemplate mongoTemplate;

    private final ProviderVerificationFacade providerVerificationFacade;

    private final ProviderActivityFacade providerActivityFacade;

    public ProviderProfileActivityServiceImpl(
            ProviderProfileRepository providerProfileRepository,
            MongoTemplate mongoTemplate,
            ProviderVerificationFacade providerVerificationFacade,
            ProviderActivityFacade providerActivityFacade
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.mongoTemplate = mongoTemplate;
        this.providerVerificationFacade = providerVerificationFacade;
        this.providerActivityFacade = providerActivityFacade;
    }

    @Override
    public ProviderProfileActivityResponse getMyActivity() {

        String userId = requireCurrentUserId();

        ProviderProfile profile = requireProviderProfile(userId);

        return toResponse(profile, userId);
    }

    @Override
    public ProviderProfileActivityResponse pauseMyProfile() {

        String userId = requireCurrentUserId();

        ProviderProfile profile = requireProviderProfile(userId);

        // Repeated pause requests are idempotent.
        if (profile.getActivityStatus()
                == ProviderProfileActivityStatus.PAUSED) {

            return toResponse(profile, userId);
        }

        // Only currently approved providers may voluntarily pause.
        if (!providerVerificationFacade.isApprovedProvider(userId)) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only an approved provider can pause the profile"
            );
        }

        Instant now = Instant.now();

        /*
         * Match ACTIVE profiles and legacy profiles without
         * an activityStatus field.
         *
         * Update only activityStatus and updatedAt.
         * All other provider information remains untouched.
         */
        Criteria activeOrLegacyStatus = new Criteria().orOperator(
                Criteria.where("activityStatus")
                        .is(ProviderProfileActivityStatus.ACTIVE),

                Criteria.where("activityStatus").is(null)
        );

        Query query = new Query();

        query.addCriteria(
                Criteria.where("userId").is(userId)
        );

        query.addCriteria(activeOrLegacyStatus);

        Update update = new Update()
                .set(
                        "activityStatus",
                        ProviderProfileActivityStatus.PAUSED
                )
                .set("updatedAt", now);

        mongoTemplate.updateFirst(
                query,
                update,
                ProviderProfile.class
        );

        ProviderProfile updatedProfile =
                requireProviderProfile(userId);

        if (updatedProfile.getActivityStatus()
                != ProviderProfileActivityStatus.PAUSED) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Profile activity changed during the pause request. Please refresh and try again"
            );
        }

        return toResponse(updatedProfile, userId);
    }

    @Override
    public ProviderProfileActivityResponse reactivateMyProfile() {

        String userId = requireCurrentUserId();

        ProviderProfile profile = requireProviderProfile(userId);

        // Repeated reactivation requests are idempotent.
        if (profile.getActivityStatus()
                == ProviderProfileActivityStatus.ACTIVE) {

            return toResponse(profile, userId);
        }

        /*
         * APPROVED is required for reactivation.
         *
         * This also prevents voluntary reactivation from
         * bypassing EXPIRED or Admin SUSPENDED verification.
         */
        if (!providerVerificationFacade.isApprovedProvider(userId)) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Your provider verification is not approved. You cannot reactivate your profile"
            );
        }

        Instant now = Instant.now();

        Query query = Query.query(
                Criteria.where("userId").is(userId)
                        .and("activityStatus")
                        .is(ProviderProfileActivityStatus.PAUSED)
        );

        Update update = new Update()
                .set(
                        "activityStatus",
                        ProviderProfileActivityStatus.ACTIVE
                )
                .set("updatedAt", now);

        mongoTemplate.updateFirst(
                query,
                update,
                ProviderProfile.class
        );

        ProviderProfile updatedProfile =
                requireProviderProfile(userId);

        if (updatedProfile.getActivityStatus()
                != ProviderProfileActivityStatus.ACTIVE) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Profile activity changed during reactivation. Please refresh and try again"
            );
        }

        return toResponse(updatedProfile, userId);
    }

    private String requireCurrentUserId() {

        String userId = CurrentUser.getUserId();

        if (userId == null || userId.isBlank()) {
            throw new AccessDeniedException(
                    "Authentication required"
            );
        }

        return userId.trim();
    }

    private ProviderProfile requireProviderProfile(String userId) {

        return providerProfileRepository.findByUserId(userId)

                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));
    }

    private ProviderProfileActivityResponse toResponse(
            ProviderProfile profile,
            String userId
    ) {

        return new ProviderProfileActivityResponse(
                profile.getActivityStatus(),
                providerActivityFacade.canReceiveNewBookings(userId),
                profile.getUpdatedAt()
        );
    }
}
