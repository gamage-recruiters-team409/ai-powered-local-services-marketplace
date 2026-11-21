package com.gamagerecruiters.marketplace.modules.listing.service;

/**
 * Verifies whether a provider user account is eligible to create, edit, or publish listings.
 */
public interface ProviderEligibilityChecker {

    void assertEligibleToManageListing(String providerUserId, boolean publishing);
}
