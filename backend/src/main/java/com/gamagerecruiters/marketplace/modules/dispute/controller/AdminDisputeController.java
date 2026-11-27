package com.gamagerecruiters.marketplace.modules.dispute.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealDecisionRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAdminSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.ResolveDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.security.DisputeAccess;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeAdminQueryService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeResolutionService;

import jakarta.validation.Valid;

/**
 * Admin dispute endpoints. Reads: ADMIN, and SUPER_ADMIN read-only (approved access matrix). Writes (decision,
 * appeal decision): ADMIN only via canEditArea, so SUPER_ADMIN gets 403. A method-level @PreAuthorize replaces the
 * class-level one.
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/disputes")
@PreAuthorize(DisputeAccess.ADMIN_AREA_VIEW)
public class AdminDisputeController {

    private final DisputeAdminQueryService queryService;
    private final DisputeResolutionService resolutionService;

    public AdminDisputeController(DisputeAdminQueryService queryService, DisputeResolutionService resolutionService) {
        this.queryService = queryService;
        this.resolutionService = resolutionService;
    }

    @GetMapping
    public ResponseEntity<DisputePageResponse<AdminDisputeSummaryResponse>> getDisputes(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = DisputeAdminQueryService.DEFAULT_SORT) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DisputeRules.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(queryService.search(status, category, q, from, to, sort, page, size));
    }

    @GetMapping("/summary")
    public ResponseEntity<DisputeAdminSummaryResponse> getSummary() {
        return ResponseEntity.ok(queryService.summary());
    }

    @GetMapping("/{disputeId}")
    public ResponseEntity<AdminDisputeDetailResponse> getDispute(@PathVariable String disputeId) {
        return ResponseEntity.ok(queryService.getDetail(disputeId));
    }

    @PostMapping("/{disputeId}/resolve")
    @PreAuthorize(DisputeAccess.ADMIN_AREA_EDIT)
    public ResponseEntity<AdminDisputeDetailResponse> resolve(@PathVariable String disputeId,
                                                              @Valid @RequestBody ResolveDisputeRequest request) {
        return ResponseEntity.ok(resolutionService.resolve(currentUserId(), CurrentUser.getRole(), disputeId, request));
    }

    @PostMapping("/{disputeId}/appeal/decision")
    @PreAuthorize(DisputeAccess.ADMIN_AREA_EDIT)
    public ResponseEntity<AdminDisputeDetailResponse> decideAppeal(@PathVariable String disputeId,
                                                                   @Valid @RequestBody AppealDecisionRequest request) {
        return ResponseEntity.ok(
                resolutionService.decideAppeal(currentUserId(), CurrentUser.getRole(), disputeId, request));
    }

    private static String currentUserId() {
        String userId = CurrentUser.getUserId();
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User is not authenticated");
        }
        return userId;
    }
}
