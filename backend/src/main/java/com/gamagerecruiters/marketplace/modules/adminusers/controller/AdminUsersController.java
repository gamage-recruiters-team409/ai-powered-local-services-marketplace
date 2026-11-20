package com.gamagerecruiters.marketplace.modules.adminusers.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.domain.AdminUsersRules;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AccountActionResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUserDetailResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUserRow;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUsersSummaryResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.ListingReviewRequest;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.ReactivateRequest;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.RegistrationsResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.Section;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.SuspendRequest;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.security.AdminUsersAccess;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUserAccountService;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUserListingService;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUsersCsvExporter;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUsersQueryService;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;

/**
 * Admin users (customers and providers). Reads: ADMIN, and SUPER_ADMIN read-only. Suspend / reactivate: ADMIN only
 * (canEditArea), so SUPER_ADMIN gets 403. A method-level @PreAuthorize replaces the class-level one. The actor is
 * always the logged-in admin, never a request value.
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/users")
@PreAuthorize(AdminUsersAccess.ADMIN_AREA_VIEW)
public class AdminUsersController {

    private static final MediaType CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

    private final AdminUsersQueryService queryService;
    private final AdminUsersCsvExporter csvExporter;
    private final AdminUserAccountService accountService;
    private final AdminUserListingService listingService;

    public AdminUsersController(AdminUsersQueryService queryService, AdminUsersCsvExporter csvExporter,
                                AdminUserAccountService accountService, AdminUserListingService listingService) {
        this.queryService = queryService;
        this.csvExporter = csvExporter;
        this.accountService = accountService;
        this.listingService = listingService;
    }

    @GetMapping
    public ResponseEntity<AdminPageResponse<AdminUserRow>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AdminUsersRules.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(queryService.list(currentUserId(), q, role, status, page, size));
    }

    @GetMapping("/summary")
    public ResponseEntity<AdminUsersSummaryResponse> summary() {
        return ResponseEntity.ok(queryService.summary(currentUserId()));
    }

    @GetMapping("/registrations")
    public ResponseEntity<RegistrationsResponse> registrations(@RequestParam(required = false) String period) {
        return ResponseEntity.ok(queryService.registrations(currentUserId(), period));
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        AdminUsersCsvExporter.Export export = csvExporter.export(currentUserId(), q, role, status);
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .header("X-Total-Count", String.valueOf(export.totalMatching()))
                .header("X-Export-Truncated", String.valueOf(export.truncated()))
                .body(export.content());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminUserDetailResponse> detail(@PathVariable String id) {
        return ResponseEntity.ok(queryService.detail(id, currentUserId(), CurrentUser.getRole()));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize(AdminUsersAccess.ADMIN_AREA_EDIT)
    public ResponseEntity<AccountActionResponse> suspend(@PathVariable String id,
                                                         @RequestBody SuspendRequest request) {
        return ResponseEntity.ok(accountService.suspend(currentUserId(), CurrentUser.getRole(), id,
                request.reasonCode(), request.note()));
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize(AdminUsersAccess.ADMIN_AREA_EDIT)
    public ResponseEntity<AccountActionResponse> reactivate(@PathVariable String id,
                                                            @RequestBody ReactivateRequest request) {
        return ResponseEntity.ok(accountService.reactivate(currentUserId(), CurrentUser.getRole(), id, request.note()));
    }

    /** The review modal's listing. {@code available: false} when the listing source cannot provide it. */
    @GetMapping("/{id}/listings/{listingId}")
    public ResponseEntity<Section<ProviderListingPort.ListingDetail>> listing(@PathVariable String id,
                                                                          @PathVariable String listingId) {
        return ResponseEntity.ok(listingService.detail(id, listingId));
    }

    /** KEEP_HIDDEN or MAKE_VISIBLE with a note. 503 LISTING_ACTION_UNAVAILABLE until the listing module supports it. */
    @PostMapping("/{id}/listings/{listingId}/review")
    @PreAuthorize(AdminUsersAccess.ADMIN_AREA_EDIT)
    public ResponseEntity<Void> reviewListing(@PathVariable String id, @PathVariable String listingId,
                                              @RequestBody ListingReviewRequest request) {
        listingService.review(currentUserId(), id, listingId, request.decision(), request.note());
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
