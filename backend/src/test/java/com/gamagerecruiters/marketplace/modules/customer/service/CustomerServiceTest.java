package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerAddress;
import com.gamagerecruiters.marketplace.modules.customer.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressRequest;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerAddressResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerProfileResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.UpdateCustomerProfileRequest;
import com.gamagerecruiters.marketplace.modules.customer.repository.CustomerAddressRepository;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private UserInternalService userInternalService;

    @Mock
    private CustomerAddressRepository customerAddressRepository;

    @Mock
    private DistrictCatalog districtCatalog;

    private CustomerServiceImpl customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerServiceImpl(
                userInternalService,
                customerAddressRepository,
                districtCatalog,
                10
        );
    }

    @Test
    @DisplayName("getProfile: Returns profile matching user in database")
    void shouldReturnCustomerProfile() {
        User user = new User();
        user.setId("c-1");
        user.setName("Kamal Perera");
        user.setEmail("kamal@marketplace.local");
        user.setPhone("+94771234567");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);

        when(userInternalService.getById("c-1")).thenReturn(user);

        CustomerProfileResponse res = customerService.getProfile("c-1");
        assertEquals("c-1", res.id());
        assertEquals("Kamal Perera", res.fullName());
        assertEquals("kamal@marketplace.local", res.email());
        assertEquals("+94771234567", res.phone());
        assertEquals(Role.CUSTOMER, res.role());
        assertEquals(UserStatus.ACTIVE, res.status());
        assertTrue(res.emailVerified());
    }

    @Test
    @DisplayName("updateProfile: Delegates contact details update to UserInternalService")
    void shouldUpdateProfile() {
        User updated = new User();
        updated.setId("c-1");
        updated.setName("Kamal Silva");
        updated.setEmail("kamal@marketplace.local");
        updated.setPhone("+94779876543");
        updated.setRole(Role.CUSTOMER);
        updated.setStatus(UserStatus.ACTIVE);
        updated.setEmailVerified(true);

        when(userInternalService.updateContactDetails("c-1", "Kamal Silva", "0779876543")).thenReturn(updated);

        CustomerProfileResponse res = customerService.updateProfile(
                "c-1",
                new UpdateCustomerProfileRequest("Kamal Silva", "0779876543")
        );

        assertEquals("Kamal Silva", res.fullName());
        assertEquals("+94779876543", res.phone());
        verify(userInternalService).updateContactDetails("c-1", "Kamal Silva", "0779876543");
    }

    @Test
    @DisplayName("changePassword: Delegates password change to UserInternalService")
    void shouldChangePassword() {
        customerService.changePassword("c-1", new ChangePasswordRequest("OldPass123!", "NewPass123!"));
        verify(userInternalService).changePassword("c-1", "OldPass123!", "NewPass123!");
    }

    @Test
    @DisplayName("createAddress: First address automatically becomes default")
    void shouldMakeFirstAddressDefault() {
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(customerAddressRepository.countByCustomerId("c-1")).thenReturn(0L);
        when(customerAddressRepository.save(any(CustomerAddress.class))).thenAnswer(inv -> {
            CustomerAddress a = inv.getArgument(0);
            a.setId("addr-1");
            return a;
        });

        CustomerAddressRequest req = new CustomerAddressRequest(
                "Home",
                "123 Galle Road",
                "Apt 4B",
                "Colombo 03",
                "Colombo",
                "00300",
                false // Explicitly passed false, but first address must be default!
        );

        CustomerAddressResponse res = customerService.createAddress("c-1", req);
        assertNotNull(res);
        assertTrue(res.isDefault(), "First saved address must automatically be marked as default");
    }

    @Test
    @DisplayName("createAddress: Subsequent default address unsets previous default")
    void shouldUnsetPreviousDefaultWhenNewDefaultCreated() {
        when(districtCatalog.isValidDistrict("Gampaha")).thenReturn(true);
        when(customerAddressRepository.countByCustomerId("c-1")).thenReturn(1L);

        CustomerAddress existingDefault = new CustomerAddress();
        existingDefault.setId("addr-1");
        existingDefault.setCustomerId("c-1");
        existingDefault.setDefault(true);

        when(customerAddressRepository.findFirstByCustomerIdAndIsDefaultTrue("c-1"))
                .thenReturn(Optional.of(existingDefault));
        when(customerAddressRepository.save(any(CustomerAddress.class))).thenAnswer(inv -> {
            CustomerAddress a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId("addr-2");
            }
            return a;
        });

        CustomerAddressRequest req = new CustomerAddressRequest(
                "Office",
                "456 Kandy Road",
                null,
                "Kelaniya",
                "Gampaha",
                "11600",
                true
        );

        CustomerAddressResponse res = customerService.createAddress("c-1", req);
        assertTrue(res.isDefault());
        assertFalse(existingDefault.isDefault(), "Previous default address must be unset");
        verify(customerAddressRepository).save(existingDefault);
    }

    @Test
    @DisplayName("createAddress: Exceeding max addresses limit returns 409 Conflict")
    void shouldRejectAddressWhenLimitReached() {
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(customerAddressRepository.countByCustomerId("c-1")).thenReturn(10L);

        CustomerAddressRequest req = new CustomerAddressRequest(
                "Work", "100 High Level Rd", null, "Nugegoda", "Colombo", null, false
        );

        ResourceConflictException ex = assertThrows(
                ResourceConflictException.class,
                () -> customerService.createAddress("c-1", req)
        );
        assertEquals("MAX_ADDRESSES_REACHED", ex.getErrorCode());
        verify(customerAddressRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAddress: Invalid district throws 400 Bad Request")
    void shouldRejectInvalidDistrictOnCreate() {
        when(districtCatalog.isValidDistrict("NotADistrict")).thenReturn(false);

        CustomerAddressRequest req = new CustomerAddressRequest(
                "Home", "123 Main St", null, "City", "NotADistrict", null, false
        );

        ApiException ex = assertThrows(
                ApiException.class,
                () -> customerService.createAddress("c-1", req)
        );
        assertEquals("INVALID_DISTRICT", ex.getErrorCode());
    }

    @Test
    @DisplayName("updateAddress: Updating address not owned by caller returns 404 Not Found")
    void shouldReturn404WhenUpdatingUnownedAddress() {
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(customerAddressRepository.findByIdAndCustomerId("addr-99", "c-1")).thenReturn(Optional.empty());

        CustomerAddressRequest req = new CustomerAddressRequest(
                "Home", "Updated St", null, "Colombo", "Colombo", null, false
        );

        assertThrows(
                ResourceNotFoundException.class,
                () -> customerService.updateAddress("c-1", "addr-99", req)
        );
    }

    @Test
    @DisplayName("deleteAddress: Deleting default address promotes oldest remaining address")
    void shouldPromoteOldestRemainingAddressOnDefaultDelete() {
        CustomerAddress addressToDelete = new CustomerAddress();
        addressToDelete.setId("addr-1");
        addressToDelete.setCustomerId("c-1");
        addressToDelete.setDefault(true);

        CustomerAddress oldestRemaining = new CustomerAddress();
        oldestRemaining.setId("addr-2");
        oldestRemaining.setCustomerId("c-1");
        oldestRemaining.setDefault(false);
        oldestRemaining.setCreatedAt(Instant.parse("2026-11-01T10:00:00Z"));

        when(customerAddressRepository.findByIdAndCustomerId("addr-1", "c-1")).thenReturn(Optional.of(addressToDelete));
        when(customerAddressRepository.findFirstByCustomerIdOrderByCreatedAtAsc("c-1")).thenReturn(Optional.of(oldestRemaining));

        customerService.deleteAddress("c-1", "addr-1");

        verify(customerAddressRepository).delete(addressToDelete);
        assertTrue(oldestRemaining.isDefault(), "Oldest remaining address must be promoted to default");
        verify(customerAddressRepository).save(oldestRemaining);
    }

    @Test
    @DisplayName("deleteAddress: Deleting address not owned by caller returns 404")
    void shouldReturn404WhenDeletingUnownedAddress() {
        when(customerAddressRepository.findByIdAndCustomerId("addr-other", "c-1")).thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> customerService.deleteAddress("c-1", "addr-other")
        );
        verify(customerAddressRepository, never()).delete(any());
    }

    @Test
    @DisplayName("setDefaultAddress: Unsets previous default and marks target address default")
    void shouldSetTargetAddressAsDefault() {
        CustomerAddress currentDefault = new CustomerAddress();
        currentDefault.setId("addr-1");
        currentDefault.setCustomerId("c-1");
        currentDefault.setDefault(true);

        CustomerAddress target = new CustomerAddress();
        target.setId("addr-2");
        target.setCustomerId("c-1");
        target.setDefault(false);

        when(customerAddressRepository.findByIdAndCustomerId("addr-2", "c-1")).thenReturn(Optional.of(target));
        when(customerAddressRepository.findFirstByCustomerIdAndIsDefaultTrue("c-1")).thenReturn(Optional.of(currentDefault));
        when(customerAddressRepository.save(any(CustomerAddress.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerAddressResponse res = customerService.setDefaultAddress("c-1", "addr-2");

        assertTrue(target.isDefault());
        assertFalse(currentDefault.isDefault());
        verify(customerAddressRepository).save(currentDefault);
        verify(customerAddressRepository).save(target);
    }
}
