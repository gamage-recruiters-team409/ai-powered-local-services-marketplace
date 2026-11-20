package com.gamagerecruiters.marketplace.modules.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;
import com.gamagerecruiters.marketplace.modules.auth.dto.Granularity;
import com.gamagerecruiters.marketplace.modules.auth.dto.PeriodCount;

/**
 * Cross-module user management interface.
 * NOTE: Contract to be formally reviewed and agreed with Team Lead / Injas.
 */
public interface UserInternalService {

    record UserContact(String id, String fullName, String email, UserStatus status) {}

    UserContact getContactById(String userId);

    User getById(String userId);

    /**
     * @deprecated Use {@link #changeStatus(String, String, UserStatus, UserStatus)} for atomic
     *             conditional status updates with optimistic concurrency control and refresh token revocation.
     */
    @Deprecated
    User changeStatus(String userId, UserStatus status);

    /**
     * Atomically changes a user's status with optimistic concurrency control.
     * The update only succeeds if the user's current status matches {@code expectedStatus};
     * otherwise a 409 CONCURRENT_MODIFICATION is thrown.
     * Revokes all active refresh tokens when the new status is SUSPENDED or DEACTIVATED.
     *
     * @param actorId        the ID of the authenticated admin performing the action
     * @param userId         the ID of the target user
     * @param expectedStatus the status the caller expects the target to currently have
     * @param newStatus      the desired new status
     * @return the updated User document
     */
    User changeStatus(String actorId, String userId, UserStatus expectedStatus, UserStatus newStatus);

    User createAdminAccount(String name, String email, String password, Role role);

    User updateContactDetails(String userId, String fullName, String phone);

    void changePassword(String userId, String currentPassword, String newPassword);

    void verifyPassword(String userId, String rawPassword);

    void deactivateOwnAccount(String userId);

    /**
     * Searches users across roles and statuses with name/email search, pagination, and sorting.
     * Enforces administrative visibility boundaries based on the authenticated actor.
     */
    Page<AdminUserSummary> searchUsers(String actorId, Set<Role> roles, UserStatus status, String nameOrEmail, Pageable pageable);

    /**
     * Counts users matching role and status filters within the authenticated actor's visibility scope.
     */
    long countByRoleAndStatus(String actorId, Role role, UserStatus status);

    /**
     * Counts new user registrations per time period for admin analytics dashboards.
     * Returns a list of (periodStart, count) ordered chronologically, with empty periods filled with 0.
     * Enforces the same actor binding (401/403) and role restriction as {@link #searchUsers}.
     * Users without a {@code createdAt} field (legacy records) are excluded from the count.
     *
     * @param actorId     the ID of the authenticated admin performing the query
     * @param roles       the set of roles to count (must be within the actor's visible roles)
     * @param from        start of the date range (inclusive)
     * @param to          end of the date range (exclusive)
     * @param granularity time bucket size: WEEK or MONTH
     * @return ordered list of period counts; never null
     */
    List<PeriodCount> countNewUsers(String actorId, Set<Role> roles, Instant from, Instant to, Granularity granularity);
}
