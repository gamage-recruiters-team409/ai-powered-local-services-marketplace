package com.gamagerecruiters.marketplace.modules.admin.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccountResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccountSummaryResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminStatusChangeResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.CreateAdminAccountRequest;
import com.gamagerecruiters.marketplace.modules.admin.security.SuperAdminAccess;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/**
 * Super Admin management of admin accounts. Uses the auth module only through UserInternalService.
 *
 * <p>Every write follows the same audit flow: validate what this module owns (no audit record on a
 * rejection here), insert a PENDING record, call the auth module, then set the record to SUCCESS, or to
 * FAILED with the auth error code, and re-throw the original error unchanged.</p>
 *
 * <p>The actor id is always supplied by the controller from the authenticated principal
 * (CurrentUser.getUserId()), never from the request. The auth module checks it again.</p>
 *
 * <p>Also guarded here (not only on the controller) so a call from another module cannot skip the
 * SUPER_ADMIN check.</p>
 */
@Service
@PreAuthorize(SuperAdminAccess.SUPER_ADMIN_AREA_EDIT)
public class AdminAccountService {

    /** The only statuses this module may set. SUSPENDED covers suspend and ban. */
    public static final Set<UserStatus> SETTABLE_STATUSES = Set.of(UserStatus.ACTIVE, UserStatus.SUSPENDED);

    /** Roles a Super Admin may create. SUPER_ADMIN is seeded only; CUSTOMER and PROVIDER register themselves. */
    public static final Set<Role> CREATABLE_ROLES =
            Set.of(Role.ADMIN, Role.SUPPORT_ADMIN, Role.MODERATOR, Role.FINANCE_ADMIN);

    /** Admin roles in display order, used for the list default and the summary. */
    public static final List<Role> ADMIN_ROLE_ORDER =
            List.of(Role.SUPER_ADMIN, Role.ADMIN, Role.SUPPORT_ADMIN, Role.MODERATOR, Role.FINANCE_ADMIN);

    public static final int MIN_REASON_LENGTH = 10;
    public static final int MAX_REASON_LENGTH = 500;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_SEARCH_LENGTH = 100;
    public static final String DEFAULT_SORT = "createdAt,desc";

    /** BCrypt only reads the first 72 bytes of a password. */
    static final int MAX_PASSWORD_BYTES = 72;
    static final int MAX_PASSWORD_LENGTH = 64;

    /** Same policy as the auth module's RegisterRequest / ChangePasswordRequest (not exposed as a shared constant). */
    private static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");
    static final String PASSWORD_POLICY_MESSAGE = "temporaryPassword must be 8 to " + MAX_PASSWORD_LENGTH
            + " characters and contain at least one uppercase letter, one lowercase letter, and one digit";

    /** Sort fields accepted by the list (auth's searchUsers maps fullName to the stored "name"). */
    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "fullName", "email");

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    private final UserInternalService userInternalService;
    private final AdminAuditLogService auditLogService;
    private final Clock clock;

    @Autowired
    public AdminAccountService(UserInternalService userInternalService, AdminAuditLogService auditLogService) {
        this(userInternalService, auditLogService, Clock.systemUTC());
    }

    AdminAccountService(UserInternalService userInternalService, AdminAuditLogService auditLogService, Clock clock) {
        this.userInternalService = userInternalService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- status change

    /**
     * Rules owned here: status ACTIVE/SUSPENDED only, reason 10..500, target is an admin account, status changes.
     * Rules owned by auth (self change, hierarchy, inactive actor, deactivated target, last active SUPER_ADMIN)
     * are applied by auth's changeStatus; their errors are recorded as FAILED and passed through unchanged.
     */
    public AdminStatusChangeResponse changeStatus(String actorId, Role actorRole, String targetUserId,
                                                  UserStatus newStatus, String reason) {
        if (newStatus == null || !SETTABLE_STATUSES.contains(newStatus)) {
            throw AdminErrors.badRequest(AdminErrors.STATUS_NOT_ALLOWED,
                    "Only ACTIVE and SUSPENDED can be set from admin account management");
        }
        String cleanReason = reason == null ? "" : reason.strip();
        if (cleanReason.length() < MIN_REASON_LENGTH || cleanReason.length() > MAX_REASON_LENGTH) {
            throw AdminErrors.badRequest(AdminErrors.VALIDATION_FAILED,
                    "reason must be between " + MIN_REASON_LENGTH + " and " + MAX_REASON_LENGTH + " characters");
        }

        // getById's errors (e.g. 404 USER_NOT_FOUND) pass through unchanged.
        User target = userInternalService.getById(targetUserId);
        if (!Roles.ADMIN_ROLES.contains(target.getRole())) {
            throw AdminErrors.notFound(AdminErrors.ADMIN_ACCOUNT_NOT_FOUND, "Admin account not found");
        }
        if (target.getStatus() == newStatus) {
            throw AdminErrors.badRequest(AdminErrors.STATUS_UNCHANGED, "The account already has this status");
        }

        AdminAuditLog pending = auditLogService.recordPending(AdminAuditLog.pending(
                actorId, actorRole, AdminAuditAction.ADMIN_STATUS_CHANGED,
                target.getId(), target.getRole(), target.getStatus(), newStatus,
                cleanReason, clock.instant()));

        User updated;
        try {
            updated = userInternalService.changeStatus(target.getId(), newStatus);
        } catch (RuntimeException | Error e) {
            markFailedQuietly(pending.getId(), AdminErrors.failureCodeOf(e));
            throw e;
        }

        markQuietly(pending.getId(), () -> auditLogService.markSuccess(pending.getId()));
        log.info("Admin status change applied, audit record {}", pending.getId());
        return new AdminStatusChangeResponse(
                updated.getId(), updated.getRole(), target.getStatus(), updated.getStatus(), pending.getId());
    }

    // ---------------------------------------------------------------- create

    /**
     * Creates an ADMIN, SUPPORT_ADMIN, MODERATOR or FINANCE_ADMIN account through auth's createAdminAccount,
     * which sets mustChangePassword=true. The password is passed straight to auth and is never logged,
     * stored in the audit record or returned.
     */
    public AdminAccountResponse createAdminAccount(String actorId, Role actorRole, CreateAdminAccountRequest request) {
        if (request.role() == null || !CREATABLE_ROLES.contains(request.role())) {
            throw AdminErrors.badRequest(AdminErrors.ROLE_NOT_ALLOWED,
                    "role must be one of ADMIN, SUPPORT_ADMIN, MODERATOR or FINANCE_ADMIN");
        }
        String password = request.temporaryPassword();
        checkPasswordPolicy(password);

        // The new account's id is not known yet; it is added to the record on SUCCESS. No email, no password.
        AdminAuditLog pending = auditLogService.recordPending(AdminAuditLog.pending(
                actorId, actorRole, AdminAuditAction.ADMIN_ACCOUNT_CREATED,
                null, request.role(), null, null, null, clock.instant()));

        User created;
        try {
            created = userInternalService.createAdminAccount(
                    request.fullName(), request.email(), password, request.role());
        } catch (RuntimeException | Error e) {
            markFailedQuietly(pending.getId(), AdminErrors.failureCodeOf(e));
            throw e;
        }

        markQuietly(pending.getId(), () -> auditLogService.markCreated(pending.getId(), created.getId()));
        log.info("Admin account created with role {}, audit record {}", created.getRole(), pending.getId());
        return AdminAccountResponse.fromCreated(created);
    }

    // ---------------------------------------------------------------- list

    /**
     * Paginated admin-role accounts. With no role filter, only the five admin roles are searched (never
     * customers or providers). The search text is plain text; auth's searchUsers escapes it.
     */
    public AdminPageResponse<AdminAccountResponse> listAccounts(String actorId, String role, String status,
                                                                String q, int page, int size, String sort) {
        Set<Role> roles = hasText(role) ? Set.of(parseAdminRole(role)) : Roles.ADMIN_ROLES;
        UserStatus statusFilter = hasText(status) ? parseStatus(status) : null;
        String search = null;
        if (hasText(q)) {
            search = q.strip();
            if (search.length() > MAX_SEARCH_LENGTH) {
                throw AdminErrors.badRequest(AdminErrors.INVALID_FILTER,
                        "q must not exceed " + MAX_SEARCH_LENGTH + " characters");
            }
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw AdminErrors.badRequest(AdminErrors.INVALID_PARAMETER,
                    "page must be 0 or more and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        PageRequest pageable = PageRequest.of(page, size, parseSort(sort));

        return AdminPageResponse.from(userInternalService
                .searchUsers(actorId, roles, statusFilter, search, pageable)
                .map(AdminAccountResponse::from));
    }

    // ---------------------------------------------------------------- summary

    /** Counts per admin role and per status, only over the five admin roles. */
    public AdminAccountSummaryResponse summary(String actorId) {
        Map<Role, Long> byRole = new LinkedHashMap<>();
        Map<UserStatus, Long> byStatus = new EnumMap<>(UserStatus.class);
        for (UserStatus status : UserStatus.values()) {
            byStatus.put(status, 0L);
        }
        long total = 0;
        for (Role role : ADMIN_ROLE_ORDER) {
            long roleTotal = 0;
            for (UserStatus status : UserStatus.values()) {
                long count = userInternalService.countByRoleAndStatus(actorId, role, status);
                roleTotal += count;
                byStatus.merge(status, count, Long::sum);
            }
            byRole.put(role, roleTotal);
            total += roleTotal;
        }
        return new AdminAccountSummaryResponse(total, byRole, byStatus);
    }

    // ---------------------------------------------------------------- helpers

    /** Checked here instead of with Bean Validation so the rejected value never reaches an exception or log. */
    static void checkPasswordPolicy(String password) {
        if (password == null || password.length() > MAX_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES
                || !PASSWORD_POLICY.matcher(password).matches()) {
            throw AdminErrors.badRequest(AdminErrors.VALIDATION_FAILED, PASSWORD_POLICY_MESSAGE);
        }
    }

    private static Role parseAdminRole(String value) {
        try {
            Role role = Role.valueOf(value.strip().toUpperCase(Locale.ROOT));
            if (Roles.ADMIN_ROLES.contains(role)) {
                return role;
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        throw AdminErrors.badRequest(AdminErrors.INVALID_FILTER,
                "role must be one of SUPER_ADMIN, ADMIN, SUPPORT_ADMIN, MODERATOR or FINANCE_ADMIN");
    }

    private static UserStatus parseStatus(String value) {
        try {
            return UserStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AdminErrors.badRequest(AdminErrors.INVALID_FILTER, "Unknown status");
        }
    }

    /** Accepts "field" or "field,asc|desc" with field in createdAt, fullName, email. Anything else is rejected. */
    static Sort parseSort(String value) {
        String spec = hasText(value) ? value.strip() : DEFAULT_SORT;
        String[] parts = spec.split(",", -1);
        if (parts.length > 2 || !SORT_FIELDS.contains(parts[0].strip())) {
            throw AdminErrors.badRequest(AdminErrors.INVALID_PARAMETER, "sort must be createdAt, fullName or email");
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            String dir = parts[1].strip().toLowerCase(Locale.ROOT);
            if (dir.equals("desc")) {
                direction = Sort.Direction.DESC;
            } else if (!dir.equals("asc")) {
                throw AdminErrors.badRequest(AdminErrors.INVALID_PARAMETER, "sort direction must be asc or desc");
            }
        }
        return Sort.by(direction, parts[0].strip());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void markFailedQuietly(String auditLogId, String failureCode) {
        markQuietly(auditLogId, () -> auditLogService.markFailed(auditLogId, failureCode));
    }

    /** The action already ran; an audit update error is logged (by record id only) and never hides the result. */
    private static void markQuietly(String auditLogId, Runnable mark) {
        try {
            mark.run();
        } catch (RuntimeException e) {
            log.error("Audit record {} could not be completed; it stays PENDING and must be reconciled", auditLogId, e);
        }
    }
}
