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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.Section;
import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort.ListingDetail;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort.ListingRow;
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
    @DisplayName("detail: this provider's listing -> available with its data; moderatedAt stays null when not recorded")
    void detail() {
        when(listings.listingDetail(LISTING)).thenReturn(Optional.of(listing(PROVIDER)));

        Section<ListingDetail> result = service.detail(PROVIDER, LISTING);

        assertThat(result.available()).isTrue();
        assertThat(result.data().title()).isEqualTo("Commercial Deep Cleaning");
        assertThat(result.data().moderatedAt()).isNull();
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

    // ---------- listings page (read-only) ----------

    @Test
    @DisplayName("page: the provider's listings of every status through the listing port, paginated")
    void page() {
        ListingRow row = new ListingRow(LISTING, "Commercial Deep Cleaning", "Cleaning", new BigDecimal("12000"),
                "FIXED", "PAUSED", List.of(), Instant.parse("2026-10-24T00:00:00Z"));
        when(listings.listings(PROVIDER, PageRequest.of(1, 5))).thenReturn(new PageImpl<>(List.of(row),
                PageRequest.of(1, 5), 6));

        Section<AdminPageResponse<ListingRow>> result = service.page(PROVIDER, 1, 5);

        assertThat(result.available()).isTrue();
        assertThat(result.data().content()).containsExactly(row);
        assertThat(result.data().totalElements()).isEqualTo(6);
        assertThat(result.data().totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("page: listing source fails -> SOURCE_ERROR (no data); bad paging -> 400; customer -> 404")
    void pageErrors() {
        when(listings.listings(any(), any())).thenThrow(new IllegalStateException("down"));
        assertThat(service.page(PROVIDER, 0, 5).reason()).isEqualTo(Section.SOURCE_ERROR);

        assertApiError(catchThrowable(() -> service.page(PROVIDER, -1, 5)), HttpStatus.BAD_REQUEST,
                "INVALID_PAGE_REQUEST");
        assertApiError(catchThrowable(() -> service.page(PROVIDER, 0, 21)), HttpStatus.BAD_REQUEST,
                "INVALID_PAGE_REQUEST");
        account("cust", Role.CUSTOMER);
        assertApiError(catchThrowable(() -> service.page("cust", 0, 5)), HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND");
    }

    @Test
    @DisplayName("read-only: viewing listings never writes to the listing module (no visibility or moderation call)")
    void readOnly() {
        when(listings.listingDetail(LISTING)).thenReturn(Optional.of(listing(PROVIDER)));
        when(listings.listings(any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.detail(PROVIDER, LISTING);
        service.page(PROVIDER, 0, 5);

        verify(listings, never()).updateSuspensionVisibility(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
