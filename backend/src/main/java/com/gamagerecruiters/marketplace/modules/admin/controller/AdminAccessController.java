package com.gamagerecruiters.marketplace.modules.admin.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccessResponse;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin")
public class AdminAccessController {

    private final AdminAccessPolicyService adminAccessPolicyService;

    public AdminAccessController(AdminAccessPolicyService adminAccessPolicyService) {
        this.adminAccessPolicyService = adminAccessPolicyService;
    }

    /** Returns the admin areas the authenticated user may edit or view. The role comes only from the token. */
    @GetMapping("/access")
    @PreAuthorize("@adminAccessPolicyService.isAdmin(authentication)")
    public ResponseEntity<AdminAccessResponse> getAccess() {
        Role role = CurrentUser.getRole();
        if (role == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User is not authenticated");
        }
        return ResponseEntity.ok(adminAccessPolicyService.getAccess(role));
    }
}
