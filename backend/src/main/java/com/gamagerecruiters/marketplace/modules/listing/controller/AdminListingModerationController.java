package com.gamagerecruiters.marketplace.modules.listing.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingModerationRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/listings")
public class AdminListingModerationController {

    private final ListingService listingService;

    public AdminListingModerationController(ListingService listingService) {
        this.listingService = listingService;
    }

    @GetMapping("/moderation")
    @PreAuthorize("@adminAccessPolicyService.canViewArea(authentication, 'MODERATOR')")
    public ResponseEntity<Page<ListingSummaryResponse>> getModerationQueue(
            @RequestParam(required = false) ListingStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return ResponseEntity.ok(listingService.getModerationQueue(status, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@adminAccessPolicyService.canViewArea(authentication, 'MODERATOR')")
    public ResponseEntity<ListingResponse> getListingForModeration(@PathVariable String id) {
        return ResponseEntity.ok(listingService.getListingForModeration(id));
    }

    @PatchMapping("/{id}/moderation")
    @PreAuthorize("@adminAccessPolicyService.canEditArea(authentication, 'MODERATOR')")
    public ResponseEntity<ListingResponse> moderateListing(
            @PathVariable String id,
            @Valid @RequestBody ListingModerationRequest request
    ) {
        return ResponseEntity.ok(listingService.moderateListing(CurrentUser.getUserId(), id, request));
    }
}
