package com.gamagerecruiters.marketplace.modules.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminStatusChangeResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.ChangeAdminStatusRequest;
import com.gamagerecruiters.marketplace.modules.admin.security.SuperAdminAccess;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccountService;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * Super Admin management of admin accounts. SUPER_ADMIN only.
 * Create, list, detail and summary endpoints wait for the auth module changes (WP01).
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/accounts")
@PreAuthorize(SuperAdminAccess.SUPER_ADMIN_AREA_EDIT)
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    public AdminAccountController(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<AdminStatusChangeResponse> changeStatus(
            @PathVariable String userId,
            @Valid @RequestBody ChangeAdminStatusRequest request) {
        String actorId = CurrentUser.getUserId();
        if (actorId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User is not authenticated");
        }
        return ResponseEntity.ok(adminAccountService.changeStatus(
                actorId, CurrentUser.getRole(), userId, request.status(), request.reason()));
    }
}
