package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.customer.dto.DeactivateAccountRequest;
import com.gamagerecruiters.marketplace.modules.customer.repository.CustomerAddressRepository;

@ExtendWith(MockitoExtension.class)
class CustomerDeactivationTest {

    @Mock
    private UserInternalService userInternalService;

    @Mock
    private CustomerAddressRepository customerAddressRepository;

    @Mock
    private DistrictCatalog districtCatalog;

    @Mock
    private OpenBookingsChecker openBookingsChecker;

    private CustomerServiceImpl customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerServiceImpl(
                userInternalService,
                customerAddressRepository,
                districtCatalog,
                openBookingsChecker,
                10
        );
    }

    @Test
    @DisplayName("DefaultOpenBookingsChecker: Fails closed with 503 SERVICE_UNAVAILABLE")
    void defaultOpenBookingsCheckerShouldFailClosedWith503() {
        DefaultOpenBookingsChecker defaultChecker = new DefaultOpenBookingsChecker();
        ApiException ex = assertThrows(
                ApiException.class,
                () -> defaultChecker.hasOpenBookings("cust-1")
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DEACTIVATION_UNAVAILABLE", ex.getErrorCode());
        assertEquals("Account deactivation is temporarily unavailable", ex.getMessage());
    }

    @Test
    @DisplayName("deactivateAccount: Success when password matches and openBookingsChecker returns false")
    void shouldDeactivateAccountSuccessfully() {
        when(openBookingsChecker.hasOpenBookings("cust-1")).thenReturn(false);

        customerService.deactivateAccount("cust-1", new DeactivateAccountRequest("ValidPassword123!"));

        verify(userInternalService).verifyPassword("cust-1", "ValidPassword123!");
        verify(openBookingsChecker).hasOpenBookings("cust-1");
        verify(userInternalService).deactivateOwnAccount("cust-1");
    }

    @Test
    @DisplayName("deactivateAccount: Returns 409 when customer has open bookings and does not deactivate")
    void shouldRejectDeactivationWhenOpenBookingsExist() {
        when(openBookingsChecker.hasOpenBookings("cust-1")).thenReturn(true);

        ApiException ex = assertThrows(
                ApiException.class,
                () -> customerService.deactivateAccount("cust-1", new DeactivateAccountRequest("ValidPassword123!"))
        );
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OPEN_BOOKINGS_EXIST", ex.getErrorCode());
        assertEquals("You cannot deactivate your account while you have active bookings", ex.getMessage());

        verify(userInternalService).verifyPassword("cust-1", "ValidPassword123!");
        verify(openBookingsChecker).hasOpenBookings("cust-1");
        verify(userInternalService, never()).deactivateOwnAccount(any());
    }

    @Test
    @DisplayName("deactivateAccount: Fails fast on wrong password confirmation before checking bookings")
    void shouldFailFastOnWrongPassword() {
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Password confirmation is incorrect"))
                .when(userInternalService).verifyPassword("cust-1", "WrongPass");

        ApiException ex = assertThrows(
                ApiException.class,
                () -> customerService.deactivateAccount("cust-1", new DeactivateAccountRequest("WrongPass"))
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_PASSWORD", ex.getErrorCode());

        verify(openBookingsChecker, never()).hasOpenBookings(any());
        verify(userInternalService, never()).deactivateOwnAccount(any());
    }

    @Test
    @DisplayName("deactivateAccount: Propagates 503 when fail-closed DefaultOpenBookingsChecker is used")
    void shouldPropagate503FromDefaultChecker() {
        CustomerServiceImpl serviceWithDefaultChecker = new CustomerServiceImpl(
                userInternalService,
                customerAddressRepository,
                districtCatalog,
                new DefaultOpenBookingsChecker(),
                10
        );

        ApiException ex = assertThrows(
                ApiException.class,
                () -> serviceWithDefaultChecker.deactivateAccount("cust-1", new DeactivateAccountRequest("ValidPass123!"))
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DEACTIVATION_UNAVAILABLE", ex.getErrorCode());
        verify(userInternalService, never()).deactivateOwnAccount(any());
    }
}
