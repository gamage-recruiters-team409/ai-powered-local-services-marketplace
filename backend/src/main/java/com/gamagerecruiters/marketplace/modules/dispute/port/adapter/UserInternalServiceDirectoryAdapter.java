package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.dispute.port.UserDirectoryPort;

/**
 * Reads display names through the auth module's cross-module {@link UserInternalService}. Only the name leaves this
 * class; the rest of the account (email, phone, password hash, status) is never copied or returned.
 */
@Component
public class UserInternalServiceDirectoryAdapter implements UserDirectoryPort {

    private static final Logger log = LoggerFactory.getLogger(UserInternalServiceDirectoryAdapter.class);

    private final UserInternalService userInternalService;

    public UserInternalServiceDirectoryAdapter(UserInternalService userInternalService) {
        this.userInternalService = userInternalService;
    }

    @Override
    public Optional<String> findDisplayName(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            User user = userInternalService.getById(userId);
            return Optional.ofNullable(user == null ? null : user.getName()).filter(name -> !name.isBlank());
        } catch (RuntimeException e) {
            log.warn("event=DISPUTE_USER_NAME_UNAVAILABLE userId={} cause={}", userId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
