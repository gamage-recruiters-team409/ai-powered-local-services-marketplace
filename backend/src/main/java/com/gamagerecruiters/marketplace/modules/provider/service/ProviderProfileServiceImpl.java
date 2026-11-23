package com.gamagerecruiters.marketplace.modules.provider.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.filestorage.ProfileImageStorageService;
import com.gamagerecruiters.marketplace.modules.provider.filestorage.StoredProfileImage;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationReverificationService;

@Service
public class ProviderProfileServiceImpl implements ProviderProfileService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProviderProfileServiceImpl.class);

    private final ProviderProfileRepository providerProfileRepository;
    private final MongoTemplate mongoTemplate;
    private final CategoryService categoryService;
    private final DistrictCatalog districtCatalog;
    private final ProviderVerificationReverificationService
            providerVerificationReverificationService;
    private final ProfileImageStorageService profileImageStorageService;

    public ProviderProfileServiceImpl(
            ProviderProfileRepository providerProfileRepository,
            CategoryService categoryService,
            DistrictCatalog districtCatalog,
            ProviderVerificationReverificationService
                    providerVerificationReverificationService,
            ProfileImageStorageService profileImageStorageService,
            MongoTemplate mongoTemplate
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.mongoTemplate = mongoTemplate;
        this.categoryService = categoryService;
        this.districtCatalog = districtCatalog;
        this.providerVerificationReverificationService =
                providerVerificationReverificationService;
        this.profileImageStorageService = profileImageStorageService;
    }

    /**
     * Returns the configured service-area districts for a provider.
     *
     * @param providerId the provider's user ID (not the profile document ID)
     * @return an immutable set of configured district names. For a legacy
     *         profile whose selection field is absent, a valid primary district
     *         is returned; explicit empty selections remain empty.
     */
    @Override
    public Set<String> getServiceAreaDistricts(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Set.of();
        }

        ProviderProfile profile = providerProfileRepository
                .findByUserId(providerId.trim())
                .orElse(null);

        if (profile == null) {
            return Set.of();
        }

        // Use the same resolution rule as the profile response and WP05 facade.
        // A missing legacy field falls back to a valid primary district;
        // an explicitly stored empty list stays empty.
        return ProviderServiceAreaResolver.resolveDistrictSet(
                profile,
                districtCatalog
        );
    }

    @Override
    public ProviderProfileResponse createMyProfile(
            CreateProviderProfileRequest request
    ) {
        String userId = requireCurrentUserId();

        if (providerProfileRepository.existsByUserId(userId)) {
            throw providerProfileAlreadyExists();
        }

        ProviderProfile profile = new ProviderProfile();

        profile.setUserId(userId);
        profile.setProviderType(request.getProviderType());
        profile.setDisplayName(request.getDisplayName().trim());
        profile.setBio(trimToNull(request.getBio()));
        profile.setDistrict(validateDistrict(request.getDistrict()));
        profile.setCity(request.getCity().trim());
        profile.setServiceAreaDistricts(
                resolveServiceAreaDistricts(
                        request.getServiceAreaDistricts(),
                        profile.getDistrict()
                )
        );
        profile.setExperienceYears(request.getExperienceYears());

        // Preserve existing profile image URL functionality.
        profile.setProfileImageUrl(
                trimToNull(request.getProfileImageUrl())
        );

        // Existing category validation is preserved.
        profile.setCategoryIds(
                validateCategoryIds(request.getCategoryIds())
        );

        Instant now = Instant.now();

        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);

        try {
            return toResponse(
                    providerProfileRepository.save(profile)
            );
        } catch (DuplicateKeyException ex) {
            /*
             * The pre-check provides a friendly duplicate response.
             * The MongoDB unique index protects against concurrent requests.
             */
            throw providerProfileAlreadyExists();
        }
    }

    @Override
    public ProviderProfileResponse getMyProfile() {

        String userId = requireCurrentUserId();

        ProviderProfile profile = providerProfileRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));

        return toResponse(profile);
    }

    @Override
    @Transactional
    public ProviderProfileResponse updateMyProfile(
            UpdateProviderProfileRequest request
    ) {
        String userId = requireCurrentUserId();
        ProviderProfile original = requireProviderProfile(userId);

        // Validate all requested values before changing anything in MongoDB.
        // Use a targeted update rather than saving a stale ProviderProfile.
        Update update = new Update();

        if (request.getProviderType() != null) {
            update.set("providerType", request.getProviderType());
        }
        if (request.getDisplayName() != null) {
            update.set("displayName", request.getDisplayName().trim());
        }
        if (request.getBio() != null) {
            update.set("bio", trimToNull(request.getBio()));
        }
        if (request.getDistrict() != null) {
            update.set("district", validateDistrict(request.getDistrict()));
        }
        if (request.getCity() != null) {
            update.set("city", request.getCity().trim());
        }
        if (request.getServiceAreaDistricts() != null) {
            update.set("serviceAreaDistricts",
                    validateServiceAreaDistricts(request.getServiceAreaDistricts()));
        }
        if (request.getExperienceYears() != null) {
            update.set("experienceYears", request.getExperienceYears());
        }
        if (request.getProfileImageUrl() != null) {
            // Preserve legacy profile-image URL update behavior.
            update.set("profileImageUrl", trimToNull(request.getProfileImageUrl()));
        }

        boolean categoriesRequested = request.getCategoryIds() != null;
        if (categoriesRequested) {
            List<String> validated = validateCategoryIds(request.getCategoryIds());
            if (hasNewCategory(original.getCategoryIds(), validated)) {
                // Retain the existing transactional verification coordination.
                // A conflict must abort the profile update as before.
                providerVerificationReverificationService
                        .requestCategoryReverification(original.getId());
            }
            update.set("categoryIds", validated);
        }

        update.set("updatedAt", Instant.now());
        Query query = Query.query(Criteria.where("userId").is(userId));

        if (categoriesRequested) {
            // Compare-and-set category selection: do not silently overwrite
            // a newer concurrent category update based on a stale snapshot.
            // Older records may have no categoryIds field at all.
            List<String> previous = original.getCategoryIds();
            if (previous.isEmpty()) {
                query.addCriteria(new Criteria().orOperator(
                        Criteria.where("categoryIds").is(List.of()),
                        Criteria.where("categoryIds").is(null)
                ));
            } else {
                query.addCriteria(Criteria.where("categoryIds").is(previous));
            }
        }

        ProviderProfile updated = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                ProviderProfile.class
        );

        if (updated == null) {
            // Throwing here rolls back the verification transition in the
            // existing MongoDB transaction on category additions.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Provider profile or categories changed. Please refresh and try again"
            );
        }
        return toResponse(updated);
    }

    /**
     * Uploads or replaces the authenticated provider's profile image.
     *
     * The new image is uploaded before the database is updated.
     * The previous Cloudinary image is deleted only after the new
     * image reference has been successfully saved.
     */
    @Override
    public ProviderProfileResponse uploadMyProfileImage(
            MultipartFile file
    ) {
        String userId = requireCurrentUserId();
        ProviderProfile original = requireProviderProfile(userId);

        // Keep the old image unchanged while Cloudinary performs the upload.
        StoredProfileImage uploaded =
                profileImageStorageService.upload(original.getId(), file);

        String previousPublicId = original.getProfileImagePublicId();
        String previousUrl = original.getProfileImageUrl();
        ProviderProfile updated;

        try {
            // Only swap image references if no other image request replaced
            // or removed the old image in the meantime. Other profile fields
            // (including PAUSED, service area and payout) are never written.
            Query query = imageCompareAndSetQuery(
                    userId, previousPublicId, previousUrl);
            Update update = new Update()
                    .set("profileImageUrl", uploaded.secureUrl())
                    .set("profileImagePublicId", uploaded.publicId())
                    .set("updatedAt", Instant.now());

            updated = mongoTemplate.findAndModify(
                    query, update,
                    FindAndModifyOptions.options().returnNew(true),
                    ProviderProfile.class
            );
            if (updated == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Profile image changed during upload. Please refresh and retry"
                );
            }
        } catch (RuntimeException exception) {
            // The new Cloudinary image was never made current in MongoDB.
            deleteProfileImageQuietly(
                    uploaded.publicId(), "cleaning up an unsuccessful upload");
            throw exception;
        }

        // Only the winning request may delete the previously referenced asset.
        if (hasText(previousPublicId)
                && !previousPublicId.equals(uploaded.publicId())) {
            deleteProfileImageQuietly(
                    previousPublicId, "replacing the previous profile image");
        }
        return toResponse(updated);
    }

    /**
     * Clear the current image only if the references still match what was
     * read. This cannot delete an image that a concurrent upload just saved.
     */
    @Override
    public ProviderProfileResponse removeMyProfileImage() {
        String userId = requireCurrentUserId();
        ProviderProfile original = requireProviderProfile(userId);
        String previousPublicId = original.getProfileImagePublicId();
        String previousUrl = original.getProfileImageUrl();

        if (!hasText(previousUrl) && !hasText(previousPublicId)) {
            return toResponse(original);
        }

        Query query = imageCompareAndSetQuery(
                userId, previousPublicId, previousUrl);
        Update update = new Update()
                .set("profileImageUrl", null)
                .set("profileImagePublicId", null)
                .set("updatedAt", Instant.now());

        ProviderProfile updated = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                ProviderProfile.class
        );
        if (updated == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Profile image changed during removal. Please refresh and retry"
            );
        }

        if (hasText(previousPublicId)) {
            deleteProfileImageQuietly(
                    previousPublicId, "removing the provider profile image");
        }
        return toResponse(updated);
    }

    private Query imageCompareAndSetQuery(
            String userId, String expectedPublicId, String expectedUrl
    ) {
        Query query = Query.query(Criteria.where("userId").is(userId));
        // MongoDB `field: null` matches both missing legacy fields and null.
        query.addCriteria(Criteria.where("profileImagePublicId").is(expectedPublicId));
        query.addCriteria(Criteria.where("profileImageUrl").is(expectedUrl));
        return query;
    }

    private ProviderProfile requireProviderProfile(String userId) {
        return providerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND", "Provider profile not found"));
    }

    /**
     * Attempts to delete an image without allowing a Cloudinary
     * cleanup failure to undo an already-persisted profile update.
     *
     * A cleanup failure is logged for investigation.
     */
    private void deleteProfileImageQuietly(
            String publicId,
            String operation
    ) {

        if (!hasText(publicId)) {
            return;
        }

        try {

            profileImageStorageService.delete(publicId);

        } catch (RuntimeException exception) {

            LOGGER.warn(
                    "Unable to delete provider profile image while {}. "
                            + "Cloudinary cleanup may be required.",
                    operation,
                    exception
            );
        }
    }

    /**
     * Checks whether the updated selection contains a category
     * that was not present in the existing selection.
     *
     * Category removal and category ordering changes do not
     * count as adding a new category.
     */
    private boolean hasNewCategory(
            List<String> existingCategoryIds,
            List<String> updatedCategoryIds
    ) {

        Set<String> existingCategories =
                existingCategoryIds == null
                        ? Set.of()
                        : new LinkedHashSet<>(existingCategoryIds);

        return updatedCategoryIds.stream()
                .anyMatch(categoryId ->
                        !existingCategories.contains(categoryId)
                );
    }

    /**
     * Resolves service-area districts for profile creation.
     *
     * Existing clients that do not yet send serviceAreaDistricts remain compatible:
     * the already validated profile district becomes the initial service area.
     */
    private List<String> resolveServiceAreaDistricts(
            List<String> requestedDistricts,
            String fallbackDistrict
    ) {
        if (requestedDistricts == null) {
            return List.of(validateDistrict(fallbackDistrict));
        }

        return validateServiceAreaDistricts(requestedDistricts);
    }

    /**
     * Validates and normalizes the selected service-area districts against the
     * same fixed Sri Lankan district catalog used by the Customer module.
     */
    private List<String> validateServiceAreaDistricts(
            List<String> requestedDistricts
    ) {
        if (requestedDistricts == null || requestedDistricts.isEmpty()) {
            throw new ResourceConflictException(
                    "PROVIDER_SERVICE_AREA_REQUIRED",
                    "Select at least one service-area district"
            );
        }

        if (requestedDistricts.size() > 25) {
            throw new ResourceConflictException(
                    "PROVIDER_SERVICE_AREA_LIMIT_EXCEEDED",
                    "A maximum of 25 service-area districts can be selected"
            );
        }

        List<String> validatedDistricts = new ArrayList<>();

        for (String rawDistrict : requestedDistricts) {
            if (rawDistrict == null || rawDistrict.isBlank()) {
                throw new ResourceConflictException(
                        "PROVIDER_SERVICE_AREA_INVALID",
                        "Service-area district cannot be empty"
                );
            }

            String normalizedDistrict = rawDistrict.trim();

            if (!districtCatalog.isValidDistrict(normalizedDistrict)) {
                throw new ResourceConflictException(
                        "INVALID_SERVICE_AREA_DISTRICT",
                        "Service-area district '" + normalizedDistrict
                                + "' is not a valid Sri Lankan district"
                );
            }

            String canonicalDistrict = districtCatalog.getAllDistricts().stream()
                    .filter(knownDistrict ->
                            knownDistrict.equalsIgnoreCase(normalizedDistrict)
                    )
                    .findFirst()
                    .orElse(normalizedDistrict);

            boolean duplicate = validatedDistricts.stream()
                    .anyMatch(existingDistrict ->
                            existingDistrict.equalsIgnoreCase(canonicalDistrict)
                    );

            if (duplicate) {
                throw new ResourceConflictException(
                        "PROVIDER_SERVICE_AREA_DUPLICATE",
                        "Duplicate service-area district: " + canonicalDistrict
                );
            }

            validatedDistricts.add(canonicalDistrict);
        }

        return List.copyOf(validatedDistricts);
    }

    /**
     * Validates the selected district against the shared Sri Lankan district catalog.
     * The existing city field remains unchanged because the shared catalog currently
     * provides districts, not a separate city catalog.
     */
    private String validateDistrict(String district) {

        String normalizedDistrict =
                district == null ? null : district.trim();

        if (normalizedDistrict == null
                || normalizedDistrict.isBlank()
                || !districtCatalog.isValidDistrict(normalizedDistrict)) {

            throw new ResourceConflictException(
                    "INVALID_DISTRICT",
                    "District '" + normalizedDistrict
                            + "' is not a valid Sri Lankan district"
            );
        }

        return normalizedDistrict;
    }

    /**
     * Validates selected category IDs using the existing Catalog module.
     *
     * Only existing and active categories can be selected.
     * Duplicate IDs and blank IDs are rejected.
     */
    private List<String> validateCategoryIds(
            List<String> categoryIds
    ) {

        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }

        Set<String> uniqueCategoryIds = new LinkedHashSet<>();

        for (String rawId : categoryIds) {

            if (rawId == null || rawId.isBlank()) {
                throw new ResourceConflictException(
                        "PROVIDER_CATEGORY_INVALID",
                        "Category ID cannot be empty"
                );
            }

            String categoryId = rawId.trim();

            if (!uniqueCategoryIds.add(categoryId)) {
                throw new ResourceConflictException(
                        "PROVIDER_CATEGORY_DUPLICATE",
                        "Duplicate category selection: " + categoryId
                );
            }

            // Find category using the existing Catalog service.
            CategoryResponse category =
                    categoryService.getCategoryById(categoryId);

            // Prevent selection of deactivated categories.
            if (!category.active()) {
                throw new ResourceConflictException(
                        "PROVIDER_CATEGORY_INACTIVE",
                        "Selected category is inactive: " + categoryId
                );
            }
        }

        return new ArrayList<>(uniqueCategoryIds);
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
            throw new AccessDeniedException(
                    "Authentication required"
            );
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ProviderProfileResponse toResponse(
            ProviderProfile profile
    ) {

        ProviderProfileResponse response =
                new ProviderProfileResponse(
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

        response.setCategoryIds(profile.getCategoryIds());

        // Keep the response consistent with the read-only contracts.
        // Only a missing legacy field receives a validated fallback.
        response.setServiceAreaDistricts(
                ProviderServiceAreaResolver.resolveDistricts(
                        profile,
                        districtCatalog
                )
        );

        return response;
    }
}
