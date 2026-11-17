package com.gamagerecruiters.marketplace.modules.verification.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationService;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/providers/me/verification")
@PreAuthorize("hasRole('PROVIDER')")
public class ProviderVerificationController {

    private final ProviderVerificationService providerVerificationService;

    public ProviderVerificationController(
            ProviderVerificationService providerVerificationService
    ) {
        this.providerVerificationService = providerVerificationService;
    }

    @PostMapping("/submit")
    public ResponseEntity<ProviderVerificationResponse> submitMyVerification() {

        return ResponseEntity.accepted().body(
                providerVerificationService.submitMyVerification()
        );
    }

    @GetMapping("/status")
    public ResponseEntity<ProviderVerificationResponse> getMyVerificationStatus() {

        return ResponseEntity.ok(
                providerVerificationService.getMyVerificationStatus()
        );
    }
}