package com.gamagerecruiters.marketplace.modules.auth.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.AdminRolePolicy;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.customer.util.SriLankanPhoneUtil;

@Service
public class UserInternalServiceImpl implements UserInternalService {

    private static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MongoTemplate mongoTemplate;

    public UserInternalServiceImpl(
            UserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            PasswordEncoder passwordEncoder,
            MongoTemplate mongoTemplate
    ) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mongoTemplate = mongoTemplate;
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
        admin.setMustChangePassword(true);

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
        user.setMustChangePassword(false);
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

    @Override
    public Page<AdminUserSummary> searchUsers(String actorId, Set<Role> roles, UserStatus status, String nameOrEmail, Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Pagination is required; unpaged requests are not permitted");
        }

        User actor = validateActorForRead(actorId);
        Set<Role> visible = AdminRolePolicy.visibleRoles(actor.getRole());

        List<Criteria> criteriaList = new ArrayList<>();

        if (roles != null && !roles.isEmpty()) {
            for (Role role : roles) {
                if (!visible.contains(role)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_PERMITTED",
                            "Actor role '" + actor.getRole() + "' is not permitted to view role '" + role + "'");
                }
            }
            criteriaList.add(Criteria.where("role").in(roles));
        } else {
            // Unrestricted: limit to visible roles for non-SUPER_ADMIN
            if (actor.getRole() != Role.SUPER_ADMIN) {
                criteriaList.add(Criteria.where("role").in(visible));
            }
        }

        if (status != null) {
            criteriaList.add(Criteria.where("status").is(status));
        }

        if (nameOrEmail != null) {
            String trimmed = nameOrEmail.trim();
            if (!trimmed.isEmpty()) {
                String escaped = Pattern.quote(trimmed);
                criteriaList.add(new Criteria().orOperator(
                        Criteria.where("name").regex(escaped, "i"),
                        Criteria.where("email").regex(escaped, "i")
                ));
            }
        }

        Query query = new Query();
        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }

        // Exact same criteria used for total count
        long total = mongoTemplate.count(query, User.class);

        // Cap page size at 100, minimum 1
        int pageSize = Math.min(Math.max(1, pageable.getPageSize()), 100);
        int pageNumber = Math.max(0, pageable.getPageNumber());

        // Sort whitelist: only createdAt, fullName (maps to stored field "name"), or email
        Sort sanitizedSort = null;
        if (pageable.getSort().isSorted()) {
            List<Sort.Order> validOrders = new ArrayList<>();
            boolean hasInvalid = false;
            for (Sort.Order order : pageable.getSort()) {
                String prop = order.getProperty();
                if ("fullName".equalsIgnoreCase(prop) || "name".equalsIgnoreCase(prop)) {
                    validOrders.add(new Sort.Order(order.getDirection(), "name"));
                } else if ("email".equalsIgnoreCase(prop)) {
                    validOrders.add(new Sort.Order(order.getDirection(), "email"));
                } else if ("createdAt".equalsIgnoreCase(prop)) {
                    validOrders.add(new Sort.Order(order.getDirection(), "createdAt"));
                } else {
                    hasInvalid = true;
                    break;
                }
            }
            if (!hasInvalid && !validOrders.isEmpty()) {
                sanitizedSort = Sort.by(validOrders);
            }
        }

        if (sanitizedSort == null) {
            sanitizedSort = Sort.by(Sort.Direction.DESC, "createdAt");
        }

        Pageable sanitizedPageable = PageRequest.of(pageNumber, pageSize, sanitizedSort);
        query.with(sanitizedPageable);

        List<User> users = mongoTemplate.find(query, User.class);
        List<AdminUserSummary> summaries = users.stream().map(AdminUserSummary::fromUser).toList();

        return new PageImpl<>(summaries, sanitizedPageable, total);
    }

    @Override
    public long countByRoleAndStatus(String actorId, Role role, UserStatus status) {
        User actor = validateActorForRead(actorId);
        Set<Role> visible = AdminRolePolicy.visibleRoles(actor.getRole());

        if (role != null) {
            if (!visible.contains(role)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_PERMITTED",
                        "Actor role '" + actor.getRole() + "' is not permitted to view role '" + role + "'");
            }
            if (status != null) {
                return userRepository.countByRoleAndStatus(role, status);
            }
            Query query = new Query();
            query.addCriteria(Criteria.where("role").is(role));
            return mongoTemplate.count(query, User.class);
        }

        Query query = new Query();
        if (actor.getRole() != Role.SUPER_ADMIN) {
            query.addCriteria(Criteria.where("role").in(visible));
        }
        if (status != null) {
            query.addCriteria(Criteria.where("status").is(status));
        }
        return mongoTemplate.count(query, User.class);
    }

    private User validateActorForRead(String actorId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                    "Authentication required: user could not be resolved from security context");
        }

        if (actorId == null || actorId.trim().isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACTOR_NOT_FOUND",
                    "Authentication required: actor could not be resolved");
        }

        String authenticatedUserId = principal.getUserId();
        if (!actorId.equals(authenticatedUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACTOR_IDENTITY_MISMATCH",
                    "Supplied actor ID does not match authenticated user ID");
        }

        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "ACTOR_NOT_FOUND",
                        "Actor user not found in database"));
        if (actor.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACTOR_ACCOUNT_INACTIVE",
                    "Actor account must be active to view users");
        }
        Set<Role> visible = AdminRolePolicy.visibleRoles(actor.getRole());
        if (visible.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_PERMITTED",
                    "Actor role '" + actor.getRole() + "' is not permitted to view users");
        }
        return actor;
    }
}
