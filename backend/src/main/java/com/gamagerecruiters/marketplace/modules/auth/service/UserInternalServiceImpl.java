package com.gamagerecruiters.marketplace.modules.auth.service;

import java.time.Instant;
import java.util.regex.Pattern;

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
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.AdminRolePolicy;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.customer.util.SriLankanPhoneUtil;

@Service
public class UserInternalServiceImpl implements UserInternalService {

    private static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public UserInternalServiceImpl(
            UserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
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

    @Override
    public User updateContactDetails(String userId, String fullName, String phone) {
        User user = getById(userId);

        String normalizedPhone = SriLankanPhoneUtil.normalize(phone);

        userRepository.findByPhone(normalizedPhone).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) {
                throw new ResourceConflictException("PHONE_ALREADY_EXISTS", "Phone number is already in use");
            }
        });

        user.setName(fullName.trim());
        user.setPhone(normalizedPhone);
        return userRepository.save(user);
    }

    @Override
    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = getById(userId);

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }

        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_PASSWORD", "New password cannot be the same as the current password");
        }

        if (newPassword == null || !PASSWORD_POLICY.matcher(newPassword).matches()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "WEAK_PASSWORD",
                    "Password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, and one digit"
            );
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        authTokenRepository.revokeAllActiveTokensByUserId(userId);
    }

    @Override
    public void verifyPassword(String userId, String rawPassword) {
        User user = getById(userId);
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Password confirmation is incorrect");
        }
    }

    @Override
    public void deactivateOwnAccount(String userId) {
        User user = getById(userId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_NOT_ACTIVE", "Only active accounts can be deactivated");
        }
        user.setStatus(UserStatus.DEACTIVATED);
        userRepository.save(user);
        authTokenRepository.revokeAllActiveTokensByUserId(userId);
    }
}
