package com.gamagerecruiters.marketplace.modules.search.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.search.dto.PublicProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.search.service.PublicProviderProfileService;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/providers")
public class PublicProviderProfileController {

    private final PublicProviderProfileService publicProviderProfileService;

    public PublicProviderProfileController(PublicProviderProfileService publicProviderProfileService) {
        this.publicProviderProfileService = publicProviderProfileService;
    }

    @GetMapping("/{providerId}")
    public PublicProviderProfileResponse getPublicProfile(
            @PathVariable String providerId,
            @RequestParam(defaultValue = "0") int reviewPage
    ) {
        return publicProviderProfileService.getPublicProfile(providerId, reviewPage);
    }
}
