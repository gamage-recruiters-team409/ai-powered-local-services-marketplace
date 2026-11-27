package com.gamagerecruiters.marketplace.modules.admin.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.admin.dto.PlatformSettingAuditHistoryResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.PlatformSettingsGroupResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.UpdatePlatformSettingsRequest;
import com.gamagerecruiters.marketplace.modules.admin.security.SuperAdminAccess;
import com.gamagerecruiters.marketplace.modules.admin.service.PlatformSettingsService;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * Super Admin endpoints for platform settings (FR-ADM-008).
 * SUPER_ADMIN only.
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/settings")
@PreAuthorize(SuperAdminAccess.SUPER_ADMIN_AREA_EDIT)
public class PlatformSettingsController {

    private final PlatformSettingsService settingsService;

    public PlatformSettingsController(PlatformSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, PlatformSettingsGroupResponse>> getAllSettings() {
        return ResponseEntity.ok(settingsService.getAllGrouped());
    }

    @GetMapping("/{group}")
    public ResponseEntity<PlatformSettingsGroupResponse> getGroup(@PathVariable String group) {
        return ResponseEntity.ok(settingsService.getGroup(group));
    }

    @PutMapping("/{group}")
    public ResponseEntity<PlatformSettingsGroupResponse> updateGroup(
            @PathVariable String group,
            @Valid @RequestBody UpdatePlatformSettingsRequest request) {
        return ResponseEntity.ok(settingsService.updateGroup(
                actorId(),
                CurrentUser.getRole(),
                group,
                request
        ));
    }

    @GetMapping("/{group}/history")
    public ResponseEntity<List<PlatformSettingAuditHistoryResponse>> getGroupHistory(@PathVariable String group) {
        return ResponseEntity.ok(settingsService.getHistory(group));
    }

    private static String actorId() {
        String actorId = CurrentUser.getUserId();
        if (actorId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User is not authenticated");
        }
        return actorId;
    }
}
