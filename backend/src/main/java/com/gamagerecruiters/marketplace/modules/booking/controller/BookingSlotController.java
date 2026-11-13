package com.gamagerecruiters.marketplace.modules.booking.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.booking.dto.AvailableSlotsResponse;
import com.gamagerecruiters.marketplace.modules.booking.service.SlotService;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/bookings")
public class BookingSlotController {

    private final SlotService slotService;

    public BookingSlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    /**
     * Available slots for a provider on a date, for a service of the given length.
     * Example: GET /api/v1/bookings/available-slots?providerId=...&amp;date=2026-11-20&amp;durationMinutes=60
     */
    @GetMapping("/available-slots")
    @PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER')")
    public ResponseEntity<AvailableSlotsResponse> getAvailableSlots(
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String durationMinutes) {
        return ResponseEntity.ok(slotService.getAvailableSlots(providerId, date, durationMinutes));
    }
}
