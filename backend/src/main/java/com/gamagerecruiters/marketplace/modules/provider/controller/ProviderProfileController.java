
package com.gamagerecruiters.marketplace.modules.provider.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.service.ProviderProfileService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/providers")
@Validated
@PreAuthorize("hasRole('PROVIDER')")
public class ProviderProfileController {

    private final ProviderProfileService providerProfileService;

    public ProviderProfileController(
            ProviderProfileService providerProfileService
    ) {
        this.providerProfileService = providerProfileService;
    }

    @PostMapping("/me")
    public ResponseEntity<ProviderProfileResponse> createMyProfile(
            @Valid @RequestBody CreateProviderProfileRequest request
    ) {
        ProviderProfileResponse response =
                providerProfileService.createMyProfile(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<ProviderProfileResponse> getMyProfile() {
        return ResponseEntity.ok(
                providerProfileService.getMyProfile()
        );
    }

    @PutMapping("/me")
    public ResponseEntity<ProviderProfileResponse> updateMyProfile(
            @Valid @RequestBody UpdateProviderProfileRequest request
    ) {
        return ResponseEntity.ok(
                providerProfileService.updateMyProfile(request)
        );
    }

    /**
     * Uploads or replaces the authenticated provider's profile image.
     *
     * Request: multipart/form-data
     * Field: file
     */
    @PostMapping(
            value = "/me/profile-image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ProviderProfileResponse> uploadMyProfileImage(
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.ok(
                providerProfileService.uploadMyProfileImage(file)
        );
    }

    /**
     * Removes the authenticated provider's profile image.
     */
    @DeleteMapping("/me/profile-image")
    public ResponseEntity<ProviderProfileResponse> removeMyProfileImage() {
        return ResponseEntity.ok(
                providerProfileService.removeMyProfileImage()
        );
    }
}
