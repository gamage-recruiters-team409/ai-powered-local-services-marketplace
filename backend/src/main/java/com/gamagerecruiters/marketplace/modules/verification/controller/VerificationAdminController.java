
package com.gamagerecruiters.marketplace.modules.verification.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationDetailResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationQueueItemResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;
import com.gamagerecruiters.marketplace.modules.verification.dto.VerificationDecisionRequest;
import com.gamagerecruiters.marketplace.modules.verification.service.VerificationAdminService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/provider-verifications")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class VerificationAdminController {

    private final VerificationAdminService verificationAdminService;

    public VerificationAdminController(
            VerificationAdminService verificationAdminService
    ) {
        this.verificationAdminService = verificationAdminService;
    }

    /**
     * Get all pending provider verification requests.
     * Accessible only to ADMIN.
     */
    @GetMapping
    public ResponseEntity<List<AdminVerificationQueueItemResponse>>
    getPendingVerifications() {

        return ResponseEntity.ok(
                verificationAdminService.getPendingVerifications()
        );
    }

    /**
     * Get complete verification details of a provider.
     * Accessible only to ADMIN.
     */
    @GetMapping("/{providerId}")
    public ResponseEntity<AdminVerificationDetailResponse>
    getVerificationDetails(
            @PathVariable String providerId
    ) {

        return ResponseEntity.ok(
                verificationAdminService.getVerificationDetails(providerId)
        );
    }

    /**
     * Start reviewing a provider verification request.
     * Accessible only to ADMIN.
     */
    @PostMapping("/{providerId}/start-review")
    public ResponseEntity<ProviderVerificationResponse>
    startReview(
            @PathVariable String providerId
    ) {

        return ResponseEntity.ok(
                verificationAdminService.startReview(providerId)
        );
    }

    /**
     * Approve a provider verification request.
     * Accessible only to ADMIN.
     */
    @PostMapping("/{providerId}/approve")
    public ResponseEntity<ProviderVerificationResponse>
    approveVerification(
            @PathVariable String providerId
    ) {

        return ResponseEntity.ok(
                verificationAdminService.approveVerification(providerId)
        );
    }

    /**
     * Reject a provider verification request.
     * Accessible only to ADMIN.
     */
    @PostMapping("/{providerId}/reject")
    public ResponseEntity<ProviderVerificationResponse>
    rejectVerification(
            @PathVariable String providerId,
            @Valid @RequestBody VerificationDecisionRequest request
    ) {

        return ResponseEntity.ok(
                verificationAdminService.rejectVerification(
                        providerId,
                        request.reason()
                )
        );
    }

    /**
     * Request updated documents from a provider.
     * Accessible only to ADMIN.
     */
    @PostMapping("/{providerId}/request-resubmission")
    public ResponseEntity<ProviderVerificationResponse>
    requestResubmission(
            @PathVariable String providerId,
            @Valid @RequestBody VerificationDecisionRequest request
    ) {

        return ResponseEntity.ok(
                verificationAdminService.requestResubmission(
                        providerId,
                        request.reason()
                )
        );
    }
}
