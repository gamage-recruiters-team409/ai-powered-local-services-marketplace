package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.beans.factory.ObjectProvider;
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
    @DisplayName("deactivateAccount: Fails closed with 503 SERVICE_UNAVAILABLE when OpenBookingsChecker bean is missing")
    void shouldFailClosedWith503WhenNoOpenBookingsCheckerBean() {
        when(openBookingsCheckerProvider.getIfAvailable()).thenReturn(null);

        ApiException ex = assertThrows(
                ApiException.class,
                () -> customerService.deactivateAccount("cust-1", new DeactivateAccountRequest("ValidPassword123!"))
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DEACTIVATION_UNAVAILABLE", ex.getErrorCode());
        assertEquals("Account deactivation is temporarily unavailable", ex.getMessage());

        verify(userInternalService).verifyPassword("cust-1", "ValidPassword123!");
        verify(userInternalService, never()).deactivateOwnAccount(any());
    }

    @Test
    @DisplayName("deactivateAccount: Success when password matches and openBookingsChecker returns false")
    void shouldDeactivateAccountSuccessfully() {
        when(openBookingsCheckerProvider.getIfAvailable()).thenReturn(openBookingsChecker);
        when(openBookingsChecker.hasOpenBookings("cust-1")).thenReturn(false);

        customerService.deactivateAccount("cust-1", new DeactivateAccountRequest("ValidPassword123!"));

        verify(userInternalService).verifyPassword("cust-1", "ValidPassword123!");
        verify(openBookingsChecker).hasOpenBookings("cust-1");
        verify(userInternalService).deactivateOwnAccount("cust-1");
    }

    @Test
    @DisplayName("deactivateAccount: Returns 409 when customer has open bookings and does not deactivate")
    void shouldRejectDeactivationWhenOpenBookingsExist() {
        when(openBookingsCheckerProvider.getIfAvailable()).thenReturn(openBookingsChecker);
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
    @DisplayName("deactivateAccount: Fails fast on wrong password confirmation before checking bookings provider")
    void shouldFailFastOnWrongPassword() {
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Password confirmation is incorrect"))
                .when(userInternalService).verifyPassword("cust-1", "WrongPass");

        ApiException ex = assertThrows(
                ApiException.class,
                () -> customerService.deactivateAccount("cust-1", new DeactivateAccountRequest("WrongPass"))
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_PASSWORD", ex.getErrorCode());

        verify(openBookingsCheckerProvider, never()).getIfAvailable();
        verify(userInternalService, never()).deactivateOwnAccount(any());
    }
}
