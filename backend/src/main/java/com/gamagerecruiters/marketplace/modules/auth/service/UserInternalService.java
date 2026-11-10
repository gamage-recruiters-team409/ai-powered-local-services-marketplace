package com.gamagerecruiters.marketplace.modules.auth.service;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

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
}
