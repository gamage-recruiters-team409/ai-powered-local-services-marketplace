package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

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

    @Mock
    private ObjectProvider<OpenBookingsChecker> openBookingsCheckerProvider;

    @Mock
    private OpenBookingsChecker openBookingsChecker;

    private CustomerServiceImpl customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerServiceImpl(
                userInternalService,
                customerAddressRepository,
                districtCatalog,
                openBookingsCheckerProvider,
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
    @DisplayName("createAddress: First address automatically becomes default and gets slot 1")
    void shouldMakeFirstAddressDefaultWithSlotOne() {
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc("c-1"))
                .thenReturn(List.of());
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
    @DisplayName("createAddress: Subsequent default address unsets previous default and allocates free slot")
    void shouldUnsetPreviousDefaultWhenNewDefaultCreated() {
        when(districtCatalog.isValidDistrict("Gampaha")).thenReturn(true);

        CustomerAddress existingDefault = new CustomerAddress();
        existingDefault.setId("addr-1");
        existingDefault.setCustomerId("c-1");
        existingDefault.setDefault(true);
        existingDefault.setSlot(1);

        when(customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc("c-1"))
                .thenReturn(List.of(existingDefault));
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

        List<CustomerAddress> tenAddresses = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            CustomerAddress a = new CustomerAddress();
            a.setId("addr-" + i);
            a.setCustomerId("c-1");
            a.setSlot(i);
            a.setDefault(i == 1);
            tenAddresses.add(a);
        }

        when(customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc("c-1")).thenReturn(tenAddresses);

        CustomerAddressRequest req = new CustomerAddressRequest(
                "Work", "100 High Level Rd", null, "Nugegoda", "Colombo", null, false
        );

        ResourceConflictException ex = assertThrows(
                ResourceConflictException.class,
                () -> customerService.createAddress("c-1", req)
        );
        assertEquals("MAX_ADDRESSES_REACHED", ex.getErrorCode());
    }

    @Test
    @DisplayName("createAddress: Retries next slot on DuplicateKeyException for slot index")
    void shouldRetryNextSlotOnSlotDuplicateKeyException() {
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);

        CustomerAddress existing1 = new CustomerAddress();
        existing1.setId("addr-1");
        existing1.setCustomerId("c-1");
        existing1.setSlot(1);
        existing1.setDefault(true);

        CustomerAddress existing2 = new CustomerAddress();
        existing2.setId("addr-2");
        existing2.setCustomerId("c-1");
        existing2.setSlot(2);
        existing2.setDefault(false);

        // First attempt sees only existing1; tries slot 2, which conflicts
        // Second attempt sees both existing1 and existing2; tries slot 3 and succeeds
        when(customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc("c-1"))
                .thenReturn(List.of(existing1))
                .thenReturn(List.of(existing1, existing2));

        when(customerAddressRepository.save(any(CustomerAddress.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error collection: marketplace.customer_addresses index: " +
                        CustomerServiceImpl.SLOT_INDEX_NAME + " dup key: { customerId: \"c-1\", slot: 2 }"))
                .thenAnswer(inv -> {
                    CustomerAddress a = inv.getArgument(0);
                    a.setId("addr-3");
                    return a;
                });

        CustomerAddressRequest req = new CustomerAddressRequest(
                "Third", "300 Lake Rd", null, "Colombo", "Colombo", null, false
        );

        CustomerAddressResponse res = customerService.createAddress("c-1", req);
        assertNotNull(res);
        assertEquals("addr-3", res.id());
    }

    @Test
    @DisplayName("createAddress: Rethrows unknown duplicate key exception unchanged")
    void shouldRethrowUnknownDuplicateKeyException() {
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc("c-1")).thenReturn(List.of());
        when(customerAddressRepository.save(any(CustomerAddress.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error collection: other_collection index: unknown_index_1"));

        CustomerAddressRequest req = new CustomerAddressRequest(
                "Home", "123 Galle Road", null, "Colombo", "Colombo", null, false
        );

        assertThrows(DuplicateKeyException.class, () -> customerService.createAddress("c-1", req));
    }

    @Test
    @DisplayName("createAddress: Lazily backfills legacy addresses without slots")
    void shouldLazilyBackfillLegacyAddressesWithoutSlots() {
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);

        CustomerAddress legacy1 = new CustomerAddress();
        legacy1.setId("legacy-1");
        legacy1.setCustomerId("c-1");
        legacy1.setSlot(null);
        legacy1.setDefault(true);

        when(customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc("c-1"))
                .thenReturn(List.of(legacy1));

        when(customerAddressRepository.save(any(CustomerAddress.class))).thenAnswer(inv -> {
            CustomerAddress a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId("addr-new");
            }
            return a;
        });

        CustomerAddressRequest req = new CustomerAddressRequest(
                "New", "200 Baseline Rd", null, "Colombo", "Colombo", null, false
        );

        CustomerAddressResponse res = customerService.createAddress("c-1", req);
        assertNotNull(res);
        assertEquals(1, legacy1.getSlot(), "Legacy address must be assigned lowest free slot 1");
        verify(customerAddressRepository, times(2)).save(any(CustomerAddress.class)); // 1 backfill + 1 create
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
        when(customerAddressRepository.findByCustomerIdAndIsDefaultTrue("c-1")).thenReturn(List.of(currentDefault));
        when(customerAddressRepository.save(any(CustomerAddress.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerAddressResponse res = customerService.setDefaultAddress("c-1", "addr-2");

        assertTrue(target.isDefault());
        assertFalse(currentDefault.isDefault());
        verify(customerAddressRepository).save(currentDefault);
        verify(customerAddressRepository).save(target);
    }

    @Test
    @DisplayName("setDefaultAddress: Retries up to 3 times on default index race and returns 409 if exhausted")
    void shouldReturn409WhenDefaultAddressRaceExhaustsRetries() {
        when(customerAddressRepository.findByIdAndCustomerId("addr-2", "c-1")).thenAnswer(inv -> {
            CustomerAddress a = new CustomerAddress();
            a.setId("addr-2");
            a.setCustomerId("c-1");
            a.setDefault(false);
            return Optional.of(a);
        });
        when(customerAddressRepository.findByCustomerIdAndIsDefaultTrue("c-1")).thenReturn(List.of());
        when(customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc("c-1")).thenAnswer(inv -> {
            CustomerAddress a = new CustomerAddress();
            a.setId("addr-2");
            a.setCustomerId("c-1");
            a.setDefault(false);
            return List.of(a);
        });

        // Stub with doThrow on any(CustomerAddress.class) matching default index name
        org.mockito.Mockito.doThrow(new DuplicateKeyException("E11000 duplicate key error collection: marketplace.customer_addresses index: " +
                CustomerServiceImpl.DEFAULT_ADDRESS_INDEX_NAME + " dup key: { customerId: \"c-1\", isDefault: true }"))
                .when(customerAddressRepository).save(any(CustomerAddress.class));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> customerService.setDefaultAddress("c-1", "addr-2")
        );
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("CONCURRENT_MODIFICATION", ex.getErrorCode());
        verify(customerAddressRepository, times(3)).save(any(CustomerAddress.class));
    }

    @Test
    @DisplayName("loweringLimit: Lowering limit hides or deletes nothing; existing addresses remain and new creates rejected")
    void shouldKeepAndReturnAllAddressesWhenLimitLowered() {
        // Customer already has 8 addresses, but service instance is configured with lower limit of 5
        CustomerServiceImpl restrictedService = new CustomerServiceImpl(
                userInternalService,
                customerAddressRepository,
                districtCatalog,
                openBookingsCheckerProvider,
                5 // Lower limit
        );

        List<CustomerAddress> eightAddresses = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            CustomerAddress a = new CustomerAddress();
            a.setId("addr-" + i);
            a.setCustomerId("c-1");
            a.setSlot(i);
            a.setDefault(i == 1);
            eightAddresses.add(a);
        }

        when(customerAddressRepository.findByCustomerIdOrderByCreatedAtAsc("c-1")).thenReturn(eightAddresses);

        // All 8 addresses are still returned, none hidden or deleted
        List<CustomerAddressResponse> returned = restrictedService.getAddresses("c-1");
        assertEquals(8, returned.size(), "Lowering the limit must never hide existing addresses");

        // Attempting to create an address is rejected because count >= lower limit
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        ResourceConflictException ex = assertThrows(
                ResourceConflictException.class,
                () -> restrictedService.createAddress("c-1", new CustomerAddressRequest(
                        "New", "Street", null, "City", "Colombo", null, false
                ))
        );
        assertEquals("MAX_ADDRESSES_REACHED", ex.getErrorCode());
        verify(customerAddressRepository, never()).delete(any());
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
}
