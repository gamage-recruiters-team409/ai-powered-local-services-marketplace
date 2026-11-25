package com.gamagerecruiters.marketplace.modules.customer.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.mongodb.client.result.UpdateResult;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerAddress;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupReason;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupTask;
import com.gamagerecruiters.marketplace.modules.customer.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerProfileResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.DeactivateAccountRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.UpdateCustomerProfileRequest;
import com.gamagerecruiters.marketplace.modules.customer.repository.CustomerAddressRepository;

@Service
public class CustomerServiceImpl implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceImpl.class);

    public static final String DEFAULT_ADDRESS_INDEX_NAME = "customerId_1_isDefault_1_unique_default";
    public static final String SLOT_INDEX_NAME = "customerId_1_slot_1_unique";

    private final UserInternalService userInternalService;
    private final CustomerAddressRepository customerAddressRepository;
    private final DistrictCatalog districtCatalog;
    private final ObjectProvider<OpenBookingsChecker> openBookingsCheckerProvider;
    private final CustomerProfileImageStorage profileImageStorage;
    private final ProfileImageCleanupService cleanupService;
    private final MongoTemplate mongoTemplate;
    private final int maxAddresses;

    public CustomerServiceImpl(
            UserInternalService userInternalService,
            CustomerAddressRepository customerAddressRepository,
            DistrictCatalog districtCatalog,
            ObjectProvider<OpenBookingsChecker> openBookingsCheckerProvider,
            CustomerProfileImageStorage profileImageStorage,
            ProfileImageCleanupService cleanupService,
            MongoTemplate mongoTemplate,
            @Value("${app.customer.max-addresses:10}") int maxAddresses
    ) {
        this.userInternalService = userInternalService;
        this.customerAddressRepository = customerAddressRepository;
        this.districtCatalog = districtCatalog;
        this.openBookingsCheckerProvider = openBookingsCheckerProvider;
        this.profileImageStorage = profileImageStorage;
        this.cleanupService = cleanupService;
        this.mongoTemplate = mongoTemplate;
        this.maxAddresses = maxAddresses;
    }

    @Override
    public CustomerProfileResponse getProfile(String customerId) {
        User user = userInternalService.getById(customerId);
        return toProfileResponse(user);
    }

    @Override
    public CustomerProfileResponse updateProfile(String customerId, UpdateCustomerProfileRequest request) {
        User updated = userInternalService.updateContactDetails(customerId, request.fullName(), request.phone());
        return toProfileResponse(updated);
    }

    @Override
    public void changePassword(String customerId, ChangePasswordRequest request) {
        userInternalService.changePassword(customerId, request.currentPassword(), request.newPassword());
    }

    @Override
    public List<CustomerAddressResponse> getAddresses(String customerId) {
        return getAndHealAddresses(customerId).stream()
                .map(CustomerAddressResponse::fromEntity)
                .toList();
    }

    @Override
    public CustomerAddressResponse createAddress(String customerId, CustomerAddressRequest request) {
        validateDistrict(request.district());

        int maxAttempts = maxAddresses + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<CustomerAddress> addresses = getAndHealAddresses(customerId);
            if (addresses.size() >= maxAddresses) {
                throw new ResourceConflictException(
                        "MAX_ADDRESSES_REACHED",
                        "Maximum number of saved addresses (" + maxAddresses + ") reached"
                );
            }

            lazyBackfillSlots(customerId, addresses);
            addresses = getAndHealAddresses(customerId);
            if (addresses.size() >= maxAddresses) {
                throw new ResourceConflictException(
                        "MAX_ADDRESSES_REACHED",
                        "Maximum number of saved addresses (" + maxAddresses + ") reached"
                );
            }

            Integer slot = findLowestFreeSlot(addresses, maxAddresses);
            if (slot == null) {
                throw new ResourceConflictException(
                        "MAX_ADDRESSES_REACHED",
                        "Maximum number of saved addresses (" + maxAddresses + ") reached"
                );
            }

            boolean makeDefault = addresses.isEmpty() || Boolean.TRUE.equals(request.isDefault());
            CustomerAddress previousDefault = null;
            if (makeDefault && !addresses.isEmpty()) {
                previousDefault = addresses.stream()
                        .filter(CustomerAddress::isDefault)
                        .findFirst()
                        .orElse(null);
                if (previousDefault != null) {
                    previousDefault.setDefault(false);
                    previousDefault.setUpdatedAt(Instant.now());
                    customerAddressRepository.save(previousDefault);
                }
            }

            Instant now = Instant.now();
            CustomerAddress address = new CustomerAddress(
                    customerId,
                    request.label().trim(),
                    request.addressLine1().trim(),
                    request.addressLine2() != null ? request.addressLine2().trim() : null,
                    request.city().trim(),
                    request.district().trim(),
                    request.postalCode() != null && !request.postalCode().isBlank() ? request.postalCode().trim() : null,
                    makeDefault,
                    now,
                    now
            );
            address.setSlot(slot);

            try {
                CustomerAddress saved = customerAddressRepository.save(address);
                return CustomerAddressResponse.fromEntity(saved);
            } catch (DuplicateKeyException ex) {
                if (previousDefault != null) {
                    try {
                        previousDefault.setDefault(true);
                        previousDefault.setUpdatedAt(Instant.now());
                        customerAddressRepository.save(previousDefault);
                    } catch (DuplicateKeyException restoreDupEx) {
                        log.debug("Restoring previous default hit duplicate key; another default already exists");
                    }
                }

                if (isDuplicateKeyOnIndex(ex, SLOT_INDEX_NAME)) {
                    // Retry with next slot on slot index violation
                    continue;
                } else if (isDuplicateKeyOnIndex(ex, DEFAULT_ADDRESS_INDEX_NAME)) {
                    // Lost race on default address index; retry full create
                    continue;
                } else {
                    throw ex;
                }
            }
        }

        throw new ResourceConflictException(
                "MAX_ADDRESSES_REACHED",
                "Maximum number of saved addresses (" + maxAddresses + ") reached"
        );
    }

    @Override
    public CustomerAddressResponse updateAddress(String customerId, String addressId, CustomerAddressRequest request) {
        validateDistrict(request.district());

        CustomerAddress address = findCustomerAddressOrThrow(customerId, addressId);

        address.setLabel(request.label().trim());
        address.setAddressLine1(request.addressLine1().trim());
        address.setAddressLine2(request.addressLine2() != null ? request.addressLine2().trim() : null);
        address.setCity(request.city().trim());
        address.setDistrict(request.district().trim());
        address.setPostalCode(request.postalCode() != null && !request.postalCode().isBlank() ? request.postalCode().trim() : null);
        address.setUpdatedAt(Instant.now());

        boolean requestedDefault = Boolean.TRUE.equals(request.isDefault());
        if (requestedDefault && !address.isDefault()) {
            customerAddressRepository.save(address);
            return setDefaultAddress(customerId, addressId);
        } else if (Boolean.FALSE.equals(request.isDefault()) && address.isDefault()) {
            address.setDefault(false);
            customerAddressRepository.save(address);
            getAndHealAddresses(customerId);
            return CustomerAddressResponse.fromEntity(findCustomerAddressOrThrow(customerId, addressId));
        }

        CustomerAddress saved = customerAddressRepository.save(address);
        return CustomerAddressResponse.fromEntity(saved);
    }

    @Override
    public void deleteAddress(String customerId, String addressId) {
        CustomerAddress address = findCustomerAddressOrThrow(customerId, addressId);

        boolean wasDefault = address.isDefault();
        customerAddressRepository.delete(address);

        if (wasDefault) {
            customerAddressRepository.findFirstByCustomerIdOrderByCreatedAtAsc(customerId).ifPresent(oldest -> {
                oldest.setDefault(true);
                oldest.setUpdatedAt(Instant.now());
                try {
                    customerAddressRepository.save(oldest);
                } catch (DuplicateKeyException e) {
                    log.debug("DuplicateKeyException during delete default promotion ignored; another default set concurrently");
                }
            });
        }
    }

    @Override
    public CustomerAddressResponse setDefaultAddress(String customerId, String addressId) {
        CustomerAddress target = findCustomerAddressOrThrow(customerId, addressId);
        if (target.isDefault()) {
            return CustomerAddressResponse.fromEntity(target);
        }

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            target = findCustomerAddressOrThrow(customerId, addressId);
            if (target.isDefault()) {
                return CustomerAddressResponse.fromEntity(target);
            }

            List<CustomerAddress> currentDefaults = customerAddressRepository.findByCustomerIdAndIsDefaultTrue(customerId);
            CustomerAddress previousDefault = null;
            for (CustomerAddress def : currentDefaults) {
                if (!def.getId().equals(addressId)) {
                    previousDefault = def;
                    def.setDefault(false);
                    def.setUpdatedAt(Instant.now());
                    customerAddressRepository.save(def);
                }
            }

            target.setDefault(true);
            target.setUpdatedAt(Instant.now());

            try {
                CustomerAddress saved = customerAddressRepository.save(target);
                return CustomerAddressResponse.fromEntity(saved);
            } catch (DuplicateKeyException ex) {
                target.setDefault(false);
                if (previousDefault != null) {
                    try {
                        previousDefault.setDefault(true);
                        previousDefault.setUpdatedAt(Instant.now());
                        customerAddressRepository.save(previousDefault);
                    } catch (DuplicateKeyException restoreDup) {
                        log.debug("Restoring previous default hit duplicate key; another default already exists");
                    }
                }

                if (isDuplicateKeyOnIndex(ex, DEFAULT_ADDRESS_INDEX_NAME)) {
                    if (attempt == maxAttempts) {
                        getAndHealAddresses(customerId);
                        throw new ApiException(
                                HttpStatus.CONFLICT,
                                "CONCURRENT_MODIFICATION",
                                "Unable to set default address due to a concurrent update. Please try again."
                        );
                    }
                    try {
                        Thread.sleep(10L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    throw ex;
                }
            }
        }

        getAndHealAddresses(customerId);
        throw new ApiException(
                HttpStatus.CONFLICT,
                "CONCURRENT_MODIFICATION",
                "Unable to set default address due to a concurrent update."
        );
    }

    @Override
    public void deactivateAccount(String customerId, DeactivateAccountRequest request) {
        userInternalService.verifyPassword(customerId, request.password());

        OpenBookingsChecker checker = openBookingsCheckerProvider.getIfAvailable();
        if (checker == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DEACTIVATION_UNAVAILABLE",
                    "Account deactivation is temporarily unavailable"
            );
        }

        if (checker.hasOpenBookings(customerId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "OPEN_BOOKINGS_EXIST",
                    "You cannot deactivate your account while you have active bookings"
            );
        }

        userInternalService.deactivateOwnAccount(customerId);
    }

    private void validateDistrict(String district) {
        if (!districtCatalog.isValidDistrict(district)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_DISTRICT",
                    "District '" + district + "' is not a valid Sri Lankan district"
            );
        }
    }

    private List<CustomerAddress> getAndHealAddresses(String customerId) {
        List<CustomerAddress> addresses = customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        if (!addresses.isEmpty() && addresses.stream().noneMatch(CustomerAddress::isDefault)) {
            CustomerAddress oldest = addresses.get(0);
            oldest.setDefault(true);
            oldest.setUpdatedAt(Instant.now());
            try {
                customerAddressRepository.save(oldest);
                log.info("Self-healed default address for customer {}: promoted address {}", customerId, oldest.getId());
            } catch (DuplicateKeyException ex) {
                log.debug("DuplicateKeyException during self-heal default promotion ignored (default already set)");
            }
            addresses = customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        }
        return addresses;
    }

    private void lazyBackfillSlots(String customerId, List<CustomerAddress> addresses) {
        if (addresses.size() > maxAddresses) {
            return;
        }

        Set<Integer> occupiedSlots = addresses.stream()
                .map(CustomerAddress::getSlot)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        for (CustomerAddress addr : addresses) {
            if (addr.getSlot() == null) {
                for (int s = 1; s <= maxAddresses; s++) {
                    if (!occupiedSlots.contains(s)) {
                        occupiedSlots.add(s);
                        addr.setSlot(s);
                        addr.setUpdatedAt(Instant.now());
                        try {
                            customerAddressRepository.save(addr);
                        } catch (DuplicateKeyException ex) {
                            if (isDuplicateKeyOnIndex(ex, SLOT_INDEX_NAME)) {
                                log.debug("Slot {} taken concurrently during backfill for address {}", s, addr.getId());
                            } else {
                                throw ex;
                            }
                        }
                        break;
                    }
                }
            }
        }
    }

    private Integer findLowestFreeSlot(List<CustomerAddress> addresses, int max) {
        Set<Integer> usedSlots = addresses.stream()
                .map(CustomerAddress::getSlot)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (int s = 1; s <= max; s++) {
            if (!usedSlots.contains(s)) {
                return s;
            }
        }
        return null;
    }

    private boolean isDuplicateKeyOnIndex(DuplicateKeyException ex, String indexName) {
        if (ex.getMessage() != null && ex.getMessage().contains(indexName)) {
            return true;
        }
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains(indexName)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private CustomerAddress findCustomerAddressOrThrow(String customerId, String addressId) {
        return customerAddressRepository.findByIdAndCustomerId(addressId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("ADDRESS_NOT_FOUND", "Address not found"));
    }

    @Override
    public CustomerProfileResponse uploadProfilePhoto(String customerId, MultipartFile file) {
        User user = userInternalService.getById(customerId);
        if (user.getRole() != Role.CUSTOMER || user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Customer account must be active");
        }

        String expectedOldPublicId = user.getProfileImagePublicId();
        ProfileImageCleanupTask oldCleanupTask = null;
        if (expectedOldPublicId != null && !expectedOldPublicId.isBlank()) {
            oldCleanupTask = cleanupService.schedule(
                    expectedOldPublicId, customerId, ProfileImageCleanupReason.REPLACED);
        }

        // 1. Upload new image
        StoredProfileImage stored = profileImageStorage.upload(customerId, file);

        // 2. Conditional targeted $set of profileImageUrl + profileImagePublicId
        try {
            Query query = Query.query(
                    Criteria.where("_id").is(customerId)
                            .and("role").is(Role.CUSTOMER)
                            .and("status").is(UserStatus.ACTIVE)
                            .and("profileImagePublicId").is(expectedOldPublicId)
            );
            Update update = new Update()
                    .set("profileImageUrl", stored.secureUrl())
                    .set("profileImagePublicId", stored.publicId());
            UpdateResult result = mongoTemplate.updateFirst(query, update, User.class);

            if (result.getMatchedCount() == 1) {
                if (oldCleanupTask != null) {
                    cleanupService.attempt(oldCleanupTask);
                }
            } else {
                // Matched count 0: race condition, state change, or user not found
                ProfileImageCleanupTask rollbackTask = cleanupService.schedule(
                        stored.publicId(), customerId, ProfileImageCleanupReason.CONFLICT_ROLLBACK);
                if (rollbackTask != null) {
                    cleanupService.attempt(rollbackTask);
                }
                if (expectedOldPublicId != null && !expectedOldPublicId.isBlank()) {
                    cleanupService.cancel(expectedOldPublicId);
                }

                User reloaded = userInternalService.getById(customerId);
                if (reloaded.getRole() != Role.CUSTOMER || reloaded.getStatus() != UserStatus.ACTIVE) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Customer account must be active");
                }
                throw new ResourceConflictException("PROFILE_PHOTO_CONFLICT",
                        "Your profile photo was changed by another request. Please retry.");
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            // DB error / Mongo exception: rollback newly uploaded image
            ProfileImageCleanupTask rollbackTask = cleanupService.schedule(
                    stored.publicId(), customerId, ProfileImageCleanupReason.UPLOAD_ROLLBACK);
            if (rollbackTask != null) {
                cleanupService.attempt(rollbackTask);
            }
            if (expectedOldPublicId != null && !expectedOldPublicId.isBlank()) {
                cleanupService.cancel(expectedOldPublicId);
            }
            log.warn("DB update failed for customer profile photo: userId={}, error={}",
                    customerId, ex.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROFILE_PHOTO_UPDATE_FAILED",
                    "Failed to save profile photo. Please try again.");
        }

        User updated = userInternalService.getById(customerId);
        return toProfileResponse(updated);
    }

    @Override
    public void deleteProfilePhoto(String customerId) {
        User user = userInternalService.getById(customerId);
        if (user.getRole() != Role.CUSTOMER || user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Customer account must be active");
        }

        String expectedPublicId = user.getProfileImagePublicId();
        if (expectedPublicId == null || expectedPublicId.isBlank()) {
            return;
        }

        // Schedule cleanup task BEFORE update
        ProfileImageCleanupTask cleanupTask = cleanupService.schedule(
                expectedPublicId, customerId, ProfileImageCleanupReason.REMOVED);

        // Targeted conditional $unset
        try {
            Query query = Query.query(
                    Criteria.where("_id").is(customerId)
                            .and("role").is(Role.CUSTOMER)
                            .and("status").is(UserStatus.ACTIVE)
                            .and("profileImagePublicId").is(expectedPublicId)
            );
            Update update = new Update()
                    .unset("profileImageUrl")
                    .unset("profileImagePublicId");
            UpdateResult result = mongoTemplate.updateFirst(query, update, User.class);

            if (result.getMatchedCount() == 1) {
                if (cleanupTask != null) {
                    cleanupService.attempt(cleanupTask);
                }
            } else {
                User reloaded = userInternalService.getById(customerId);
                if (reloaded.getRole() != Role.CUSTOMER || reloaded.getStatus() != UserStatus.ACTIVE) {
                    cleanupService.cancel(expectedPublicId);
                    throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "Customer account must be active");
                }
                String currentPublicId = reloaded.getProfileImagePublicId();
                if (currentPublicId == null || currentPublicId.isBlank()) {
                    return; // Already removed concurrently
                }
                cleanupService.cancel(expectedPublicId);
                throw new ResourceConflictException("PROFILE_PHOTO_CONFLICT",
                        "Your profile photo was changed by another request. Please retry.");
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            cleanupService.cancel(expectedPublicId);
            log.warn("DB unset failed for customer profile photo: userId={}, error={}",
                    customerId, ex.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROFILE_PHOTO_DELETE_FAILED",
                    "Failed to delete profile photo. Please try again.");
        }
    }

    private CustomerProfileResponse toProfileResponse(User user) {
        return new CustomerProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.getProfileImageUrl()
        );
    }
}
