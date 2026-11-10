package com.gamagerecruiters.marketplace.modules.customer.service;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerAddress;
import com.gamagerecruiters.marketplace.modules.customer.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerProfileResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.UpdateCustomerProfileRequest;
import com.gamagerecruiters.marketplace.modules.customer.repository.CustomerAddressRepository;

@Service
public class CustomerServiceImpl implements CustomerService {

    private final UserInternalService userInternalService;
    private final CustomerAddressRepository customerAddressRepository;
    private final DistrictCatalog districtCatalog;
    private final int maxAddresses;

    public CustomerServiceImpl(
            UserInternalService userInternalService,
            CustomerAddressRepository customerAddressRepository,
            DistrictCatalog districtCatalog,
            @Value("${app.customer.max-addresses:10}") int maxAddresses
    ) {
        this.userInternalService = userInternalService;
        this.customerAddressRepository = customerAddressRepository;
        this.districtCatalog = districtCatalog;
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
        return customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc(customerId).stream()
                .map(CustomerAddressResponse::fromEntity)
                .toList();
    }

    @Override
    public CustomerAddressResponse createAddress(String customerId, CustomerAddressRequest request) {
        validateDistrict(request.district());

        long count = customerAddressRepository.countByCustomerId(customerId);
        if (count >= maxAddresses) {
            throw new ResourceConflictException(
                    "MAX_ADDRESSES_REACHED",
                    "Maximum number of saved addresses (" + maxAddresses + ") reached"
            );
        }

        boolean makeDefault = count == 0 || Boolean.TRUE.equals(request.isDefault());

        if (makeDefault && count > 0) {
            unsetDefaultAddress(customerId);
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

        CustomerAddress saved = customerAddressRepository.save(address);
        return CustomerAddressResponse.fromEntity(saved);
    }

    @Override
    public CustomerAddressResponse updateAddress(String customerId, String addressId, CustomerAddressRequest request) {
        validateDistrict(request.district());

        CustomerAddress address = findCustomerAddressOrThrow(customerId, addressId);

        boolean requestedDefault = Boolean.TRUE.equals(request.isDefault());
        if (requestedDefault && !address.isDefault()) {
            unsetDefaultAddress(customerId);
            address.setDefault(true);
        }

        address.setLabel(request.label().trim());
        address.setAddressLine1(request.addressLine1().trim());
        address.setAddressLine2(request.addressLine2() != null ? request.addressLine2().trim() : null);
        address.setCity(request.city().trim());
        address.setDistrict(request.district().trim());
        address.setPostalCode(request.postalCode() != null && !request.postalCode().isBlank() ? request.postalCode().trim() : null);
        address.setUpdatedAt(Instant.now());

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
                customerAddressRepository.save(oldest);
            });
        }
    }

    @Override
    public CustomerAddressResponse setDefaultAddress(String customerId, String addressId) {
        CustomerAddress address = findCustomerAddressOrThrow(customerId, addressId);

        if (!address.isDefault()) {
            unsetDefaultAddress(customerId);
            address.setDefault(true);
            address.setUpdatedAt(Instant.now());
            address = customerAddressRepository.save(address);
        }

        return CustomerAddressResponse.fromEntity(address);
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

    private void unsetDefaultAddress(String customerId) {
        customerAddressRepository.findFirstByCustomerIdAndIsDefaultTrue(customerId).ifPresent(prev -> {
            prev.setDefault(false);
            prev.setUpdatedAt(Instant.now());
            customerAddressRepository.save(prev);
        });
    }

    private CustomerAddress findCustomerAddressOrThrow(String customerId, String addressId) {
        return customerAddressRepository.findByIdAndCustomerId(addressId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("ADDRESS_NOT_FOUND", "Address not found"));
    }

    private CustomerProfileResponse toProfileResponse(User user) {
        return new CustomerProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified()
        );
    }
}
