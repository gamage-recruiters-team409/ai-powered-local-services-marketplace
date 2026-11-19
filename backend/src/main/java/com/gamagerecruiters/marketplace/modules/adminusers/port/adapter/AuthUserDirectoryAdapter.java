package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/**
 * UserDirectoryPort through the auth module's UserInternalService only (searchUsers, getById, countByRoleAndStatus,
 * changeStatus). Only safe fields are copied out of User: never passwordHash, lock or login counters.
 *
 * <p>Not available in the auth module yet (answer empty, listed as a WP01 dependency): counts by join date
 * (registrations, weekly change) and last-active time.</p>
 */
@Component
public class AuthUserDirectoryAdapter implements UserDirectoryPort {

    private final UserInternalService userInternalService;

    public AuthUserDirectoryAdapter(UserInternalService userInternalService) {
        this.userInternalService = userInternalService;
    }

    @Override
    public Page<UserAccount> search(String actorId, Set<Role> roles, UserStatus status, String nameOrEmail,
                                    Pageable pageable) {
        return userInternalService.searchUsers(actorId, roles, status, nameOrEmail, pageable)
                .map(AuthUserDirectoryAdapter::fromSummary);
    }

    @Override
    public Optional<UserAccount> findById(String userId) {
        try {
            return Optional.of(fromUser(userInternalService.getById(userId)));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public long count(String actorId, Role role, UserStatus status) {
        return userInternalService.countByRoleAndStatus(actorId, role, status);
    }

    @Override
    public Optional<Long> joinedSince(String actorId, Role role, Instant since) {
        return Optional.empty();
    }

    @Override
    public Optional<List<RegistrationCount>> registrations(String actorId, List<Bucket> buckets) {
        return Optional.empty();
    }

    @Override
    public Optional<Instant> lastActiveAt(String userId) {
        return Optional.empty();
    }

    @Override
    public boolean supportsAccountActions() {
        return true;
    }

    @Override
    public UserAccount changeStatus(String userId, UserStatus status) {
        return fromUser(userInternalService.changeStatus(userId, status));
    }

    /** The search result has no email-verified flag; it is only needed on the account page (findById). */
    private static UserAccount fromSummary(AdminUserSummary s) {
        return new UserAccount(s.id(), s.fullName(), s.email(), s.phone(), s.role(), s.status(), false, s.createdAt());
    }

    private static UserAccount fromUser(User u) {
        return new UserAccount(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getRole(), u.getStatus(),
                u.isEmailVerified(), u.getCreatedAt());
    }
}
