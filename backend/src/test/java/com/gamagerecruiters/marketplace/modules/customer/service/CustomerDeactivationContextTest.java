package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.customer.dto.DeactivateAccountRequest;
import com.gamagerecruiters.marketplace.modules.customer.repository.CustomerAddressRepository;

class CustomerDeactivationContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CustomerServiceImpl.class)
            .withBean(UserInternalService.class, () -> mock(UserInternalService.class))
            .withBean(CustomerAddressRepository.class, () -> mock(CustomerAddressRepository.class))
            .withBean(DistrictCatalog.class, () -> mock(DistrictCatalog.class));

    @Test
    @DisplayName("Context with NO OpenBookingsChecker bean: Starts cleanly and deactivateAccount returns 503")
    void contextWithNoOpenBookingsCheckerShouldReturn503() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OpenBookingsChecker.class);
            assertThat(context).doesNotHaveBean("defaultOpenBookingsChecker");

            CustomerService service = context.getBean(CustomerService.class);
            ApiException ex = assertThrows(
                    ApiException.class,
                    () -> service.deactivateAccount("cust-1", new DeactivateAccountRequest("ValidPassword123!"))
            );
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
            assertEquals("DEACTIVATION_UNAVAILABLE", ex.getErrorCode());
        });
    }

    @Test
    @DisplayName("Context with stub OpenBookingsChecker bean (no open bookings): Deactivation succeeds")
    void contextWithStubOpenBookingsCheckerAllowingDeactivation() {
        OpenBookingsChecker stubChecker = customerId -> false;

        contextRunner
                .withBean(OpenBookingsChecker.class, () -> stubChecker)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenBookingsChecker.class);

                    CustomerService service = context.getBean(CustomerService.class);
                    UserInternalService userInternalService = context.getBean(UserInternalService.class);

                    service.deactivateAccount("cust-1", new DeactivateAccountRequest("ValidPassword123!"));
                    verify(userInternalService).deactivateOwnAccount("cust-1");
                });
    }

    @Test
    @DisplayName("Context with stub OpenBookingsChecker bean (has open bookings): Returns 409 OPEN_BOOKINGS_EXIST")
    void contextWithStubOpenBookingsCheckerRejectingDeactivation() {
        OpenBookingsChecker stubChecker = customerId -> true;

        contextRunner
                .withBean(OpenBookingsChecker.class, () -> stubChecker)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenBookingsChecker.class);

                    CustomerService service = context.getBean(CustomerService.class);
                    ApiException ex = assertThrows(
                            ApiException.class,
                            () -> service.deactivateAccount("cust-1", new DeactivateAccountRequest("ValidPassword123!"))
                    );
                    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
                    assertEquals("OPEN_BOOKINGS_EXIST", ex.getErrorCode());
                });
    }

    @Test
    @DisplayName("Context with real BookingOpenBookingsChecker bean: Deactivates cleanly when facade has no open bookings")
    void contextWithBookingOpenBookingsCheckerBean() {
        BookingFacade mockFacade = mock(BookingFacade.class);
        when(mockFacade.hasOpenBookingsForCustomer("cust-1")).thenReturn(false);

        contextRunner
                .withUserConfiguration(BookingOpenBookingsChecker.class)
                .withBean(BookingFacade.class, () -> mockFacade)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenBookingsChecker.class);

                    CustomerService service = context.getBean(CustomerService.class);
                    UserInternalService userInternalService = context.getBean(UserInternalService.class);

                    service.deactivateAccount("cust-1", new DeactivateAccountRequest("ValidPassword123!"));
                    verify(userInternalService).deactivateOwnAccount("cust-1");
                    verify(mockFacade).hasOpenBookingsForCustomer("cust-1");
                });
    }
}
