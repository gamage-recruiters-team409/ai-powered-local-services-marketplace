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
import com.gamagerecruiters.marketplace.modules.payment.dto.CreatePayoutRequest;
import com.gamagerecruiters.marketplace.modules.payment.dto.PayoutPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.PayoutResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.ProviderPayoutResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.PayoutService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/provider/payouts")
@PreAuthorize("hasRole('PROVIDER')")
public class ProviderPayoutController {

    private final PayoutService payoutService;

    public ProviderPayoutController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @PostMapping
    public ResponseEntity<ProviderPayoutResponse> requestPayout(
            @Valid @RequestBody CreatePayoutRequest request) {
        var payout = payoutService.requestPayout(
                requireAuthenticatedUserId(),
                request.requestKey(),
                request.amount());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PayoutResponse.from(payout).toProviderView());
    }

    @GetMapping("/me")
    public ResponseEntity<PayoutPageResponse<ProviderPayoutResponse>> getMyPayouts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        var payouts = payoutService.getMyPayoutHistory(
                requireAuthenticatedUserId(), page, size);
        return ResponseEntity.ok(PayoutPageResponse.providerFrom(payouts));
    }

    @GetMapping("/{payoutId}")
    public ResponseEntity<ProviderPayoutResponse> getMyPayout(
            @PathVariable String payoutId) {
        var payout = payoutService.getMyPayout(
                requireAuthenticatedUserId(), payoutId);
        return ResponseEntity.ok(PayoutResponse.from(payout).toProviderView());
    }

    private static String requireAuthenticatedUserId() {
        String userId = CurrentUser.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Authentication is required to access payouts");
        }
        return userId.trim();
    }
}
