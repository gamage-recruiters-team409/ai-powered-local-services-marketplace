package com.gamagerecruiters.marketplace.modules.customer.service;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.customer.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerProfileResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.DeactivateAccountRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.UpdateCustomerProfileRequest;

public interface CustomerService {

    CustomerProfileResponse getProfile(String customerId);

    CustomerProfileResponse updateProfile(String customerId, UpdateCustomerProfileRequest request);

    void changePassword(String customerId, ChangePasswordRequest request);

    List<CustomerAddressResponse> getAddresses(String customerId);

    CustomerAddressResponse createAddress(String customerId, CustomerAddressRequest request);

    CustomerAddressResponse updateAddress(String customerId, String addressId, CustomerAddressRequest request);

    void deleteAddress(String customerId, String addressId);

    CustomerAddressResponse setDefaultAddress(String customerId, String addressId);

    void deactivateAccount(String customerId, DeactivateAccountRequest request);
}
