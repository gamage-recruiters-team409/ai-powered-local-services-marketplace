package com.gamagerecruiters.marketplace.modules.auth.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<UserPrincipal> getPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public static String getUserId() {
        return getPrincipal().map(UserPrincipal::getUserId).orElse(null);
    }

    public static String getEmail() {
        return getPrincipal().map(UserPrincipal::getEmail).orElse(null);
    }

    public static Role getRole() {
        return getPrincipal().map(UserPrincipal::getRole).orElse(null);
    }

    public static boolean isAuthenticated() {
        return getPrincipal().isPresent();
    }
}
