package com.gamagerecruiters.marketplace.modules.booking.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.booking.dto.AvailableSlotsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleResponse;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingRescheduleService;

import jakarta.validation.Valid;

/** Reschedule endpoints (FR-BOOK-007) for the booking's customer or provider. */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/bookings/{bookingId}/reschedule")
@PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER')")
public class BookingRescheduleController {

    private final BookingRescheduleService rescheduleService;

    public BookingRescheduleController(BookingRescheduleService rescheduleService) {
        this.rescheduleService = rescheduleService;
    }

    /** Free slots for a new time on a date (the booking's own current time is not counted as busy). */
    @GetMapping("/slots")
    public ResponseEntity<AvailableSlotsResponse> slots(@PathVariable String bookingId,
                                                        @RequestParam(required = false) String date) {
        return ResponseEntity.ok(rescheduleService.availableSlots(bookingId, date));
    }

    /** Propose a new time. */
    @PostMapping
    public ResponseEntity<RescheduleResponse> request(@PathVariable String bookingId,
                                                      @Valid @RequestBody RescheduleBookingRequest request) {
        return ResponseEntity.ok(rescheduleService.request(bookingId, request));
    }

    @PostMapping("/approve")
    public ResponseEntity<RescheduleResponse> approve(@PathVariable String bookingId) {
        return ResponseEntity.ok(rescheduleService.approve(bookingId));
    }

    @PostMapping("/reject")
    public ResponseEntity<RescheduleResponse> reject(@PathVariable String bookingId) {
        return ResponseEntity.ok(rescheduleService.reject(bookingId));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<RescheduleResponse> withdraw(@PathVariable String bookingId) {
        return ResponseEntity.ok(rescheduleService.withdraw(bookingId));
    }
}
