package com.gamagerecruiters.marketplace.modules.completion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.domain.IssueType;
import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.gamagerecruiters.marketplace.modules.completion.dto.NoShowReportResponse;
import com.gamagerecruiters.marketplace.modules.completion.dto.RaiseIssueRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ReportNoShowRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ServiceCompletionResponse;
import com.gamagerecruiters.marketplace.modules.completion.repository.ServiceCompletionRepository;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;

/** Raise Issue / No-Show open the WP09 dispute case after the booking is DISPUTED (agreed with Injas). */
@ExtendWith(MockitoExtension.class)
class CompletionDisputeLinkTest {

    private static final String BOOKING = "booking-1";
    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";
    private static final Instant NOW = Instant.parse("2026-11-18T08:00:00Z");

    @Mock
    private ServiceCompletionRepository repository;

    @Mock
    private BookingFacade bookingFacade;

    @Mock
    private CompletionAtomicUpdates atomicUpdates;

    private final List<String> calls = new ArrayList<>();
    private String nextDisputeId = "dsp-1";
    private ServiceCompletionServiceImpl service;

    @BeforeEach
    void setUp() {
        CompletionDisputes recording = (bookingId, actorId, role, category, description) -> {
            calls.add(bookingId + "|" + actorId + "|" + role + "|" + category + "|" + description);
            return nextDisputeId;
        };
        service = new ServiceCompletionServiceImpl(repository, bookingFacade, atomicUpdates,
                CompletionTransactions.direct(), recording, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static BookingParticipants booking(BookingStatus status, Instant slotStart) {
        return new BookingParticipants(BOOKING, null, "listing-1", CUSTOMER, PROVIDER, status, slotStart, slotStart.plusSeconds(7200));
    }

    private static ServiceCompletion marked() {
        ServiceCompletion completion = new ServiceCompletion();
        completion.setId("comp-1");
        completion.setBookingId(BOOKING);
        completion.setCustomerId(CUSTOMER);
        completion.setProviderId(PROVIDER);
        completion.setStartedAt(NOW.minusSeconds(7200));
        completion.setMarkedCompletedAt(NOW.minusSeconds(600));
        return completion;
    }

    private void stubRaiseIssue() {
        when(bookingFacade.getBookingParticipants(BOOKING))
                .thenReturn(booking(BookingStatus.COMPLETION_PENDING, NOW.minusSeconds(10800)));
        when(repository.findByBookingId(BOOKING)).thenReturn(Optional.of(marked()));
        ServiceCompletion raised = marked();
        raised.setIssueRaisedAt(NOW);
        when(atomicUpdates.raiseIssue(BOOKING, NOW, NOW.minus(Duration.ofHours(48)))).thenReturn(Optional.of(raised));
    }

    @Test
    @DisplayName("Raise issue: dispute opened with the mapped category and the description, its ID is stored and returned")
    void raiseIssueOpensDispute() {
        stubRaiseIssue();
        ServiceCompletion withDispute = marked();
        withDispute.setIssueRaisedAt(NOW);
        withDispute.setDisputeId("dsp-1");
        when(atomicUpdates.recordDisputeId(BOOKING, "dsp-1", NOW)).thenReturn(Optional.of(withDispute));

        ServiceCompletionResponse response = service.raiseIssue(BOOKING, CUSTOMER,
                new RaiseIssueRequest("  The balcony was not cleaned  ", IssueType.NOT_COMPLETED));

        assertEquals("dsp-1", response.disputeId());
        assertEquals(List.of(BOOKING + "|" + CUSTOMER + "|CUSTOMER|SERVICE_NOT_DELIVERED|The balcony was not cleaned"), calls);
        verify(bookingFacade).markDisputed(BOOKING, CUSTOMER, "CUSTOMER_ISSUE: The balcony was not cleaned");
    }

    @Test
    @DisplayName("Raise issue: when the dispute cannot be opened now, the issue still succeeds without a dispute ID")
    void raiseIssueWithoutDispute() {
        nextDisputeId = null;
        stubRaiseIssue();

        ServiceCompletionResponse response = service.raiseIssue(BOOKING, CUSTOMER,
                new RaiseIssueRequest("The balcony was not cleaned"));

        assertNull(response.disputeId());
        assertEquals(NOW, response.issueRaisedAt());
        verify(atomicUpdates, never()).recordDisputeId(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Issue type -> dispute category (agreed mapping; no type -> OTHER)")
    void categoryMapping() {
        assertEquals(DisputeCategory.SERVICE_NOT_DELIVERED, ServiceCompletionServiceImpl.disputeCategoryFor(IssueType.NOT_COMPLETED));
        assertEquals(DisputeCategory.POOR_QUALITY, ServiceCompletionServiceImpl.disputeCategoryFor(IssueType.POOR_QUALITY));
        assertEquals(DisputeCategory.PROPERTY_DAMAGE, ServiceCompletionServiceImpl.disputeCategoryFor(IssueType.DAMAGE));
        assertEquals(DisputeCategory.OTHER, ServiceCompletionServiceImpl.disputeCategoryFor(IssueType.DIFFERENT_SERVICE));
        assertEquals(DisputeCategory.OTHER, ServiceCompletionServiceImpl.disputeCategoryFor(IssueType.OTHER));
        assertEquals(DisputeCategory.OTHER, ServiceCompletionServiceImpl.disputeCategoryFor(null));
    }

    @Test
    @DisplayName("No-show: dispute NO_SHOW opened by the reporting party; default text when there is no note")
    void noShowOpensDispute() {
        when(bookingFacade.getBookingParticipants(BOOKING)).thenReturn(booking(BookingStatus.CONFIRMED, NOW.minusSeconds(3 * 3600)));
        when(repository.existsByBookingId(BOOKING)).thenReturn(false);
        when(bookingFacade.markDisputed(BOOKING, CUSTOMER, "NO_SHOW_BY_PROVIDER"))
                .thenReturn(booking(BookingStatus.DISPUTED, NOW.minusSeconds(3 * 3600)));
        when(bookingFacade.markDisputed(BOOKING, PROVIDER, "NO_SHOW_BY_CUSTOMER: Nobody home"))
                .thenReturn(booking(BookingStatus.DISPUTED, NOW.minusSeconds(3 * 3600)));

        NoShowReportResponse byCustomer = service.reportNoShow(BOOKING, CUSTOMER, null);
        NoShowReportResponse byProvider = service.reportNoShow(BOOKING, PROVIDER, new ReportNoShowRequest(" Nobody home "));

        assertEquals("dsp-1", byCustomer.disputeId());
        assertEquals("DISPUTED", byProvider.bookingStatus());
        assertEquals(List.of(
                BOOKING + "|" + CUSTOMER + "|CUSTOMER|NO_SHOW|The provider did not arrive for the booking.",
                BOOKING + "|" + PROVIDER + "|PROVIDER|NO_SHOW|Nobody home"), calls);
    }

    @Test
    @DisplayName("WP09 adapter: calls raiseDispute with no evidence; any dispute error becomes 'no dispute' (null)")
    void wp09Adapter() {
        DisputeService disputeService = org.mockito.Mockito.mock(DisputeService.class);
        DisputeDetailResponse created = new DisputeDetailResponse("dsp-9", BOOKING, CUSTOMER, PROVIDER, CUSTOMER,
                Role.CUSTOMER, DisputeCategory.POOR_QUALITY, "Bad work done here", List.of(), null, null, null, NOW, NOW,
                List.of());
        when(disputeService.raiseDispute(CUSTOMER, Role.CUSTOMER,
                new RaiseDisputeRequest(BOOKING, DisputeCategory.POOR_QUALITY, "Bad work done here", List.of())))
                .thenReturn(created);
        assertEquals("dsp-9", new Wp09CompletionDisputes(disputeService).openDispute(BOOKING, CUSTOMER, Role.CUSTOMER,
                DisputeCategory.POOR_QUALITY, "Bad work done here"));

        DisputeService failing = org.mockito.Mockito.mock(DisputeService.class);
        when(failing.raiseDispute(any(), any(), any()))
                .thenThrow(new ResourceConflictException("DISPUTE_ELIGIBILITY_UNAVAILABLE", "Not yet"));
        assertNull(new Wp09CompletionDisputes(failing).openDispute(BOOKING, CUSTOMER, Role.CUSTOMER,
                DisputeCategory.OTHER, "Something else"));
    }
}
