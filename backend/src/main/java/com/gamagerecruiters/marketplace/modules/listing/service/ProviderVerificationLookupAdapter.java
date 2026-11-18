package com.gamagerecruiters.marketplace.modules.listing.service;

import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationService;

/**
 * Cross-module adapter connecting {@link ProviderVerificationPort} to WP02's public
 * {@link ProviderVerificationService} without injecting foreign repositories.
 */
@Component
public class ProviderVerificationLookupAdapter implements ProviderVerificationPort {

    private final ProviderVerificationService providerVerificationService;

    public ProviderVerificationLookupAdapter(ProviderVerificationService providerVerificationService) {
        this.providerVerificationService = providerVerificationService;
    }

    @Override
    public boolean isProviderVerified(String providerUserId) {
        if (providerUserId == null || providerUserId.isBlank()) {
            return false;
        }
        String normalizedUserId = providerUserId.trim();

        // ProviderVerificationService resolves verification from CurrentUser; scope a temporary
        // provider principal only when another actor (e.g. a moderator approving a listing) checks eligibility.
        if (normalizedUserId.equals(CurrentUser.getUserId())) {
            return readCurrentProviderApproval();
        }

        SecurityContext originalContext = SecurityContextHolder.getContext();
        SecurityContext scopedContext = SecurityContextHolder.createEmptyContext();
        scopedContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(normalizedUserId, "", Role.PROVIDER),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + Role.PROVIDER.name()))
        ));
        SecurityContextHolder.setContext(scopedContext);
        try {
            return readCurrentProviderApproval();
        } finally {
            if (originalContext != null && originalContext.getAuthentication() != null) {
                SecurityContextHolder.setContext(originalContext);
            } else {
                SecurityContextHolder.clearContext();
            }
        }
    }

    private boolean readCurrentProviderApproval() {
        try {
            ProviderVerificationResponse response = providerVerificationService.getMyVerificationStatus();
            return response != null && response.getStatus() == ProviderVerificationStatus.APPROVED;
        } catch (ResourceNotFoundException ex) {
            return false;
        }
    }
}
