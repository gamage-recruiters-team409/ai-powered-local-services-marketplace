package com.gamagerecruiters.marketplace.modules.auth.dto;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * Principal representing an authenticated WebSocket client session.
 *
 * @param userId the unique identifier of the authenticated user
 * @param role   the current database role of the user
 */
public record WebSocketPrincipal(String userId, Role role) {

    public String getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }
}
