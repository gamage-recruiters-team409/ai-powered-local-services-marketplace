package com.gamagerecruiters.marketplace.modules.listing.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/**
 * Verifies active provider account status, role, and mandatory WP02 KYC approval before publishing listings.
 */
@Service
public class ProviderEligibilityCheckerImpl implements ProviderEligibilityChecker {

    private final UserInternalService userInternalService;
    private final ObjectProvider<ProviderVerificationPort> verificationPortProvider;

    public ProviderEligibilityCheckerImpl(
            UserInternalService userInternalService,
            ObjectProvider<ProviderVerificationPort> verificationPortProvider
    ) {
        this.userInternalService = userInternalService;
        this.verificationPortProvider = verificationPortProvider;
    }

    @Override
    public void assertEligibleToManageListing(String providerUserId, boolean publishing) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new AccessDeniedException("Authentication is required to manage listings");
        }

        User user = userInternalService.getById(providerUserId);
        if (user.getRole() != Role.PROVIDER) {
            throw new AccessDeniedException("Only provider accounts can manage service listings");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "PROVIDER_ACCOUNT_INACTIVE",
                    "Provider account must be ACTIVE to manage listings"
            );
        }

        if (publishing) {
            ProviderVerificationPort verificationPort = verificationPortProvider != null
                    ? verificationPortProvider.getIfAvailable()
                    : null;
            if (verificationPort == null) {
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "PROVIDER_VERIFICATION_UNAVAILABLE",
                        "Provider verification service is unavailable; listings cannot be published"
                );
            }
            if (!verificationPort.isProviderVerified(providerUserId)) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "PROVIDER_NOT_VERIFIED",
                        "Provider verification must be APPROVED before publishing listings"
                );
            }
        }
    }
}
