package com.gamagerecruiters.marketplace.modules.availability.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.availability.dto.AvailabilityResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.CreateBlockedDateRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.UpdateAvailabilityRequest;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/availability")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<AvailabilityResponse> getMyAvailability() {
        return ResponseEntity.ok(availabilityService.getAvailability(CurrentUser.getUserId()));
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<AvailabilityResponse> updateMyAvailability(
            @Valid @RequestBody UpdateAvailabilityRequest request) {
        return ResponseEntity.ok(availabilityService.updateAvailability(CurrentUser.getUserId(), request));
    }

    @GetMapping("/me/blocked-dates")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<List<BlockedDateResponse>> getMyBlockedDates(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(availabilityService.getBlockedDates(CurrentUser.getUserId(), from, to));
    }

    @PostMapping("/me/blocked-dates")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<BlockedDateResponse> createBlockedDate(
            @Valid @RequestBody CreateBlockedDateRequest request) {
        BlockedDateResponse created = availabilityService.createBlockedDate(CurrentUser.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/me/blocked-dates/{blockedDateId}")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<Void> deleteBlockedDate(@PathVariable String blockedDateId) {
        availabilityService.deleteBlockedDate(CurrentUser.getUserId(), blockedDateId);
        return ResponseEntity.noContent().build();
    }
}
