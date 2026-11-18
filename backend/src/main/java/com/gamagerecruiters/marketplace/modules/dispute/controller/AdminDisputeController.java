package com.gamagerecruiters.marketplace.modules.dispute.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.security.DisputeAccess;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;

/**
 * Read-only admin dispute endpoints. Access: ADMIN, and SUPER_ADMIN read-only (approved access matrix).
 * Handling actions (resolve, appeal) come in a later PR and must use canEditArea.
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/disputes")
@PreAuthorize(DisputeAccess.ADMIN_AREA_VIEW)
public class AdminDisputeController {

    private final DisputeService disputeService;

    public AdminDisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @GetMapping
    public ResponseEntity<DisputePageResponse<DisputeSummaryResponse>> getDisputes(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DisputeRules.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(disputeService.getDisputesForAdmin(status, page, size));
    }

    @GetMapping("/{disputeId}")
    public ResponseEntity<DisputeDetailResponse> getDispute(@PathVariable String disputeId) {
        return ResponseEntity.ok(disputeService.getDisputeForAdmin(disputeId));
    }
}
