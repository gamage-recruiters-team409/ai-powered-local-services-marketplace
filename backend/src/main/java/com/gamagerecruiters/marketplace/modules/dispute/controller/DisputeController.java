package com.gamagerecruiters.marketplace.modules.dispute.controller;

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
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.SubmitDisputeResponseRequest;
import com.gamagerecruiters.marketplace.modules.dispute.security.DisputeAccess;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;

import jakarta.validation.Valid;

/** Dispute endpoints for the booking parties (customer and provider). */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/disputes")
public class DisputeController {

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @PostMapping
    @PreAuthorize(DisputeAccess.PARTY)
    public ResponseEntity<DisputeDetailResponse> raiseDispute(@Valid @RequestBody RaiseDisputeRequest request) {
        DisputeDetailResponse created = disputeService.raiseDispute(currentUserId(), CurrentUser.getRole(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/my")
    @PreAuthorize(DisputeAccess.PARTY)
    public ResponseEntity<DisputePageResponse<DisputeSummaryResponse>> getMyDisputes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DisputeRules.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(disputeService.getMyDisputes(currentUserId(), page, size));
    }

    /** Parties see their own disputes; admins who can view the disputes area see any dispute. */
    @GetMapping("/{disputeId}")
    @PreAuthorize(DisputeAccess.PARTY_OR_ADMIN_AREA_VIEW)
    public ResponseEntity<DisputeDetailResponse> getDispute(@PathVariable String disputeId) {
        Role role = CurrentUser.getRole();
        if (role == Role.CUSTOMER || role == Role.PROVIDER) {
            return ResponseEntity.ok(disputeService.getDisputeForParty(currentUserId(), disputeId));
        }
        return ResponseEntity.ok(disputeService.getDisputeForAdmin(disputeId));
    }

    @PostMapping("/{disputeId}/response")
    @PreAuthorize(DisputeAccess.PARTY)
    public ResponseEntity<DisputeDetailResponse> submitResponse(
            @PathVariable String disputeId,
            @Valid @RequestBody SubmitDisputeResponseRequest request) {
        return ResponseEntity.ok(
                disputeService.submitResponse(currentUserId(), CurrentUser.getRole(), disputeId, request));
    }

    private static String currentUserId() {
        String userId = CurrentUser.getUserId();
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User is not authenticated");
        }
        return userId;
    }
}
