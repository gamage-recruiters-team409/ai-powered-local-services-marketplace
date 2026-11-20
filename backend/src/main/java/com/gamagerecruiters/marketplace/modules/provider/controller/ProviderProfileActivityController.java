
package com.gamagerecruiters.marketplace.modules.provider.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileActivityResponse;
import com.gamagerecruiters.marketplace.modules.provider.service.ProviderProfileActivityService;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/providers/me")
@Validated
@PreAuthorize("hasRole('PROVIDER')")
public class ProviderProfileActivityController {

    private final ProviderProfileActivityService activityService;

    public ProviderProfileActivityController(
            ProviderProfileActivityService activityService
    ) {
        this.activityService = activityService;
    }

    @GetMapping("/activity")
    public ResponseEntity<ProviderProfileActivityResponse> getMyActivity() {

        return ResponseEntity.ok(
                activityService.getMyActivity()
        );
    }

    @PostMapping("/pause")
    public ResponseEntity<ProviderProfileActivityResponse> pauseMyProfile() {

        return ResponseEntity.ok(
                activityService.pauseMyProfile()
        );
    }

    @PostMapping("/reactivate")
    public ResponseEntity<ProviderProfileActivityResponse> reactivateMyProfile() {

        return ResponseEntity.ok(
                activityService.reactivateMyProfile()
        );
    }
}
