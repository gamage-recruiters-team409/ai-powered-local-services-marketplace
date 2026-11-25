package com.gamagerecruiters.marketplace.modules.adminusers.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

/**
 * One row of the admin users list. {@code pro} is present only when the provider module can tell (today it
 * cannot, so it is omitted). Never contains password hashes, tokens or security counters.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminUserRow(
        String id,
        String displayName,
        String email,
        Role role,
        Instant joinedAt,
        UserStatus status,
        Boolean pro
) {
}
