package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingAddOnSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingAddress;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingPriceSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingDetailsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingListItem;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPageResponse;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingQueryServiceImplTest {

    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private BookingQueryServiceImpl service;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static Booking booking(BookingStatus status) {
        Booking b = new Booking();
        b.setId("b-1");
        b.setRef("BK-1");
        b.setCustomerId(CUSTOMER);
        b.setProviderId(PROVIDER);
        b.setListingId("lst-1");
        // 2026-12-01 10:00–12:00 Asia/Colombo
        b.setSlotStart(Instant.parse("2026-12-01T04:30:00Z"));
        b.setSlotEnd(Instant.parse("2026-12-01T06:30:00Z"));
        b.setDurationMinutes(120);
        b.setAddress(new BookingAddress("12 Lake Rd", "Apt 3", "Kandy", "Kandy", "20000"));
        b.setNotes("Gate code 1234");
        b.setStatus(status);
        b.setCreatedAt(Instant.parse("2026-11-20T03:00:00Z"));
        b.setPriceSnapshot(new BookingPriceSnapshot("AC Repair", "cat-1", "FIXED", new BigDecimal("5000"),
                List.of(new BookingAddOnSnapshot("ad-1", "Gas refill", new BigDecimal("1500"), 30)),
                new BigDecimal("6500"), "LKR", new BigDecimal("0.10"), 120, Instant.parse("2026-11-20T03:00:00Z")));
        b.setTimeline(List.of(
                new BookingStatusChange(null, BookingStatus.PENDING_PROVIDER, Instant.parse("2026-11-20T03:00:00Z"),
                        CUSTOMER, "Booking requested"),
                new BookingStatusChange(BookingStatus.PENDING_PROVIDER, BookingStatus.AWAITING_PAYMENT,
                        Instant.parse("2026-11-20T03:10:00Z"), PROVIDER, "Accepted")));
        return b;
    }

    @Test
    @DisplayName("Customer list without status filter: own bookings, newest slot first, Colombo local times")
    void customerListAll() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(bookingRepository.findByCustomerId(eq(CUSTOMER), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(booking(BookingStatus.CONFIRMED)), inv.getArgument(1), 1));

        BookingPageResponse<BookingListItem> page = service.listCustomerBookings(null, 0, 10);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(bookingRepository).findByCustomerId(eq(CUSTOMER), pageable.capture());
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "slotStart"));
        assertThat(page.totalElements()).isEqualTo(1);
        BookingListItem item = page.content().get(0);
        assertThat(item.serviceTitle()).isEqualTo("AC Repair");
        assertThat(item.totalPrice()).isEqualByComparingTo("6500");
        assertThat(item.currency()).isEqualTo("LKR");
        assertThat(item.localDate()).isEqualTo("2026-12-01");
        assertThat(item.localStart()).isEqualTo("10:00");
        assertThat(item.localEnd()).isEqualTo("12:00");
        assertThat(item.district()).isEqualTo("Kandy");
    }

    @Test
    @DisplayName("Customer list with status filter uses the status query")
    void customerListFiltered() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        Set<BookingStatus> statuses = Set.of(BookingStatus.COMPLETED);
        when(bookingRepository.findByCustomerIdAndStatusIn(eq(CUSTOMER), eq(statuses), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(2), 0));

        BookingPageResponse<BookingListItem> page = service.listCustomerBookings(statuses, 1, 5);

        assertThat(page.content()).isEmpty();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("Provider list uses the provider ID of the logged-in user")
    void providerList() {
        loginAs(PROVIDER, Role.PROVIDER);
        when(bookingRepository.findByProviderIdAndStatusIn(eq(PROVIDER), eq(Set.of(BookingStatus.PENDING_PROVIDER)),
                any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(booking(BookingStatus.PENDING_PROVIDER)),
                        inv.getArgument(2), 1));

        BookingPageResponse<BookingListItem> page =
                service.listProviderBookings(Set.of(BookingStatus.PENDING_PROVIDER), 0, 10);

        assertThat(page.content()).extracting(BookingListItem::status).containsExactly(BookingStatus.PENDING_PROVIDER);
    }

    @Test
    @DisplayName("Invalid page or size → 400 and the database is not called")
    void invalidPaging() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        assertThatThrownBy(() -> service.listCustomerBookings(null, -1, 10))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_PAGE"));
        assertThatThrownBy(() -> service.listCustomerBookings(null, 0, 0))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_PAGE_SIZE"));
        assertThatThrownBy(() -> service.listProviderBookings(null, 0, 51))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_PAGE_SIZE"));
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("Not logged in → 401")
    void notLoggedIn() {
        assertThatThrownBy(() -> service.listCustomerBookings(null, 0, 10))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("Details for the customer: full address, notes, price and timeline with roles (no user IDs)")
    void detailsCustomer() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking(BookingStatus.AWAITING_PAYMENT)));

        BookingDetailsResponse d = service.getBookingDetails("b-1");

        assertThat(d.viewerRole()).isEqualTo("CUSTOMER");
        assertThat(d.addressVisible()).isTrue();
        assertThat(d.address().line1()).isEqualTo("12 Lake Rd");
        assertThat(d.notes()).isEqualTo("Gate code 1234");
        assertThat(d.price().addOns()).extracting(BookingDetailsResponse.AddOn::title).containsExactly("Gas refill");
        assertThat(d.price().totalPrice()).isEqualByComparingTo("6500");
        assertThat(d.timeline()).extracting(BookingDetailsResponse.TimelineEntry::actor)
                .containsExactly("CUSTOMER", "PROVIDER");
    }

    @Test
    @DisplayName("Details for the provider before CONFIRMED: district only, no notes (C-08)")
    void detailsProviderMasked() {
        loginAs(PROVIDER, Role.PROVIDER);
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking(BookingStatus.PENDING_PROVIDER)));

        BookingDetailsResponse d = service.getBookingDetails("b-1");

        assertThat(d.viewerRole()).isEqualTo("PROVIDER");
        assertThat(d.addressVisible()).isFalse();
        assertThat(d.address().line1()).isNull();
        assertThat(d.address().line2()).isNull();
        assertThat(d.address().city()).isNull();
        assertThat(d.address().postalCode()).isNull();
        assertThat(d.address().district()).isEqualTo("Kandy");
        assertThat(d.notes()).isNull();
    }

    @Test
    @DisplayName("Details for the provider after CONFIRMED: full address and notes")
    void detailsProviderVisible() {
        loginAs(PROVIDER, Role.PROVIDER);
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking(BookingStatus.CONFIRMED)));

        BookingDetailsResponse d = service.getBookingDetails("b-1");

        assertThat(d.addressVisible()).isTrue();
        assertThat(d.address().line1()).isEqualTo("12 Lake Rd");
        assertThat(d.notes()).isEqualTo("Gate code 1234");
    }

    @Test
    @DisplayName("Details for a user who is not part of the booking → 403")
    void detailsOtherUser() {
        loginAs("cust-2", Role.CUSTOMER);
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking(BookingStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.getBookingDetails("b-1")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Details of a missing booking → 404 BOOKING_NOT_FOUND")
    void detailsMissing() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(bookingRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBookingDetails("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Timeline: SYSTEM stays SYSTEM, any other user (admin) shows as ADMIN")
    void timelineActors() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        Booking b = booking(BookingStatus.REFUNDED);
        b.setTimeline(List.of(
                new BookingStatusChange(BookingStatus.PENDING_PROVIDER, BookingStatus.EXPIRED, Instant.now(),
                        BookingStatusChange.SYSTEM, "Expired"),
                new BookingStatusChange(BookingStatus.DISPUTED, BookingStatus.REFUNDED, Instant.now(),
                        "admin-7", "Dispute settled")));
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(b));

        assertThat(service.getBookingDetails("b-1").timeline())
                .extracting(BookingDetailsResponse.TimelineEntry::actor).containsExactly("SYSTEM", "ADMIN");
    }
}
