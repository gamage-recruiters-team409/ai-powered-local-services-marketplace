package com.gamagerecruiters.marketplace.modules.booking.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingDecisionResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.DeclineBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.service.ProviderBookingDecisionService;

import jakarta.validation.Valid;

/** Provider accepts or declines a booking request (FR-BOOK-004). */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/bookings/{bookingId}")
public class BookingDecisionController {

    private final ProviderBookingDecisionService decisionService;

    public BookingDecisionController(ProviderBookingDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    /** PENDING_PROVIDER → AWAITING_PAYMENT (payment due in 15 minutes). */
    @PostMapping("/accept")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<BookingDecisionResponse> accept(@PathVariable String bookingId) {
        return ResponseEntity.ok(decisionService.accept(bookingId));
    }

    /** PENDING_PROVIDER → DECLINED, with a dropdown reason (C-09). */
    @PostMapping("/decline")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<BookingDecisionResponse> decline(@PathVariable String bookingId,
                                                           @Valid @RequestBody DeclineBookingRequest request) {
        return ResponseEntity.ok(decisionService.decline(bookingId, request));
    }
}
