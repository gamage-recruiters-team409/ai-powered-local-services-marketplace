package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingFacadeImplTest {

    private static final Instant NOW = Instant.parse("2026-12-01T05:00:00Z");
    private static final Instant SLOT_START = Instant.parse("2026-12-01T04:30:00Z");
    private static final Instant SLOT_END = Instant.parse("2026-12-01T06:30:00Z");
    private static final String CUSTOMER = "c-1";
    private static final String PROVIDER = "p-1";
    private static final String STRANGER = "x-9";

    @Mock
    private BookingRepository bookingRepository;

    private BookingFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new BookingFacadeImpl(bookingRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Booking booking(BookingStatus status) {
        Booking booking = new Booking();
        booking.setId("b-1");
        booking.setSeriesId("s-1");
        booking.setListingId("l-1");
        booking.setCustomerId(CUSTOMER);
        booking.setProviderId(PROVIDER);
        booking.setSlotStart(SLOT_START);
        booking.setSlotEnd(SLOT_END);
        booking.setStatus(status);
        return booking;
    }

    private void givenBooking(BookingStatus status) {
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking(status)));
    }

    private void givenSaveReturnsArgument() {
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("Read methods")
    class ReadMethods {

        @Test
        @DisplayName("hasOpenBookingsForCustomer checks the six open statuses")
        void hasOpenBookingsUsesOpenStatuses() {
            when(bookingRepository.existsByCustomerIdAndStatusIn(CUSTOMER, BookingStatus.OPEN_STATUSES)).thenReturn(true);

            assertThat(facade.hasOpenBookingsForCustomer(CUSTOMER)).isTrue();
        }

        @Test
        @DisplayName("hasOpenBookingsForCustomer returns false for a blank customer ID without querying")
        void hasOpenBookingsBlankId() {
            assertThat(facade.hasOpenBookingsForCustomer(" ")).isFalse();
            assertThat(facade.hasOpenBookingsForCustomer(null)).isFalse();
            verifyNoInteractions(bookingRepository);
        }

        @Test
        @DisplayName("getBookingParticipants returns IDs, status and the UTC time slot")
        void getParticipants() {
            givenBooking(BookingStatus.CONFIRMED);

            BookingParticipants participants = facade.getBookingParticipants("b-1");

            assertThat(participants).isEqualTo(new BookingParticipants(
                    "b-1", "s-1", "l-1", CUSTOMER, PROVIDER, BookingStatus.CONFIRMED, SLOT_START, SLOT_END));
        }

        @Test
        @DisplayName("getBookingParticipants throws BOOKING_NOT_FOUND for an unknown or blank ID")
        void getParticipantsNotFound() {
            when(bookingRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> facade.getBookingParticipants("missing"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .satisfies(e -> assertThat(((ResourceNotFoundException) e).getErrorCode())
                            .isEqualTo("BOOKING_NOT_FOUND"));
            assertThatThrownBy(() -> facade.getBookingParticipants(""))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("markInProgress / markCompletionPending (provider only)")
    class ProviderTransitions {

        @Test
        @DisplayName("Provider moves CONFIRMED to IN_PROGRESS; timeline records the provider")
        void providerStartsService() {
            givenBooking(BookingStatus.CONFIRMED);
            givenSaveReturnsArgument();

            BookingParticipants result = facade.markInProgress("b-1", PROVIDER);

            assertThat(result.status()).isEqualTo(BookingStatus.IN_PROGRESS);
            assertThat(result.slotStart()).isEqualTo(SLOT_START);
            verify(bookingRepository).save(argThat(saved ->
                    saved.getTimeline().size() == 1
                            && saved.getTimeline().get(0).getChangedBy().equals(PROVIDER)
                            && saved.getTimeline().get(0).getChangedAt().equals(NOW)));
        }

        @Test
        @DisplayName("Provider moves IN_PROGRESS to COMPLETION_PENDING")
        void providerMarksCompletionPending() {
            givenBooking(BookingStatus.IN_PROGRESS);
            givenSaveReturnsArgument();

            assertThat(facade.markCompletionPending("b-1", PROVIDER).status())
                    .isEqualTo(BookingStatus.COMPLETION_PENDING);
        }

        @Test
        @DisplayName("Customer cannot start the service (403, nothing saved)")
        void customerCannotStart() {
            givenBooking(BookingStatus.CONFIRMED);

            assertThatThrownBy(() -> facade.markInProgress("b-1", CUSTOMER))
                    .isInstanceOf(AccessDeniedException.class);
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("Another provider cannot mark completion pending (403)")
        void otherProviderDenied() {
            givenBooking(BookingStatus.IN_PROGRESS);

            assertThatThrownBy(() -> facade.markCompletionPending("b-1", STRANGER))
                    .isInstanceOf(AccessDeniedException.class);
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("SYSTEM cannot start the service (403)")
        void systemCannotStart() {
            givenBooking(BookingStatus.CONFIRMED);

            assertThatThrownBy(() -> facade.markInProgress("b-1", BookingFacade.SYSTEM_ACTOR))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Not allowed from the current status: 409 INVALID_STATUS_TRANSITION, nothing saved")
        void invalidTransition() {
            givenBooking(BookingStatus.PENDING_PROVIDER);

            assertThatThrownBy(() -> facade.markInProgress("b-1", PROVIDER))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertThat(((ResourceConflictException) e).getErrorCode())
                            .isEqualTo("INVALID_STATUS_TRANSITION"));
            verify(bookingRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markCompleted (customer or SYSTEM)")
    class CompleteTransitions {

        @Test
        @DisplayName("Customer confirms completion")
        void customerConfirms() {
            givenBooking(BookingStatus.COMPLETION_PENDING);
            givenSaveReturnsArgument();

            assertThat(facade.markCompleted("b-1", CUSTOMER).status()).isEqualTo(BookingStatus.COMPLETED);
        }

        @Test
        @DisplayName("SYSTEM auto-confirms after 48 h; timeline records SYSTEM")
        void systemAutoConfirms() {
            givenBooking(BookingStatus.COMPLETION_PENDING);
            givenSaveReturnsArgument();

            facade.markCompleted("b-1", BookingFacade.SYSTEM_ACTOR);

            verify(bookingRepository).save(argThat(saved ->
                    saved.getStatus() == BookingStatus.COMPLETED
                            && BookingStatusChange.SYSTEM.equals(saved.getTimeline().get(0).getChangedBy())));
        }

        @Test
        @DisplayName("Provider cannot confirm completion (403)")
        void providerCannotConfirm() {
            givenBooking(BookingStatus.COMPLETION_PENDING);

            assertThatThrownBy(() -> facade.markCompleted("b-1", PROVIDER))
                    .isInstanceOf(AccessDeniedException.class);
            verify(bookingRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markDisputed (customer or provider)")
    class DisputeTransitions {

        @Test
        @DisplayName("Customer raises an issue from COMPLETION_PENDING; reason is stored trimmed")
        void customerDisputes() {
            givenBooking(BookingStatus.COMPLETION_PENDING);
            givenSaveReturnsArgument();

            BookingParticipants result = facade.markDisputed("b-1", CUSTOMER, "  Work not finished ");

            assertThat(result.status()).isEqualTo(BookingStatus.DISPUTED);
            verify(bookingRepository).save(argThat(saved ->
                    "Work not finished".equals(saved.getTimeline().get(0).getReason())));
        }

        @Test
        @DisplayName("Provider can also open a dispute")
        void providerDisputes() {
            givenBooking(BookingStatus.IN_PROGRESS);
            givenSaveReturnsArgument();

            assertThat(facade.markDisputed("b-1", PROVIDER, null).status()).isEqualTo(BookingStatus.DISPUTED);
        }

        @Test
        @DisplayName("A non-participant or SYSTEM cannot open a dispute (403)")
        void strangerAndSystemDenied() {
            givenBooking(BookingStatus.COMPLETION_PENDING);

            assertThatThrownBy(() -> facade.markDisputed("b-1", STRANGER, "x"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> facade.markDisputed("b-1", BookingFacade.SYSTEM_ACTOR, "x"))
                    .isInstanceOf(AccessDeniedException.class);
            verify(bookingRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Common error cases")
    class CommonErrors {

        @Test
        @DisplayName("Unknown booking: 404 BOOKING_NOT_FOUND")
        void unknownBooking() {
            when(bookingRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> facade.markInProgress("missing", PROVIDER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Blank actor: 403, nothing saved")
        void blankActor() {
            givenBooking(BookingStatus.CONFIRMED);

            assertThatThrownBy(() -> facade.markInProgress("b-1", " "))
                    .isInstanceOf(AccessDeniedException.class);
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("Concurrent change (optimistic lock): 409 BOOKING_UPDATE_CONFLICT")
        void optimisticLockConflict() {
            givenBooking(BookingStatus.CONFIRMED);
            when(bookingRepository.save(any(Booking.class)))
                    .thenThrow(new OptimisticLockingFailureException("version mismatch"));

            assertThatThrownBy(() -> facade.markInProgress("b-1", PROVIDER))
                    .isInstanceOf(ResourceConflictException.class)
                    .satisfies(e -> assertThat(((ResourceConflictException) e).getErrorCode())
                            .isEqualTo("BOOKING_UPDATE_CONFLICT"));
        }
    }
}
