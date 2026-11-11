package com.gamagerecruiters.marketplace.modules.customer.service;

public interface OpenBookingsChecker {

    /**
     * Checks if the given customer currently has any active or open bookings.
     *
     * @param customerId ID of the customer
     * @return true if open bookings exist, false otherwise
     */
    boolean hasOpenBookings(String customerId);
}
