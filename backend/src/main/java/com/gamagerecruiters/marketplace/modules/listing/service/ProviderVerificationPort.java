package com.gamagerecruiters.marketplace.modules.listing.service;

/**
 * Cross-module port supplied by the provider verification module (WP02) to verify
 * that a provider holds an {@code APPROVED} verification status before publishing listings.
 */
public interface ProviderVerificationPort {

    boolean isProviderVerified(String providerUserId);
}
