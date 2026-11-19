package com.gamagerecruiters.marketplace.modules.adminusers.dto;

/**
 * Body of suspend / reactivate. {@code reason} is required, 10 to 500 characters after trimming; it is checked by
 * the service (so a rejected value is never echoed or logged) and stored in the admin audit log.
 */
public record AccountActionRequest(String reason) {
}
