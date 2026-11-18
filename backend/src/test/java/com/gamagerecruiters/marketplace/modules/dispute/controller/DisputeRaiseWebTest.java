package com.gamagerecruiters.marketplace.modules.dispute.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeEligibilityPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.adapter.BookingFacadeDisputeStatusAdapter;
import com.gamagerecruiters.marketplace.modules.dispute.port.adapter.BookingFacadeLookupAdapter;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeBookingSyncStore;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeServiceImpl;

/**
 * POST /disputes over HTTP: real SecurityConfig, controller, DisputeServiceImpl and booking adapters; only the
 * BookingFacade, the repository and the payout stub are mocked. Shows the status code and error code a client
 * receives for each booking-module error, and that the actor comes from the token, never from the body.
 */
@WebMvcTest(DisputeController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        DisputeExceptionHandler.class,
        AdminAccessPolicyService.class,
        DisputeServiceImpl.class,
        BookingFacadeLookupAdapter.class,
        BookingFacadeDisputeStatusAdapter.class
})
@ActiveProfiles("test")
class DisputeRaiseWebTest {

    private static final String DISPUTES = "/api/v1/disputes";
    private static final String BOOKING_ID = "booking-1";
    private static final String CUSTOMER_ID = "user-CUSTOMER";
    private static final String PROVIDER_ID = "prov-1";
    private static final String TOKEN = "token-customer";
    /** Includes an extra "actorId" and "raisedBy" that must be ignored. */
    private static final String BODY = "{\"bookingId\":\"" + BOOKING_ID + "\",\"category\":\"NO_SHOW\","
            + "\"description\":\"Provider did not come\",\"evidence\":[],"
            + "\"actorId\":\"evil-actor\",\"raisedBy\":\"evil-actor\"}";
    private static final String REASON = "Dispute raised: NO_SHOW";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;
    @MockitoBean
    private UserInternalService userInternalService;
    @MockitoBean
    private BookingFacade bookingFacade;
    @MockitoBean
    private DisputeRepository disputeRepository;
    @MockitoBean
    private com.gamagerecruiters.marketplace.modules.dispute.service.DisputeResolutionService resolutionService;
    @MockitoBean
    private com.gamagerecruiters.marketplace.modules.dispute.service.DisputeAdminQueryService adminQueryService;
    @MockitoBean
    private PayoutHoldPort payoutHoldPort;
    @MockitoBean
    private DisputeBookingSyncStore syncStore;
    @MockitoBean
    private DisputeEligibilityPort eligibilityPort;

    @BeforeEach
    void setUp() {
        when(tokenService.validateToken(TOKEN)).thenReturn(true);
        when(tokenService.getUserIdFromToken(TOKEN)).thenReturn(CUSTOMER_ID);
        when(tokenService.getRoleFromToken(TOKEN)).thenReturn(Role.CUSTOMER);
        User user = new User();
        user.setId(CUSTOMER_ID);
        user.setEmail("customer@test.local");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(CUSTOMER_ID)).thenReturn(user);
    }

    private static MockHttpServletRequestBuilder raise() {
        return post(DISPUTES).header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    /** What BookingFacade.assertDisputeEligible returns (through the eligibility port) for an eligible booking. */
    private void bookingIs(BookingStatus status, String customerId) {
        java.time.Instant now = java.time.Instant.now();
        when(bookingFacade.getBookingParticipants(BOOKING_ID)).thenReturn(new BookingParticipants(
                BOOKING_ID, "series-1", "listing-1", customerId, PROVIDER_ID, status, now, now.plusSeconds(3600)));
        when(eligibilityPort.assertEligible(BOOKING_ID)).thenReturn(
                new BookingSnapshot(BOOKING_ID, customerId, PROVIDER_ID, status == BookingStatus.DISPUTED));
    }

    /** Insert assigns an id; marking SYNCED returns the same record as SYNCED. */
    private void saveWorks() {
        Dispute[] inserted = new Dispute[1];
        when(disputeRepository.insert(any(Dispute.class))).thenAnswer(invocation -> {
            Dispute dispute = invocation.getArgument(0);
            dispute.setId("dispute-1");
            inserted[0] = dispute;
            return dispute;
        });
        when(syncStore.markSynced(eq("dispute-1"), anyString())).thenAnswer(invocation -> {
            inserted[0].setBookingSyncState(BookingSyncState.SYNCED);
            return Optional.of(inserted[0]);
        });
    }

    @Test
    @DisplayName("201: booking moved to DISPUTED as the token's user (body actorId/raisedBy ignored), dispute returned")
    void raiseCreated() throws Exception {
        bookingIs(BookingStatus.COMPLETION_PENDING, CUSTOMER_ID);
        saveWorks();

        mockMvc.perform(raise())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("dispute-1"))
                .andExpect(jsonPath("$.status").value("AWAITING_RESPONSE"))
                .andExpect(jsonPath("$.raisedBy").value(CUSTOMER_ID))
                .andExpect(jsonPath("$.providerId").value(PROVIDER_ID));

        verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, REASON);
        verify(bookingFacade, never()).markDisputed(anyString(), eq("evil-actor"), anyString());
    }

    @Test
    @DisplayName("201: booking already DISPUTED and eligible -> no second markDisputed")
    void raiseOnAlreadyDisputedBooking() throws Exception {
        bookingIs(BookingStatus.DISPUTED, CUSTOMER_ID);
        saveWorks();

        mockMvc.perform(raise()).andExpect(status().isCreated());

        verify(eligibilityPort).assertEligible(BOOKING_ID);
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("409 DISPUTE_WINDOW_CLOSED when the booking is already DISPUTED but the window has passed; nothing saved")
    void alreadyDisputedExpired() throws Exception {
        when(eligibilityPort.assertEligible(BOOKING_ID))
                .thenThrow(new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "The dispute window closes"));

        mockMvc.perform(raise())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DISPUTE_WINDOW_CLOSED"));
        verify(disputeRepository, never()).insert(any(Dispute.class));
    }

    @Test
    @DisplayName("404 BOOKING_NOT_FOUND from the facade reaches the client unchanged")
    void unknownBooking() throws Exception {
        when(eligibilityPort.assertEligible(BOOKING_ID))
                .thenThrow(new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found"));

        mockMvc.perform(raise())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BOOKING_NOT_FOUND"));
        verify(disputeRepository, never()).insert(any(Dispute.class));
    }

    @Test
    @DisplayName("403 ACCESS_DENIED when the caller is not a party of the booking (booking untouched)")
    void notAParty() throws Exception {
        bookingIs(BookingStatus.COMPLETION_PENDING, "another-customer");

        mockMvc.perform(raise())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
        verify(disputeRepository, never()).insert(any(Dispute.class));
    }

    @Test
    @DisplayName("409 DISPUTE_ALREADY_EXISTS when an active dispute exists (booking untouched)")
    void duplicate() throws Exception {
        bookingIs(BookingStatus.DISPUTED, CUSTOMER_ID);
        Dispute active = new Dispute();
        active.setId("other-dispute");
        active.setRaisedBy("prov-1");
        active.setBookingSyncState(BookingSyncState.SYNCED);
        when(disputeRepository.findByActiveBookingKey(BOOKING_ID)).thenReturn(Optional.of(active));

        mockMvc.perform(raise())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DISPUTE_ALREADY_EXISTS"));
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
    }

    static Stream<Arguments> facadeErrors() {
        return Stream.of(
                Arguments.of(new AccessDeniedException("denied"), HttpStatus.FORBIDDEN, "ACCESS_DENIED", true),
                Arguments.of(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required"),
                        HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", false),
                Arguments.of(new ResourceConflictException("INVALID_STATUS_TRANSITION", "Booking cannot move"),
                        HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", true),
                Arguments.of(new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "The dispute window closes"),
                        HttpStatus.CONFLICT, "DISPUTE_WINDOW_CLOSED", true),
                Arguments.of(new ResourceConflictException("BOOKING_UPDATE_CONFLICT", "Updated at the same time"),
                        HttpStatus.CONFLICT, "BOOKING_UPDATE_CONFLICT", false));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("facadeErrors")
    @DisplayName("markDisputed errors keep their status and code over HTTP; the intent is removed only for a confirmed "
            + "rejection (booking re-read as not DISPUTED), otherwise kept for the reconciler")
    void facadeErrorsOverHttp(RuntimeException error, HttpStatus status, String code, boolean removed) throws Exception {
        bookingIs(BookingStatus.COMPLETED, CUSTOMER_ID);
        saveWorks();
        when(bookingFacade.markDisputed(BOOKING_ID, CUSTOMER_ID, REASON)).thenThrow(error);

        mockMvc.perform(raise())
                .andExpect(status().is(status.value()))
                .andExpect(jsonPath("$.error").value(code));
        if (removed) {
            verify(syncStore).deleteIfPending(eq("dispute-1"), anyString());
        } else {
            verify(syncStore, never()).deleteIfPending(anyString(), anyString());
        }
        verify(syncStore, never()).markSynced(anyString(), anyString());
    }

    @Test
    @DisplayName("503 DISPUTE_SAVE_FAILED when the intent record cannot be inserted; the booking is never touched")
    void saveFailure() throws Exception {
        bookingIs(BookingStatus.COMPLETION_PENDING, CUSTOMER_ID);
        when(disputeRepository.insert(any(Dispute.class))).thenThrow(new DataAccessResourceFailureException("down"));

        mockMvc.perform(raise())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DISPUTE_SAVE_FAILED"));
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("503 DISPUTE_SYNC_PENDING when the booking moved but the dispute could not be marked SYNCED yet")
    void syncPending() throws Exception {
        bookingIs(BookingStatus.COMPLETION_PENDING, CUSTOMER_ID);
        saveWorks();
        doThrow(new DataAccessResourceFailureException("down")).when(syncStore).markSynced(eq("dispute-1"), anyString());

        mockMvc.perform(raise())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DISPUTE_SYNC_PENDING"));
        verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, REASON);
        verify(syncStore, never()).deleteIfPending(anyString(), anyString());
    }
}
