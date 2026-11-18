package com.gamagerecruiters.marketplace.modules.listing.controller;

import java.net.URI;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.dto.CreateListingRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProviderListingDashboardSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProvinceGeoDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.UpdateListingRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.UpdateListingStatusRequest;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

import jakarta.validation.Valid;

/**
 * Service listing endpoints.
 *
 * <p><strong>Public Guest Access Follow-Up Plan (WP01 Coordination):</strong>
 * {@code GET /api/v1/listings/{id}} and {@code GET /api/v1/listings/locations/districts}
 * intentionally retain {@code isAuthenticated()} in PR #30 and are tracked as a dedicated
 * follow-up coordinated with WP01 (Kalana) rather than modifying {@code SecurityConfig.java}
 * unilaterally here. In that follow-up PR, WP01 will permit {@code GET /api/v1/listings/locations/districts}
 * and {@code GET /api/v1/listings/*} only after explicitly matching {@code /api/v1/listings/me}
 * and {@code /api/v1/listings/me/summary} as authenticated, and these two read handlers will
 * switch to public access; the service layer already handles anonymous context safely and
 * scrubs moderation details.
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/listings")
public class ListingController {

    private final ListingService listingService;

    public ListingController(ListingService listingService) {
        this.listingService = listingService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ListingResponse> createListing(@Valid @RequestBody CreateListingRequest request) {
        ListingResponse created = listingService.createListing(CurrentUser.getUserId(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<Page<ListingSummaryResponse>> getMyListings(
            @RequestParam(required = false) ListingStatus status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(listingService.getMyListings(CurrentUser.getUserId(), status, search, pageable));
    }

    @GetMapping("/me/summary")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListingDashboardSummaryResponse> getMyListingsSummary() {
        return ResponseEntity.ok(listingService.getMyListingsSummary(CurrentUser.getUserId()));
    }

    @GetMapping("/locations/districts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ProvinceGeoDto>> getDistrictLocations() {
        return ResponseEntity.ok(listingService.getDistrictLocations());
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ListingResponse> getListingById(@PathVariable String id) {
        return ResponseEntity.ok(listingService.getListingById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ListingResponse> updateListing(
            @PathVariable String id,
            @Valid @RequestBody UpdateListingRequest request
    ) {
        return ResponseEntity.ok(listingService.updateListing(CurrentUser.getUserId(), id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ListingResponse> updateListingStatus(
            @PathVariable String id,
            @Valid @RequestBody UpdateListingStatusRequest request
    ) {
        return ResponseEntity.ok(listingService.updateListingStatus(CurrentUser.getUserId(), id, request));
    }
}
