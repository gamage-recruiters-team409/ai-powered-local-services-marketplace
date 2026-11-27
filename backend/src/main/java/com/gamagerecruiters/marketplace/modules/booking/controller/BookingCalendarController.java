package com.gamagerecruiters.marketplace.modules.booking.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.booking.dto.CalendarResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CalendarSubscriptionResponse;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingCalendarService;

/** Calendar view, .ics download and the private subscription link. */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/bookings/calendar")
public class BookingCalendarController {

    private final BookingCalendarService calendarService;

    public BookingCalendarController(BookingCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    /** GET /api/v1/bookings/calendar?from=2026-10-20&amp;to=2026-10-26 */
    @GetMapping
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<CalendarResponse> calendar(@RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to) {
        return ResponseEntity.ok(calendarService.calendar(from, to));
    }

    @GetMapping("/export.ics")
    @PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER')")
    public ResponseEntity<byte[]> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"localserve-bookings.ics\"")
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .body(calendarService.exportIcs().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/subscription")
    @PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER')")
    public ResponseEntity<CalendarSubscriptionResponse> subscription() {
        return ResponseEntity.ok(calendarService.getSubscription());
    }

    /** Creates or resets the private link; the response is the only time the full link is shown. */
    @PostMapping("/subscription")
    @PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER')")
    public ResponseEntity<CalendarSubscriptionResponse> createOrReset() {
        return ResponseEntity.ok(calendarService.createOrResetSubscription());
    }

    @DeleteMapping("/subscription")
    @PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER')")
    public ResponseEntity<Void> revoke() {
        calendarService.revokeSubscription();
        return ResponseEntity.noContent().build();
    }
}
