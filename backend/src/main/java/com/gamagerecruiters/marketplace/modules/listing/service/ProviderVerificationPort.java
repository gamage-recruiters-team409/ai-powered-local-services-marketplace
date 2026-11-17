package com.gamagerecruiters.marketplace.modules.listing.service;

/**
 * Optional cross-module port supplied by the provider verification module (WP02) once merged.
 */
public interface ProviderVerificationPort {

    boolean isProviderVerified(String providerUserId);
}
