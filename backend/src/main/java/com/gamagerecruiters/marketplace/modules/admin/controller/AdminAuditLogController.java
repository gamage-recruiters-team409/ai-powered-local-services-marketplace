package com.gamagerecruiters.marketplace.modules.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAuditLogResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.admin.security.SuperAdminAccess;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAuditLogService;

/** Read-only admin audit log. SUPER_ADMIN only. There is intentionally no update or delete endpoint. */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/audit-logs")
@PreAuthorize(SuperAdminAccess.SUPER_ADMIN_AREA_EDIT)
public class AdminAuditLogController {

    private final AdminAuditLogService auditLogService;

    public AdminAuditLogController(AdminAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public ResponseEntity<AdminPageResponse<AdminAuditLogResponse>> getAuditLogs(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String targetUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AdminAuditLogService.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(auditLogService.search(actorId, targetUserId, action, from, to, page, size));
    }
}
