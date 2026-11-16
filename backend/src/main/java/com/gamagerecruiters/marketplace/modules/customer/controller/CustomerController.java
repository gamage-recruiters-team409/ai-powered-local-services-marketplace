package com.gamagerecruiters.marketplace.modules.customer.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.customer.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerProfileResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.DeactivateAccountRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.UpdateCustomerProfileRequest;
import com.gamagerecruiters.marketplace.modules.customer.service.CustomerService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerController {

    // TODO: Add rate limiting on customer endpoints (FR-CUST, deferred per specification)

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public ResponseEntity<CustomerProfileResponse> getProfile() {
        String currentUserId = CurrentUser.getUserId();
        return ResponseEntity.ok(customerService.getProfile(currentUserId));
    }

    @PutMapping
    public ResponseEntity<CustomerProfileResponse> updateProfile(@Valid @RequestBody UpdateCustomerProfileRequest request) {
        String currentUserId = CurrentUser.getUserId();
        return ResponseEntity.ok(customerService.updateProfile(currentUserId, request));
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        String currentUserId = CurrentUser.getUserId();
        customerService.changePassword(currentUserId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/addresses")
    public ResponseEntity<List<CustomerAddressResponse>> getAddresses() {
        String currentUserId = CurrentUser.getUserId();
        return ResponseEntity.ok(customerService.getAddresses(currentUserId));
    }

    @PostMapping("/addresses")
    public ResponseEntity<CustomerAddressResponse> createAddress(@Valid @RequestBody CustomerAddressRequest request) {
        String currentUserId = CurrentUser.getUserId();
        CustomerAddressResponse created = customerService.createAddress(currentUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/addresses/{addressId}")
    public ResponseEntity<CustomerAddressResponse> updateAddress(
            @PathVariable String addressId,
            @Valid @RequestBody CustomerAddressRequest request
    ) {
        String currentUserId = CurrentUser.getUserId();
        CustomerAddressResponse updated = customerService.updateAddress(currentUserId, addressId, request);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/addresses/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteAddress(@PathVariable String addressId) {
        String currentUserId = CurrentUser.getUserId();
        customerService.deleteAddress(currentUserId, addressId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/addresses/{addressId}/default")
    public ResponseEntity<CustomerAddressResponse> setDefaultAddress(@PathVariable String addressId) {
        String currentUserId = CurrentUser.getUserId();
        CustomerAddressResponse updated = customerService.setDefaultAddress(currentUserId, addressId);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deactivateAccount(@Valid @RequestBody DeactivateAccountRequest request) {
        String currentUserId = CurrentUser.getUserId();
        customerService.deactivateAccount(currentUserId, request);
        return ResponseEntity.noContent().build();
    }
}
