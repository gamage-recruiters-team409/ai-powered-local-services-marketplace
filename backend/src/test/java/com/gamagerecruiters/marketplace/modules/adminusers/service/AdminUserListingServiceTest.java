package com.gamagerecruiters.marketplace.modules.adminusers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.Section;
import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort.ListingDetail;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort.ReviewDecision;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderProfilePort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderReviewStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserBookingStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort.UserAccount;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDisputeStatsPort;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

class AdminUserListingServiceTest {

    private static final String PROVIDER = "65f1c0aa11bb22cc33a1b2c3";
    private static final String LISTING = "65f1c0aa11bb22cc33a1aaaa";
    private static final String NOTE = "Contact details removed by the provider";

    private UserDirectoryPort directory;
    private ProviderListingPort listings;
    private AdminUserListingService service;

    @BeforeEach
    void setUp() {
        directory = mock(UserDirectoryPort.class);
        listings = mock(ProviderListingPort.class);
        AdminUsersQueryService query = new AdminUsersQueryService(directory, mock(UserBookingStatsPort.class),
                mock(ProviderReviewStatsPort.class), mock(ProviderProfilePort.class), listings,
                mock(UserDisputeStatsPort.class), mock(AccountActionAuditPort.class));
        service = new AdminUserListingService(query, listings);
        account(PROVIDER, Role.PROVIDER);
    }

    private void account(String id, Role role) {
        when(directory.findById(id)).thenReturn(Optional.of(new UserAccount(id, "Sarah Smith", "s@example.test",
                null, role, UserStatus.ACTIVE, true, Instant.parse("2026-01-01T00:00:00Z"))));
    }

    private static ListingDetail listing(String providerId) {
        return new ListingDetail(LISTING, providerId, "Commercial Deep Cleaning", "Cleaning",
                new BigDecimal("12000"), "FIXED", "FLAGGED", null, Instant.parse("2026-10-24T00:00:00Z"),
                null, List.of("CONTACT_DETAILS"), "Call +94 77 123 4567", null);
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    // ---------- detail ----------

    @Test
    @DisplayName("detail: this provider's listing -> available with its data; hiddenSince stays null when not recorded")
    void detail() {
        when(listings.listingDetail(LISTING)).thenReturn(Optional.of(listing(PROVIDER)));

        Section<ListingDetail> result = service.detail(PROVIDER, LISTING);

        assertThat(result.available()).isTrue();
        assertThat(result.data().title()).isEqualTo("Commercial Deep Cleaning");
        assertThat(result.data().hiddenSince()).isNull();
    }

    @Test
    @DisplayName("detail: source cannot provide it -> available=false NOT_CONNECTED, no data")
    void detailNotConnected() {
        when(listings.listingDetail(LISTING)).thenReturn(Optional.empty());

        Section<ListingDetail> result = service.detail(PROVIDER, LISTING);

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo(Section.NOT_CONNECTED);
        assertThat(result.data()).isNull();
    }

    @Test
    @DisplayName("detail: another provider's listing, a missing listing or an impossible id -> 404 LISTING_NOT_FOUND")
    void detailNotThisProvider() {
        when(listings.listingDetail(LISTING)).thenReturn(Optional.of(listing("someone-else")));
        assertApiError(catchThrowable(() -> service.detail(PROVIDER, LISTING)), HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND");

        when(listings.listingDetail("missing")).thenThrow(new ResourceNotFoundException("LISTING_NOT_FOUND", "id missing"));
        Throwable thrown = catchThrowable(() -> service.detail(PROVIDER, "missing"));
        assertApiError(thrown, HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND");
        assertThat(thrown.getMessage()).doesNotContain("missing");

        assertApiError(catchThrowable(() -> service.detail(PROVIDER, "x".repeat(65))), HttpStatus.NOT_FOUND,
                "LISTING_NOT_FOUND");
    }

    @Test
    @DisplayName("detail of a customer's 'listing' -> 404 LISTING_NOT_FOUND; unknown user -> 404 USER_NOT_FOUND")
    void detailWrongUser() {
        account("cust", Role.CUSTOMER);
        assertApiError(catchThrowable(() -> service.detail("cust", LISTING)), HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND");
        when(directory.findById("nobody")).thenReturn(Optional.empty());
        assertApiError(catchThrowable(() -> service.detail("nobody", LISTING)), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        verify(listings, never()).listingDetail(anyString());
    }

    // ---------- review ----------

    @Test
    @DisplayName("review while the listing port cannot apply decisions -> 503 LISTING_ACTION_UNAVAILABLE, nothing changed")
    void reviewUnavailable() {
        when(listings.supportsListingReview()).thenReturn(false);

        assertApiError(catchThrowable(() -> service.review("admin-1", PROVIDER, LISTING, "MAKE_VISIBLE", NOTE)),
                HttpStatus.SERVICE_UNAVAILABLE, "LISTING_ACTION_UNAVAILABLE");
        assertApiError(catchThrowable(() -> service.review("admin-1", PROVIDER, LISTING, "KEEP_HIDDEN", NOTE)),
                HttpStatus.SERVICE_UNAVAILABLE, "LISTING_ACTION_UNAVAILABLE");
        verify(listings, never()).reviewListing(any(), any(), any(), any());
    }

    @Test
    @DisplayName("review when supported: decision and trimmed note are passed for this provider's listing only")
    void reviewSupported() {
        when(listings.supportsListingReview()).thenReturn(true);
        when(listings.listingDetail(LISTING)).thenReturn(Optional.of(listing(PROVIDER)));

        service.review("admin-1", PROVIDER, LISTING, "make_visible", "  " + NOTE + " ");
        verify(listings).reviewListing("admin-1", LISTING, ReviewDecision.MAKE_VISIBLE, NOTE);

        when(listings.listingDetail(LISTING)).thenReturn(Optional.of(listing("someone-else")));
        assertApiError(catchThrowable(() -> service.review("admin-1", PROVIDER, LISTING, "KEEP_HIDDEN", NOTE)),
                HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "APPROVE", "hide"})
    @DisplayName("review: decision missing or not KEEP_HIDDEN / MAKE_VISIBLE -> 400 VALIDATION_FAILED")
    void reviewBadDecision(String decision) {
        assertApiError(catchThrowable(() -> service.review("admin-1", PROVIDER, LISTING, decision, NOTE)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "short", "         "})
    @DisplayName("review: note required, 10 to 500 characters -> 400 VALIDATION_FAILED")
    void reviewNoteRequired(String note) {
        assertApiError(catchThrowable(() -> service.review("admin-1", PROVIDER, LISTING, "KEEP_HIDDEN", note)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertApiError(catchThrowable(() -> service.review("admin-1", PROVIDER, LISTING, "KEEP_HIDDEN", "n".repeat(501))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }
}
