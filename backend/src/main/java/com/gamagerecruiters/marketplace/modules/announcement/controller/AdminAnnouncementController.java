package com.gamagerecruiters.marketplace.modules.announcement.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementRules;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementPageResponse;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementRequest;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementResponse;
import com.gamagerecruiters.marketplace.modules.announcement.dto.AnnouncementSummaryResponse;
import com.gamagerecruiters.marketplace.modules.announcement.security.AnnouncementAccess;
import com.gamagerecruiters.marketplace.modules.announcement.service.AnnouncementService;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * Admin announcement endpoints. Reads: ADMIN, and SUPER_ADMIN read-only. Writes: ADMIN only (canEditArea), so
 * SUPER_ADMIN gets 403. A method-level @PreAuthorize replaces the class-level one. createdBy/updatedBy are always the
 * logged-in admin.
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/announcements")
@PreAuthorize(AnnouncementAccess.ADMIN_AREA_VIEW)
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    public AdminAnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping
    public ResponseEntity<AnnouncementPageResponse<AnnouncementResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String audience,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AnnouncementRules.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(announcementService.list(status, audience, q, page, size));
    }

    @GetMapping("/summary")
    public ResponseEntity<AnnouncementSummaryResponse> summary() {
        return ResponseEntity.ok(announcementService.summary());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AnnouncementResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(announcementService.get(id));
    }

    @PostMapping
    @PreAuthorize(AnnouncementAccess.ADMIN_AREA_EDIT)
    public ResponseEntity<AnnouncementResponse> create(@Valid @RequestBody AnnouncementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(announcementService.create(currentUserId(), request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(AnnouncementAccess.ADMIN_AREA_EDIT)
    public ResponseEntity<AnnouncementResponse> update(@PathVariable String id,
                                                       @Valid @RequestBody AnnouncementRequest request) {
        return ResponseEntity.ok(announcementService.update(currentUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(AnnouncementAccess.ADMIN_AREA_EDIT)
    public ResponseEntity<Void> delete(@PathVariable String id, @RequestParam(required = false) Long version) {
        announcementService.delete(currentUserId(), id, version);
        return ResponseEntity.noContent().build();
    }

    private static String currentUserId() {
        String userId = CurrentUser.getUserId();
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User is not authenticated");
        }
        return userId;
    }
}
