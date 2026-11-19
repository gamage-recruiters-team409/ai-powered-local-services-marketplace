package com.gamagerecruiters.marketplace.modules.payment.controller;

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
import com.gamagerecruiters.marketplace.modules.payment.dto.ConfirmPayoutRequest;
import com.gamagerecruiters.marketplace.modules.payment.dto.FailPayoutRequest;
import com.gamagerecruiters.marketplace.modules.payment.dto.PayoutPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.PayoutResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.PayoutService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/payouts")
public class AdminPayoutController {

    private final PayoutService payoutService;

    public AdminPayoutController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @GetMapping
    @PreAuthorize("@adminAccessPolicyService.canViewArea(authentication, 'FINANCE_ADMIN')")
    public ResponseEntity<PayoutPageResponse<PayoutResponse>> getPayouts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        var payouts = payoutService.getAdminPayoutHistory(page, size);
        return ResponseEntity.ok(PayoutPageResponse.adminFrom(payouts));
    }

    @GetMapping("/{payoutId}")
    @PreAuthorize("@adminAccessPolicyService.canViewArea(authentication, 'FINANCE_ADMIN')")
    public ResponseEntity<PayoutResponse> getPayout(@PathVariable String payoutId) {
        return ResponseEntity.ok(PayoutResponse.from(payoutService.getAdminPayout(payoutId)));
    }

    @PostMapping("/{payoutId}/process")
    @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'FINANCE_ADMIN')")
    public ResponseEntity<PayoutResponse> markProcessing(@PathVariable String payoutId) {
        var payout = payoutService.markProcessing(payoutId, requireAuthenticatedAdminId());
        return ResponseEntity.ok(PayoutResponse.from(payout));
    }

    @PostMapping("/{payoutId}/paid")
    @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'FINANCE_ADMIN')")
    public ResponseEntity<PayoutResponse> markPaid(
            @PathVariable String payoutId,
            @Valid @RequestBody ConfirmPayoutRequest request) {
        var payout = payoutService.markPaid(
                payoutId,
                requireAuthenticatedAdminId(),
                request.transferReference());
        return ResponseEntity.ok(PayoutResponse.from(payout));
    }

    @PostMapping("/{payoutId}/failed")
    @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'FINANCE_ADMIN')")
    public ResponseEntity<PayoutResponse> markFailed(
            @PathVariable String payoutId,
            @Valid @RequestBody FailPayoutRequest request) {
        var payout = payoutService.markFailed(
                payoutId,
                requireAuthenticatedAdminId(),
                request.reason());
        return ResponseEntity.ok(PayoutResponse.from(payout));
    }

    private static String requireAuthenticatedAdminId() {
        String adminId = CurrentUser.getUserId();
        if (adminId == null || adminId.isBlank()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Authentication is required to process payouts");
        }
        return adminId.trim();
    }
}
