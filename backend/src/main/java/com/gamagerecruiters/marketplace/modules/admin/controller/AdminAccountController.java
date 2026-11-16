package com.gamagerecruiters.marketplace.modules.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccountResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccountSummaryResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminStatusChangeResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.ChangeAdminStatusRequest;
import com.gamagerecruiters.marketplace.modules.admin.dto.CreateAdminAccountRequest;
import com.gamagerecruiters.marketplace.modules.admin.security.SuperAdminAccess;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccountService;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * Super Admin management of admin accounts. SUPER_ADMIN only (class-level @PreAuthorize).
 * The actor is always the authenticated principal; no request field, header or parameter can set it.
 * GET /{id} is not provided: the auth module has no actor-scoped single-user summary read yet.
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/accounts")
@PreAuthorize(SuperAdminAccess.SUPER_ADMIN_AREA_EDIT)
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    public AdminAccountController(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;
    }

    @PostMapping
    public ResponseEntity<AdminAccountResponse> createAdminAccount(@Valid @RequestBody CreateAdminAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminAccountService.createAdminAccount(actorId(), CurrentUser.getRole(), request));
    }

    @GetMapping
    public ResponseEntity<AdminPageResponse<AdminAccountResponse>> listAccounts(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AdminAccountService.DEFAULT_PAGE_SIZE) int size,
            @RequestParam(defaultValue = AdminAccountService.DEFAULT_SORT) String sort) {
        return ResponseEntity.ok(adminAccountService.listAccounts(actorId(), role, status, q, page, size, sort));
    }

    @GetMapping("/summary")
    public ResponseEntity<AdminAccountSummaryResponse> summary() {
        return ResponseEntity.ok(adminAccountService.summary(actorId()));
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<AdminStatusChangeResponse> changeStatus(
            @PathVariable String userId,
            @Valid @RequestBody ChangeAdminStatusRequest request) {
        return ResponseEntity.ok(adminAccountService.changeStatus(
                actorId(), CurrentUser.getRole(), userId, request.status(), request.reason()));
    }

    private static String actorId() {
        String actorId = CurrentUser.getUserId();
        if (actorId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User is not authenticated");
        }
        return actorId;
    }
}
