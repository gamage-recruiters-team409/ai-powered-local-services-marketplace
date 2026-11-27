package com.gamagerecruiters.marketplace.modules.booking.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingCalendarService;

/**
 * Public calendar feed for calendar apps (they cannot log in). The secret in the URL is the only key:
 * unknown or reset links get 404, and only booking times, service and district are in the feed.
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/calendar/feed")
public class CalendarFeedController {

    private final BookingCalendarService calendarService;

    public CalendarFeedController(BookingCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/{token}.ics")
    public ResponseEntity<byte[]> feed(@PathVariable String token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .body(calendarService.feed(token).getBytes(StandardCharsets.UTF_8));
    }
}
