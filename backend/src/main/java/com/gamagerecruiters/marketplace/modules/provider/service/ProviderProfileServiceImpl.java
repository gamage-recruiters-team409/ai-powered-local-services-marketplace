package com.gamagerecruiters.marketplace.modules.provider.service;

import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

@Service
public class ProviderProfileServiceImpl implements ProviderProfileService {

    private final ProviderProfileRepository providerProfileRepository;

    public ProviderProfileServiceImpl(ProviderProfileRepository providerProfileRepository) {
        this.providerProfileRepository = providerProfileRepository;
    }

    @Override
    public ProviderProfileResponse createMyProfile(CreateProviderProfileRequest request) {
        String userId = requireCurrentUserId();

        if (providerProfileRepository.existsByUserId(userId)) {
            throw providerProfileAlreadyExists();
        }

        ProviderProfile profile = new ProviderProfile();
        profile.setUserId(userId);
        profile.setProviderType(request.getProviderType());
        profile.setDisplayName(request.getDisplayName().trim());
        profile.setBio(trimToNull(request.getBio()));
        profile.setDistrict(request.getDistrict().trim());
        profile.setCity(request.getCity().trim());
        profile.setExperienceYears(request.getExperienceYears());
        profile.setProfileImageUrl(trimToNull(request.getProfileImageUrl()));

        Instant now = Instant.now();
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);

        try {
            return toResponse(providerProfileRepository.save(profile));
        } catch (DuplicateKeyException ex) {
            /*
             * The pre-check above provides a fast and friendly duplicate response,
             * while the MongoDB unique index remains the final protection against
             * two concurrent requests creating a profile for the same user.
             */
            throw providerProfileAlreadyExists();
        }
    }

    @Override
    public ProviderProfileResponse getMyProfile() {
        String userId = requireCurrentUserId();

        ProviderProfile profile = providerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));

        return toResponse(profile);
    }

    @Override
    public ProviderProfileResponse updateMyProfile(UpdateProviderProfileRequest request) {
        String userId = requireCurrentUserId();

        ProviderProfile profile = providerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));

        if (request.getProviderType() != null) {
            profile.setProviderType(request.getProviderType());
        }

        if (request.getDisplayName() != null) {
            profile.setDisplayName(request.getDisplayName().trim());
        }

        if (request.getBio() != null) {
            profile.setBio(trimToNull(request.getBio()));
        }

        if (request.getDistrict() != null) {
            profile.setDistrict(request.getDistrict().trim());
        }

        if (request.getCity() != null) {
            profile.setCity(request.getCity().trim());
        }

        if (request.getExperienceYears() != null) {
            profile.setExperienceYears(request.getExperienceYears());
        }

        if (request.getProfileImageUrl() != null) {
            profile.setProfileImageUrl(trimToNull(request.getProfileImageUrl()));
        }

        profile.setUpdatedAt(Instant.now());

        return toResponse(providerProfileRepository.save(profile));
    }

    private ResourceConflictException providerProfileAlreadyExists() {
        return new ResourceConflictException(
                "PROVIDER_PROFILE_ALREADY_EXISTS",
                "A provider profile already exists for this user"
        );
    }

    private String requireCurrentUserId() {
        String userId = CurrentUser.getUserId();

        if (userId == null) {
            throw new AccessDeniedException("Authentication required");
        }

        return userId;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ProviderProfileResponse toResponse(ProviderProfile profile) {
        return new ProviderProfileResponse(
                profile.getId(),
                profile.getUserId(),
                profile.getProviderType(),
                profile.getDisplayName(),
                profile.getBio(),
                profile.getDistrict(),
                profile.getCity(),
                profile.getExperienceYears(),
                profile.getProfileImageUrl(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}