package com.gamagerecruiters.marketplace.modules.booking.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateImpactResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateResult;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateWithBookingsRequest;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingBlockDateService;

import jakarta.validation.Valid;

/** Provider blocks a date that already has bookings. Empty dates still use POST /availability/me/blocked-dates. */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/bookings/blocked-dates")
@PreAuthorize("hasRole('PROVIDER')")
public class BookingBlockDateController {

    private final BookingBlockDateService blockDateService;

    public BookingBlockDateController(BookingBlockDateService blockDateService) {
        this.blockDateService = blockDateService;
    }

    /** GET /api/v1/bookings/blocked-dates/impact?date=2026-10-26 */
    @GetMapping("/impact")
    public ResponseEntity<BlockDateImpactResponse> impact(@RequestParam(required = false) String date) {
        return ResponseEntity.ok(blockDateService.impact(date));
    }

    @PostMapping
    public ResponseEntity<BlockDateResult> block(@Valid @RequestBody BlockDateWithBookingsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(blockDateService.blockWithBookings(request));
    }
}
