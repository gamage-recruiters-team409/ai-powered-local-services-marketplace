package com.gamagerecruiters.marketplace.modules.auth.service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.mongodb.MongoException;
import com.mongodb.client.result.UpdateResult;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;
import com.gamagerecruiters.marketplace.modules.auth.dto.Granularity;
import com.gamagerecruiters.marketplace.modules.auth.dto.PeriodCount;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.AdminRolePolicy;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.customer.util.SriLankanPhoneUtil;

@Service
public class UserInternalServiceImpl implements UserInternalService {

    private static final Logger log = LoggerFactory.getLogger(UserInternalServiceImpl.class);

    /**
     * Distributed lease-based guard with fencing tokens in collection {@code auth_locks}.
     * Serializes status modifications on SUPER_ADMIN accounts. Uses an atomic findAndModify
     * with $inc fence on acquire (unsetting legacy lockedUntil), an ownership proof before step 1,
     * local time bound enforcement, and couples the final guard proof, policy recheck, and user
     * status update inside a single MongoDB transaction (TransactionTemplate).
     * Release-in-finally matches _id, owner, and fence.
     */
    private static final String AUTH_LOCKS_COLLECTION = "auth_locks";
    private static final String SUPER_ADMIN_LOCK_ID = "super-admin-status";
    private static final int LOCK_ACQUISITION_RETRIES = 5;
    private static final long LOCK_RETRY_BACKOFF_MS = 50L;

    @Value("${app.auth.super-admin-lock-lease-ms:10000}")
    private long superAdminLockLeaseMs = 10000L;

    @Value("${app.auth.super-admin-lock-safety-margin-ms:3000}")
    private long superAdminLockSafetyMarginMs = 3000L;

    public record SuperAdminGuard(String owner, long fence, long lastProofNanoTime) {}

    private static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MongoTemplate mongoTemplate;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate transactionTemplate;

    public UserInternalServiceImpl(
            UserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            PasswordEncoder passwordEncoder,
            MongoTemplate mongoTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mongoTemplate = mongoTemplate;
        this.transactionManager = transactionManager;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    public void setSuperAdminLockLeaseMs(long superAdminLockLeaseMs) {
        this.superAdminLockLeaseMs = superAdminLockLeaseMs;
    }

    public void setSuperAdminLockSafetyMarginMs(long superAdminLockSafetyMarginMs) {
        this.superAdminLockSafetyMarginMs = superAdminLockSafetyMarginMs;
    }

    @Override
    public UserContact getContactById(String userId) {
        User user = getById(userId);
        return new UserContact(user.getId(), user.getName(), user.getEmail(), user.getStatus());
    }

    @Override
    public User getById(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found with id: " + userId));
    }

    @Override
    @Deprecated
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public User changeStatus(String userId, UserStatus status) {
        String actorId = CurrentUser.getUserId();
        User target = getById(userId);
        return changeStatus(actorId, userId, target.getStatus(), status);
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

    @Override
    public List<PeriodCount> countNewUsers(String actorId, Set<Role> roles, Instant from, Instant to, Granularity granularity) {
        if (from == null || to == null || granularity == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETERS",
                    "from, to, and granularity are required");
        }
        if (!from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "from must be before to");
        }

        User actor = validateActorForRead(actorId);
        Set<Role> visible = AdminRolePolicy.visibleRoles(actor.getRole());

        // Validate requested roles are within the actor's visibility scope
        if (roles != null && !roles.isEmpty()) {
            for (Role role : roles) {
                if (!visible.contains(role)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_PERMITTED",
                            "Actor role '" + actor.getRole() + "' is not permitted to view role '" + role + "'");
                }
            }
        }

        // Generate period starts and enforce MAX_NEW_USER_BUCKETS limit before running query or zero-filling
        List<Instant> periodStarts = generatePeriodStarts(from, to, granularity, MAX_NEW_USER_BUCKETS);

        // Build the match criteria
        List<Criteria> matchCriteria = new ArrayList<>();
        matchCriteria.add(Criteria.where("createdAt").gte(from).lt(to));

        if (roles != null && !roles.isEmpty()) {
            matchCriteria.add(Criteria.where("role").in(roles));
        } else if (actor.getRole() != Role.SUPER_ADMIN) {
            matchCriteria.add(Criteria.where("role").in(visible));
        }

        String unit = granularity == Granularity.WEEK ? "week" : "month";

        AggregationOperation matchOp = Aggregation.match(
                new Criteria().andOperator(matchCriteria.toArray(new Criteria[0]))
        );

        AggregationOperation groupOp = context -> new Document("$group",
                new Document("_id", new Document("$dateTrunc",
                        new Document("date", "$createdAt")
                                .append("unit", unit)
                                .append("timezone", "UTC")
                                .append("startOfWeek", "monday")))
                        .append("count", new Document("$sum", 1)));

        AggregationOperation sortOp = context -> new Document("$sort", new Document("_id", 1));

        Aggregation aggregation = Aggregation.newAggregation(matchOp, groupOp, sortOp);
        AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, User.class, Document.class);

        // Pre-fill empty periods with 0
        Map<Instant, Long> countsByPeriod = new LinkedHashMap<>();
        for (Instant ps : periodStarts) {
            countsByPeriod.put(ps, 0L);
        }

        if (results != null && results.getMappedResults() != null) {
            for (Document doc : results.getMappedResults()) {
                Object idObj = doc.get("_id");
                Instant periodStart = null;
                if (idObj instanceof java.util.Date d) {
                    periodStart = d.toInstant();
                } else if (idObj instanceof Instant inst) {
                    periodStart = inst;
                }
                if (periodStart != null) {
                    Instant aligned = normalizePeriodStart(periodStart, granularity);
                    Number countNum = (Number) doc.get("count");
                    long count = countNum != null ? countNum.longValue() : 0L;
                    countsByPeriod.computeIfPresent(aligned, (k, current) -> current + count);
                }
            }
        }

        return countsByPeriod.entrySet().stream()
                .map(e -> new PeriodCount(e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public User changeStatus(String actorId, String userId, UserStatus expectedStatus, UserStatus newStatus) {
        User actor = validateAuthenticatedActor(actorId, "Actor account must be active to modify user status");

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found with id: " + userId));

        boolean isSuperAdminGuardRequired = target.getRole() == Role.SUPER_ADMIN && newStatus != UserStatus.ACTIVE;
        SuperAdminGuard guard = null;
        if (isSuperAdminGuardRequired) {
            guard = acquireSuperAdminGuard();
        }

        try {
            // Validate using AdminRolePolicy (checks actor active, not self, role hierarchy, deactivated, last SUPER_ADMIN)
            AdminRolePolicy.validateStatusChange(actor, target, newStatus, userRepository);

            // Early stale-view check: if loaded target's current status differs from expectedStatus, reject immediately without revoking or updating
            if (target.getStatus() != expectedStatus) {
                throw new ResourceConflictException("CONCURRENT_MODIFICATION",
                        "User status was modified concurrently. Expected '" + expectedStatus
                                + "' but current status is '" + target.getStatus() + "'. Please retry.");
            }

            // Proof point (b): immediately BEFORE step 1 (revoke refresh tokens)
            if (isSuperAdminGuardRequired) {
                guard = verifyAndRenewGuard(guard);
            }

            // Step 1: Revoke active refresh tokens before updating status
            if (newStatus == UserStatus.SUSPENDED || newStatus == UserStatus.DEACTIVATED) {
                try {
                    authTokenRepository.revokeAllActiveTokensByUserIdAndType(userId, TokenType.REFRESH);
                } catch (Exception ex) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_CHANGE_FAILED",
                            "The status was not changed because sessions could not be revoked. Please retry.");
                }
            }

            // Time bound check before Step 2
            if (isSuperAdminGuardRequired) {
                long elapsedNanos = System.nanoTime() - guard.lastProofNanoTime();
                long maxAllowedNanos = (superAdminLockLeaseMs - superAdminLockSafetyMarginMs) * 1_000_000L;
                if (elapsedNanos > maxAllowedNanos) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_CHANGE_FAILED",
                            "The status change could not be completed safely. No status was changed. Please reload and retry.");
                }
            }

            // Step 2: Conditional status update (inside a transaction for SUPER_ADMIN protected changes)
            if (isSuperAdminGuardRequired) {
                if (transactionTemplate == null) {
                    log.warn("Transactions not supported for super-admin status change on user {}: IllegalStateException", userId);
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_CHANGE_FAILED",
                            "The status change could not be completed safely. No status was changed. Please reload and retry.");
                }
                final SuperAdminGuard activeGuard = guard;
                try {
                    transactionTemplate.execute(status -> {
                        // a) Guard proof as a WRITE to the guard document inside the transaction
                        Instant now = Instant.now();
                        Query proofQuery = new Query(Criteria.where("_id").is(SUPER_ADMIN_LOCK_ID)
                                .and("owner").is(activeGuard.owner())
                                .and("fence").is(activeGuard.fence())
                                .and("expiresAt").gt(now));
                        Update proofUpdate = new Update().set("expiresAt", now.plusMillis(superAdminLockLeaseMs));
                        UpdateResult proofResult = mongoTemplate.updateFirst(proofQuery, proofUpdate, AUTH_LOCKS_COLLECTION);
                        if (proofResult == null || proofResult.getMatchedCount() == 0) {
                            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_CHANGE_FAILED",
                                    "The status change could not be completed safely. No status was changed. Please reload and retry.");
                        }

                        // b) Re-run the SUPER_ADMIN policy check inside the transaction
                        AdminRolePolicy.validateStatusChange(actor, target, newStatus, userRepository);

                        // c) Conditional user update inside the transaction
                        Query query = new Query(Criteria.where("_id").is(userId).and("status").is(expectedStatus));
                        Update update = new Update().set("status", newStatus);
                        if (newStatus == UserStatus.ACTIVE) {
                            update.set("failedLoginCount", 0);
                            update.unset("lockStartedAt");
                        }

                        UpdateResult result = mongoTemplate.updateFirst(query, update, User.class);
                        if (result.getMatchedCount() == 0) {
                            boolean exists = userRepository.existsById(userId);
                            if (exists) {
                                throw new ResourceConflictException("CONCURRENT_MODIFICATION",
                                        "User status was modified concurrently. Expected '" + expectedStatus
                                                + "' but it has changed. Please retry.");
                            } else {
                                throw new ResourceNotFoundException("USER_NOT_FOUND", "User not found with id: " + userId);
                            }
                        }
                        return null;
                    });
                } catch (ApiException apiEx) {
                    throw apiEx;
                } catch (Exception ex) {
                    if (isTransientTransactionOrWriteConflict(ex)) {
                        throw new ResourceConflictException("CONCURRENT_MODIFICATION",
                                "User status was modified concurrently. Please retry.");
                    }
                    if (isTransactionsNotSupported(ex)) {
                        log.warn("Transactions not supported for super-admin status change on user {}: {}",
                                userId, ex.getClass().getSimpleName());
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_CHANGE_FAILED",
                                "The status change could not be completed safely. No status was changed. Please reload and retry.");
                    }
                    log.warn("Transaction failure for super-admin status change on user {}: {}",
                            userId, ex.getClass().getSimpleName());
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_CHANGE_FAILED",
                            "The status change could not be confirmed. Please reload the user and retry.");
                }
            } else {
                // Non-SUPER_ADMIN status change (no transaction needed)
                Query query = new Query(Criteria.where("_id").is(userId).and("status").is(expectedStatus));
                Update update = new Update().set("status", newStatus);

                if (newStatus == UserStatus.ACTIVE) {
                    update.set("failedLoginCount", 0);
                    update.unset("lockStartedAt");
                }

                UpdateResult result;
                try {
                    result = mongoTemplate.updateFirst(query, update, User.class);
                } catch (Exception ex) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_CHANGE_FAILED",
                            "The status change could not be confirmed. Please reload the user and retry.");
                }

                if (result.getMatchedCount() == 0) {
                    boolean exists = userRepository.existsById(userId);
                    if (exists) {
                        throw new ResourceConflictException("CONCURRENT_MODIFICATION",
                                "User status was modified concurrently. Expected '" + expectedStatus
                                        + "' but it has changed. Please retry.");
                    } else {
                        throw new ResourceNotFoundException("USER_NOT_FOUND", "User not found with id: " + userId);
                    }
                }
            }

            // Step 3 (only after a successful status update): Secondary best-effort token revocation
            if (newStatus == UserStatus.SUSPENDED || newStatus == UserStatus.DEACTIVATED) {
                try {
                    authTokenRepository.revokeAllActiveTokensByUserIdAndType(userId, TokenType.REFRESH);
                } catch (Exception ex) {
                    log.warn("Failed secondary token revocation for user {}: {}", userId, ex.getClass().getSimpleName());
                }
            }

            return userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found after update"));
        } finally {
            if (guard != null) {
                releaseSuperAdminGuard(guard);
            }
        }
    }

    public SuperAdminGuard acquireSuperAdminGuard() {
        String owner = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < LOCK_ACQUISITION_RETRIES; attempt++) {
            try {
                Instant now = Instant.now();
                Instant expiresAt = now.plusMillis(superAdminLockLeaseMs);

                Criteria modernExpiry = Criteria.where("expiresAt").exists(true).lt(now);
                Criteria legacyExpiry = new Criteria().andOperator(
                        Criteria.where("expiresAt").exists(false),
                        new Criteria().orOperator(
                                Criteria.where("lockedUntil").exists(false),
                                Criteria.where("lockedUntil").lt(now)
                        )
                );

                Query query = new Query(Criteria.where("_id").is(SUPER_ADMIN_LOCK_ID)
                        .orOperator(modernExpiry, legacyExpiry));
                Update update = new Update()
                        .set("owner", owner)
                        .set("expiresAt", expiresAt)
                        .unset("lockedUntil")
                        .inc("fence", 1);
                FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(true);
                Document doc = mongoTemplate.findAndModify(query, update, options, Document.class, AUTH_LOCKS_COLLECTION);
                if (doc != null) {
                    long fence = 1L;
                    Object fenceObj = doc.get("fence");
                    if (fenceObj instanceof Number n) {
                        fence = n.longValue();
                    }
                    return new SuperAdminGuard(owner, fence, System.nanoTime());
                }
            } catch (DuplicateKeyException e) {
                // Another thread holds the unexpired lock; upsert hit unique _id constraint
            }
            if (attempt < LOCK_ACQUISITION_RETRIES - 1) {
                try {
                    Thread.sleep(LOCK_RETRY_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ResourceConflictException("CONCURRENT_MODIFICATION",
                            "Interrupted while acquiring super-admin status lock");
                }
            }
        }
        throw new ResourceConflictException("CONCURRENT_MODIFICATION",
                "Super-admin status modification is currently locked by another operation. Please retry.");
    }

    private SuperAdminGuard verifyAndRenewGuard(SuperAdminGuard guard) {
        if (guard == null || guard.owner() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_CHANGE_FAILED",
                    "The status change could not be completed safely. No status was changed. Please reload and retry.");
        }
        Instant now = Instant.now();
        Instant newExpiresAt = now.plusMillis(superAdminLockLeaseMs);
        Query query = new Query(Criteria.where("_id").is(SUPER_ADMIN_LOCK_ID)
                .and("owner").is(guard.owner())
                .and("fence").is(guard.fence())
                .and("expiresAt").gt(now));
        Update update = new Update().set("expiresAt", newExpiresAt);
        UpdateResult result;
        try {
            result = mongoTemplate.updateFirst(query, update, AUTH_LOCKS_COLLECTION);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_CHANGE_FAILED",
                    "The status change could not be completed safely. No status was changed. Please reload and retry.");
        }
        if (result == null || result.getMatchedCount() == 0) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_CHANGE_FAILED",
                    "The status change could not be completed safely. No status was changed. Please reload and retry.");
        }
        return new SuperAdminGuard(guard.owner(), guard.fence(), System.nanoTime());
    }

    private void releaseSuperAdminGuard(SuperAdminGuard guard) {
        if (guard == null || guard.owner() == null) {
            return;
        }
        try {
            Query releaseQuery = new Query(Criteria.where("_id").is(SUPER_ADMIN_LOCK_ID)
                    .and("owner").is(guard.owner())
                    .and("fence").is(guard.fence()));
            Update releaseUpdate = new Update().unset("owner").set("expiresAt", Instant.EPOCH);
            mongoTemplate.updateFirst(releaseQuery, releaseUpdate, AUTH_LOCKS_COLLECTION);
        } catch (Exception ex) {
            log.warn("Failed to release super-admin status lock: {}", ex.getClass().getSimpleName());
        }
    }

    private Instant normalizePeriodStart(Instant instant, Granularity granularity) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
        if (granularity == Granularity.WEEK) {
            return zdt.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS)
                    .toInstant();
        } else {
            return zdt.withDayOfMonth(1)
                    .truncatedTo(ChronoUnit.DAYS)
                    .toInstant();
        }
    }

    private List<Instant> generatePeriodStarts(Instant from, Instant to, Granularity granularity, int maxBuckets) {
        try {
            List<Instant> starts = new ArrayList<>(Math.min(maxBuckets + 1, 128));
            ZonedDateTime current = from.atZone(ZoneOffset.UTC);

            if (granularity == Granularity.WEEK) {
                current = current.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .truncatedTo(ChronoUnit.DAYS);
                while (current.toInstant().isBefore(to)) {
                    if (starts.size() >= maxBuckets) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                                "Requested date range exceeds the maximum allowed limit of " + maxBuckets + " periods for " + granularity);
                    }
                    starts.add(current.toInstant());
                    current = current.plusWeeks(1);
                }
            } else {
                current = current.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
                while (current.toInstant().isBefore(to)) {
                    if (starts.size() >= maxBuckets) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                                "Requested date range exceeds the maximum allowed limit of " + maxBuckets + " periods for " + granularity);
                    }
                    starts.add(current.toInstant());
                    current = current.plusMonths(1);
                }
            }

            return starts;
        } catch (ApiException e) {
            throw e;
        } catch (DateTimeException | ArithmeticException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Requested date range exceeds the maximum allowed limit of " + maxBuckets + " periods for " + granularity);
        }
    }

    private User validateAuthenticatedActor(String actorId, String inactiveMessage) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()) || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
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
                    inactiveMessage);
        }
        return actor;
    }

    private User validateActorForRead(String actorId) {
        User actor = validateAuthenticatedActor(actorId, "Actor account must be active to view users");
        Set<Role> visible = AdminRolePolicy.visibleRoles(actor.getRole());
        if (visible.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_NOT_PERMITTED",
                    "Actor role '" + actor.getRole() + "' is not permitted to view users");
        }
        return actor;
    }

    private boolean isTransientTransactionOrWriteConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConcurrencyFailureException) {
                return true;
            }
            if (cause instanceof MongoException mongo) {
                if (mongo.hasErrorLabel("TransientTransactionError")
                        || mongo.hasErrorLabel("WriteConflict")
                        || mongo.getCode() == 112) {
                    return true;
                }
            }
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("WriteConflict") || msg.contains("TransientTransactionError"))) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private boolean isTransactionsNotSupported(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongo) {
                if (mongo.getCode() == 20) { // IllegalOperation
                    return true;
                }
            }
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("Transaction numbers are only allowed on a replica set member or mongos")
                    || msg.contains("replica set member or mongos"))) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
