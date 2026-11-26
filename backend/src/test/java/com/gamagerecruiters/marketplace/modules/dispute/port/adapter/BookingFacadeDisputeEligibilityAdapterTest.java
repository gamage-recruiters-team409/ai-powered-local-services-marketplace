package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;

/** The eligibility adapter calls BookingFacade.assertDisputeEligible with the logged-in user and maps its answer. */
@ExtendWith(MockitoExtension.class)
class BookingFacadeDisputeEligibilityAdapterTest {

    private static final String CUSTOMER = "c-1";

    @Mock
    private BookingFacade bookingFacade;

    private BookingFacadeDisputeEligibilityAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BookingFacadeDisputeEligibilityAdapter(bookingFacade);
        UserPrincipal principal = new UserPrincipal(CUSTOMER, "customer@example.test", Role.CUSTOMER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static BookingParticipants booking(BookingStatus status) {
        Instant now = Instant.parse("2026-11-17T10:00:00Z");
        return new BookingParticipants("b-1", "s-1", "l-1", CUSTOMER, "p-1", status, now, now.plusSeconds(3600));
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "IN_PROGRESS", "COMPLETION_PENDING", "COMPLETED",
            "DISPUTED"})
    @DisplayName("eligible -> assertDisputeEligible(bookingId, logged-in user) is used; DISPUTED is reported as "
            + "alreadyDisputed (no interim fail-closed check any more)")
    void eligibleBookingIsMapped(BookingStatus status) {
        when(bookingFacade.assertDisputeEligible("b-1", CUSTOMER)).thenReturn(booking(status));

        BookingSnapshot snapshot = adapter.assertEligible("b-1");

        assertThat(snapshot).isEqualTo(new BookingSnapshot("b-1", CUSTOMER, "p-1", status == BookingStatus.DISPUTED));
        verify(bookingFacade, never()).getBookingParticipants(anyString());
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), any());
    }

    static Stream<RuntimeException> facadeErrors() {
        return Stream.of(
                new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found"),
                new ResourceConflictException("INVALID_STATUS_TRANSITION", "Not disputable"),
                new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "Window closed"),
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Sign in"),
                new AccessDeniedException("Not a participant"));
    }

    @ParameterizedTest
    @MethodSource("facadeErrors")
    @DisplayName("404 BOOKING_NOT_FOUND, 409 INVALID_STATUS_TRANSITION / DISPUTE_WINDOW_CLOSED, 401 and 403 pass "
            + "through unchanged")
    void facadeErrorsPassThrough(RuntimeException error) {
        when(bookingFacade.assertDisputeEligible("b-1", CUSTOMER)).thenThrow(error);

        assertThat(catchThrowable(() -> adapter.assertEligible("b-1"))).isSameAs(error);
    }

    @Test
    @DisplayName("not logged in -> a null actor is passed, so the Booking module answers 401 itself")
    void anonymousActor() {
        SecurityContextHolder.clearContext();
        ApiException unauthorized = new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Sign in");
        when(bookingFacade.assertDisputeEligible("b-1", null)).thenThrow(unauthorized);

        assertThat(catchThrowable(() -> adapter.assertEligible("b-1"))).isSameAs(unauthorized);
    }
}
