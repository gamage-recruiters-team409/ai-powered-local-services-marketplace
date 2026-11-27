package com.gamagerecruiters.marketplace.modules.listing.controller;

import java.net.URI;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.dto.CreateListingRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingMediaUploadResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProviderListingDashboardSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProvinceGeoDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.UpdateListingRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.UpdateListingStatusRequest;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingMediaUploadService;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

import jakarta.validation.Valid;

/**
 * Service listing endpoints.
 *
 * <p>Public guest read access is enabled in {@code SecurityConfig} (coordinated with WP01)
 * exclusively for {@code GET /api/v1/listings/locations/districts} and
 * {@code GET /api/v1/listings/{id}}, returning only customer-safe {@code ACTIVE}/{@code PAUSED}
 * listing details while keeping {@code /me}, {@code /me/summary}, and all mutations protected.
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/listings")
public class ListingController {

    private final ListingService listingService;
    private final ObjectProvider<ListingMediaUploadService> mediaUploadServiceProvider;

    public ListingController(ListingService listingService) {
        this(listingService, null);
    }

    @Autowired
    public ListingController(
            ListingService listingService,
            ObjectProvider<ListingMediaUploadService> mediaUploadServiceProvider
    ) {
        this.listingService = listingService;
        this.mediaUploadServiceProvider = mediaUploadServiceProvider;
    }

    @PostMapping(path = "/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ListingMediaUploadResponse> uploadListingMedia(
            @RequestPart("file") MultipartFile file
    ) {
        ListingMediaUploadService uploadService = mediaUploadServiceProvider == null
                ? null
                : mediaUploadServiceProvider.getIfAvailable();
        if (uploadService == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MEDIA_STORAGE_NOT_CONFIGURED",
                    "Direct cloud image upload is not available. Paste an HTTPS image URL instead."
            );
        }
        ListingMediaUploadResponse uploaded = uploadService.uploadPortfolioImage(CurrentUser.getUserId(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(uploaded);
    }

    @DeleteMapping("/media")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<Void> deleteUnusedListingMedia(@RequestParam("publicId") String publicId) {
        listingService.deleteUnusedListingMedia(CurrentUser.getUserId(), publicId);
        return ResponseEntity.noContent().build();
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
    public ResponseEntity<List<ProvinceGeoDto>> getDistrictLocations() {
        return ResponseEntity.ok(listingService.getDistrictLocations());
    }

    @GetMapping("/{id}")
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
