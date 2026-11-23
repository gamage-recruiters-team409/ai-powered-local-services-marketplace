package com.gamagerecruiters.marketplace.modules.provider.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderPayoutDetailsResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderPayoutDetailsRequest;
import com.gamagerecruiters.marketplace.modules.provider.service.ProviderPayoutDetailsService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/providers/me/payout-details")
@Validated
@PreAuthorize("hasRole('PROVIDER')")
public class ProviderPayoutDetailsController {

    private final ProviderPayoutDetailsService providerPayoutDetailsService;

    public ProviderPayoutDetailsController(
            ProviderPayoutDetailsService providerPayoutDetailsService
    ) {
        this.providerPayoutDetailsService = providerPayoutDetailsService;
    }

    /**
     * Returns the authenticated provider's saved payout details.
     * The account number is always masked in this REST response.
     */
    @GetMapping
    public ResponseEntity<ProviderPayoutDetailsResponse> getMyPayoutDetails() {
        return ResponseEntity.ok(
                providerPayoutDetailsService.getMyPayoutDetails()
        );
    }

    /**
     * Creates or replaces the authenticated provider's payout details.
     * The account number is encrypted before it is saved.
     */
    @PutMapping
    public ResponseEntity<ProviderPayoutDetailsResponse> updateMyPayoutDetails(
            @Valid @RequestBody UpdateProviderPayoutDetailsRequest request
    ) {
        return ResponseEntity.ok(
                providerPayoutDetailsService.updateMyPayoutDetails(request)
        );
    }
}
