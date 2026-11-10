package com.gamagerecruiters.marketplace.modules.customer.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ApiException;

/**
 * Fail-closed default implementation of {@link OpenBookingsChecker} used when
 * WP05 (Disura)'s BookingFacade is not present in the runtime classpath.
 * Always rejects deactivation with HTTP 503 Service Unavailable to ensure no
 * customer with active bookings can inadvertently deactivate their account.
 */
@Component
@ConditionalOnMissingBean(type = "com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade")
public class DefaultOpenBookingsChecker implements OpenBookingsChecker {

    @Override
    public boolean hasOpenBookings(String customerId) {
        throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DEACTIVATION_UNAVAILABLE",
                "Account deactivation is temporarily unavailable"
        );
    }
}
