package com.gamagerecruiters.marketplace.modules.listing.domain;

import static com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus.ACTIVE;
import static com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus.ARCHIVED;
import static com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus.DRAFT;
import static com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus.FLAGGED;
import static com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus.PAUSED;
import static com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ListingStatusTest {

    @Test
    @DisplayName("Has exactly the 6 approved listing statuses")
    void hasApprovedStatuses() {
        assertThat(ListingStatus.values()).containsExactlyInAnyOrder(
                DRAFT, ACTIVE, PAUSED, FLAGGED, ARCHIVED, REJECTED);
    }

    @Test
    @DisplayName("Favourite-readable and category-blocking status sets match FR-CUST-005 and FR-LIST-001")
    void contractSetsMatch() {
        assertThat(ListingStatus.FAVOURITE_READABLE_STATUSES)
                .containsExactlyInAnyOrder(ACTIVE, PAUSED, ARCHIVED);
        assertThat(ListingStatus.CATEGORY_BLOCKING_STATUSES)
                .containsExactlyInAnyOrder(ACTIVE, PAUSED, FLAGGED);
    }

    @ParameterizedTest(name = "Provider transition {0} -> {1} is allowed")
    @CsvSource({
            "DRAFT, ACTIVE",
            "DRAFT, ARCHIVED",
            "ACTIVE, PAUSED",
            "ACTIVE, ARCHIVED",
            "PAUSED, ACTIVE",
            "PAUSED, ARCHIVED",
            "FLAGGED, ARCHIVED",
            "REJECTED, ARCHIVED"
    })
    void allowedProviderTransitions(ListingStatus from, ListingStatus to) {
        assertThat(from.canProviderTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "Provider transition {0} -> {1} is rejected")
    @CsvSource({
            "DRAFT, PAUSED",
            "DRAFT, FLAGGED",
            "DRAFT, REJECTED",
            "ACTIVE, DRAFT",
            "ACTIVE, FLAGGED",
            "PAUSED, DRAFT",
            "FLAGGED, ACTIVE",
            "FLAGGED, PAUSED",
            "FLAGGED, REJECTED",
            "REJECTED, ACTIVE",
            "REJECTED, PAUSED",
            "REJECTED, DRAFT",
            "ARCHIVED, ACTIVE",
            "ARCHIVED, PAUSED",
            "ARCHIVED, DRAFT"
    })
    void disallowedProviderTransitions(ListingStatus from, ListingStatus to) {
        assertThat(from.canProviderTransitionTo(to)).isFalse();
    }

    @ParameterizedTest(name = "Moderator transition {0} -> {1} is allowed")
    @CsvSource({
            "FLAGGED, ACTIVE",
            "FLAGGED, REJECTED"
    })
    void allowedModeratorTransitions(ListingStatus from, ListingStatus to) {
        assertThat(from.canModeratorTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "Moderator transition from non-FLAGGED or unsupported {0} -> {1} is rejected")
    @CsvSource({
            "DRAFT, ACTIVE",
            "ACTIVE, REJECTED",
            "PAUSED, ACTIVE",
            "ARCHIVED, ACTIVE",
            "REJECTED, ACTIVE",
            "FLAGGED, PAUSED",
            "FLAGGED, DRAFT",
            "FLAGGED, ARCHIVED"
    })
    void disallowedModeratorTransitions(ListingStatus from, ListingStatus to) {
        assertThat(from.canModeratorTransitionTo(to)).isFalse();
    }

    @Test
    @DisplayName("No status can transition to itself or null")
    void noSelfOrNullTransitions() {
        for (ListingStatus status : ListingStatus.values()) {
            assertThat(status.canProviderTransitionTo(status)).isFalse();
            assertThat(status.canProviderTransitionTo(null)).isFalse();
            assertThat(status.canModeratorTransitionTo(status)).isFalse();
            assertThat(status.canModeratorTransitionTo(null)).isFalse();
        }
    }

    @Test
    @DisplayName("ARCHIVED is final and non-editable by providers")
    void archivedIsFinalAndNonEditable() {
        assertThat(ARCHIVED.isFinal()).isTrue();
        assertThat(ARCHIVED.isProviderEditable()).isFalse();
        assertThat(ARCHIVED.allowedProviderTransitions()).isEmpty();
        assertThat(ARCHIVED.isBookable()).isFalse();
        assertThat(ARCHIVED.isFavouriteReadable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ListingStatus.class, names = {"DRAFT", "ACTIVE", "PAUSED", "FLAGGED", "REJECTED"})
    @DisplayName("Non-archived statuses are editable and non-final")
    void nonArchivedStatusesAreEditable(ListingStatus status) {
        assertThat(status.isFinal()).isFalse();
        assertThat(status.isProviderEditable()).isTrue();
        assertThat(status.isBookable()).isEqualTo(status == ACTIVE);
    }
}
