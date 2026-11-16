package com.gamagerecruiters.marketplace.modules.auth.service;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;

/**
 * Cross-module user management interface.
 * NOTE: Contract to be formally reviewed and agreed with Team Lead / Injas.
 */
public interface UserInternalService {

    User getById(String userId);

    User changeStatus(String userId, UserStatus status);

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
}
