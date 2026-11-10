package com.gamagerecruiters.marketplace.modules.auth.security;

import org.springframework.security.access.AccessDeniedException;

public final class OwnershipChecker {

    private OwnershipChecker() {
    }

    public static void assertOwner(String currentUserId, String ownerId) {
        if (currentUserId == null || ownerId == null || !currentUserId.equals(ownerId)) {
            throw new AccessDeniedException("Access denied: current user is not the owner of this resource");
        }
    }
}
