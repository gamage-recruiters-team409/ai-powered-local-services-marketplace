package com.gamagerecruiters.marketplace.modules.auth.service;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.AdminRolePolicy;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;

@Service
public class UserInternalServiceImpl implements UserInternalService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserInternalServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public User getById(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found with id: " + userId));
    }

    @Override
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public User changeStatus(String userId, UserStatus status) {
        String currentUserId = CurrentUser.getUserId();
        if (currentUserId == null) {
            throw new AccessDeniedException("Authentication required: actor could not be resolved from security context");
        }

        User actor = userRepository.findById(currentUserId)
                .orElseThrow(() -> new AccessDeniedException("Actor user not found in database"));

        User target = getById(userId);

        AdminRolePolicy.validateStatusChange(actor, target, status, userRepository);

        target.setStatus(status);
        if (status == UserStatus.ACTIVE) {
            target.setFailedLoginCount(0);
            target.setLockStartedAt(null);
        }
        return userRepository.save(target);
    }

    @Override
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public User createAdminAccount(String name, String email, String password, Role role) {
        if (role == Role.SUPER_ADMIN) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ROLE",
                    "SUPER_ADMIN accounts cannot be created via API. They are initialized via startup seed only."
            );
        }

        if (!Roles.ADMIN_ROLES.contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Assigned role must be an administrative role");
        }

        String currentUserId = CurrentUser.getUserId();
        if (currentUserId != null) {
            User actor = userRepository.findById(currentUserId)
                    .orElseThrow(() -> new AccessDeniedException("Actor user not found in database"));
            if (actor.getRole() != Role.SUPER_ADMIN || actor.getStatus() != UserStatus.ACTIVE) {
                throw new AccessDeniedException("Only an active SUPER_ADMIN may create administrative accounts");
            }
        }

        String normalizedEmail = email.toLowerCase().trim();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ResourceConflictException("EMAIL_ALREADY_EXISTS", "A user with this email already exists");
        }

        User admin = new User();
        admin.setName(name.trim());
        admin.setEmail(normalizedEmail);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(role);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setEmailVerified(true);
        admin.setFailedLoginCount(0);
        admin.setCreatedAt(Instant.now());

        return userRepository.save(admin);
    }
}
