package com.gamagerecruiters.marketplace.modules.booking.controller;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingDetailsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingListItem;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPageResponse;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingQueryService;

/** My Bookings (customer / provider) and Booking Details. */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/bookings")
public class BookingQueryController {

    private final BookingQueryService queryService;

    public BookingQueryController(BookingQueryService queryService) {
        this.queryService = queryService;
    }

    /** GET /api/v1/bookings/customer?status=CONFIRMED,COMPLETED&page=0&size=10 */
    @GetMapping("/customer")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<BookingPageResponse<BookingListItem>> customerBookings(
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(queryService.listCustomerBookings(parseStatuses(status), page, size));
    }

    /** GET /api/v1/bookings/provider?status=PENDING_PROVIDER&page=0&size=10 */
    @GetMapping("/provider")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<BookingPageResponse<BookingListItem>> providerBookings(
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(queryService.listProviderBookings(parseStatuses(status), page, size));
    }

    /** Booking Details for its customer or provider (403 for anyone else). */
    @GetMapping("/{bookingId}")
    @PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER')")
    public ResponseEntity<BookingDetailsResponse> details(@PathVariable String bookingId) {
        return ResponseEntity.ok(queryService.getBookingDetails(bookingId));
    }

    /** "CONFIRMED,COMPLETED" or repeated ?status= → statuses; unknown value → 400 INVALID_STATUS. */
    static Set<BookingStatus> parseStatuses(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<BookingStatus> statuses = EnumSet.noneOf(BookingStatus.class);
        for (String value : values) {
            for (String part : value.split(",")) {
                String name = part.trim();
                if (name.isEmpty()) {
                    continue;
                }
                try {
                    statuses.add(BookingStatus.valueOf(name.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Unknown booking status: " + name);
                }
            }
        }
        return statuses.isEmpty() ? null : statuses;
    }
}
