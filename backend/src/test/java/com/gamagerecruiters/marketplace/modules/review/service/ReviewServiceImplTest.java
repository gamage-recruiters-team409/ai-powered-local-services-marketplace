package com.gamagerecruiters.marketplace.modules.review.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionLookup;
import com.gamagerecruiters.marketplace.modules.review.domain.ProviderReply;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.CreateReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.ProviderReplyRequest;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.UpdateReviewRequest;
import com.gamagerecruiters.marketplace.modules.review.repository.ReviewRepository;

@ExtendWith(MockitoExtension.class)
class ReviewServiceImplTest {

    private static final String BOOKING = "booking-1";
    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private BookingFacade bookingFacade;

    @Mock
    private CompletionLookup completionLookup;

    @Mock
    private ReviewRatingQuery ratingQuery;

    @Mock
    private ReviewAtomicUpdates reviewUpdates;

    private ReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReviewServiceImpl(reviewRepository, bookingFacade, completionLookup, ratingQuery,
                reviewUpdates, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Booking data as returned by BookingFacade. Built as a mock instead of calling the record
     * constructor, so these tests compile both before and after WP05 PR #16 adds slotStart/slotEnd.
     * Use it with doReturn(...).when(...): it stubs its own mock, which must finish before the
     * BookingFacade stub starts.
     */
    private static BookingParticipants booking(BookingStatus status) {
        BookingParticipants booking = mock(BookingParticipants.class);
        lenient().when(booking.bookingId()).thenReturn(BOOKING);
        lenient().when(booking.listingId()).thenReturn("listing-1");
        lenient().when(booking.customerId()).thenReturn(CUSTOMER);
        lenient().when(booking.providerId()).thenReturn(PROVIDER);
        lenient().when(booking.status()).thenReturn(status);
        return booking;
    }

    /** Stands in for the atomic MongoDB edit: returns the review as it would be stored after the edit. */
    private static Optional<Review> editedReview(Instant createdAt, int rating, String comment) {
        Review edited = review(createdAt);
        edited.setRating(rating);
        edited.setComment(comment);
        edited.setEdited(true);
        edited.setEditedAt(NOW);
        edited.setUpdatedAt(NOW);
        return Optional.of(edited);
    }

    private static Review review(Instant createdAt) {
        Review review = new Review();
        review.setId("rev-1");
        review.setBookingId(BOOKING);
        review.setListingId("listing-1");
        review.setProviderId(PROVIDER);
        review.setCustomerId(CUSTOMER);
        review.setRating(4);
        review.setComment("Good");
        review.setStatus(ReviewStatus.PUBLISHED);
        review.setCreatedAt(createdAt);
        review.setUpdatedAt(createdAt);
        return review;
    }

    private static void assertConflict(ApiException ex, String code) {
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(code, ex.getErrorCode());
    }

    @Nested
    @DisplayName("createReview")
    class CreateReview {

        @Test
        @DisplayName("Customer reviews a COMPLETED booking within 7 days: PUBLISHED, ids copied from the booking")
        void createsPublishedReview() {
            doReturn(booking(BookingStatus.COMPLETED)).when(bookingFacade).getBookingParticipants(BOOKING);
            when(completionLookup.findConfirmedAt(BOOKING)).thenReturn(Optional.of(NOW.minus(Duration.ofDays(2))));
            when(reviewRepository.existsByBookingId(BOOKING)).thenReturn(false);
            when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

            ReviewResponse response = service.createReview(CUSTOMER, new CreateReviewRequest(BOOKING, 5, "  Great job  "));

            assertEquals(ReviewStatus.PUBLISHED, response.status());
            assertEquals(5, response.rating());
            assertEquals("Great job", response.comment());
            assertEquals(PROVIDER, response.providerId());
            assertEquals("listing-1", response.listingId());
            assertFalse(response.edited());
        }

        @Test
        @DisplayName("Another customer gets 403 ACCESS_DENIED")
        void rejectsOtherCustomer() {
            doReturn(booking(BookingStatus.COMPLETED)).when(bookingFacade).getBookingParticipants(BOOKING);

            assertThrows(AccessDeniedException.class,
                    () -> service.createReview("cust-2", new CreateReviewRequest(BOOKING, 5, null)));
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("Booking not COMPLETED gets 409 BOOKING_NOT_COMPLETED")
        void rejectsNotCompleted() {
            doReturn(booking(BookingStatus.COMPLETION_PENDING)).when(bookingFacade).getBookingParticipants(BOOKING);

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.createReview(CUSTOMER, new CreateReviewRequest(BOOKING, 5, null)));

            assertConflict(ex, "BOOKING_NOT_COMPLETED");
        }

        @Test
        @DisplayName("More than 7 days after completion gets 409 REVIEW_WINDOW_CLOSED")
        void rejectsAfterWindow() {
            doReturn(booking(BookingStatus.COMPLETED)).when(bookingFacade).getBookingParticipants(BOOKING);
            when(completionLookup.findConfirmedAt(BOOKING))
                    .thenReturn(Optional.of(NOW.minus(Duration.ofDays(7)).minusSeconds(1)));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.createReview(CUSTOMER, new CreateReviewRequest(BOOKING, 5, null)));

            assertConflict(ex, "REVIEW_WINDOW_CLOSED");
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("Missing completion record or timestamp gets 409 REVIEW_WINDOW_UNAVAILABLE (no unlimited review)")
        void rejectsWhenCompletionTimeMissing() {
            doReturn(booking(BookingStatus.COMPLETED)).when(bookingFacade).getBookingParticipants(BOOKING);
            when(completionLookup.findConfirmedAt(BOOKING)).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.createReview(CUSTOMER, new CreateReviewRequest(BOOKING, 5, null)));

            assertConflict(ex, "REVIEW_WINDOW_UNAVAILABLE");
            verify(reviewRepository, never()).existsByBookingId(any());
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("Exactly 7 days after completion is still allowed")
        void allowsOnLastMoment() {
            doReturn(booking(BookingStatus.COMPLETED)).when(bookingFacade).getBookingParticipants(BOOKING);
            when(completionLookup.findConfirmedAt(BOOKING)).thenReturn(Optional.of(NOW.minus(Duration.ofDays(7))));
            when(reviewRepository.existsByBookingId(BOOKING)).thenReturn(false);
            when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

            assertEquals(4, service.createReview(CUSTOMER, new CreateReviewRequest(BOOKING, 4, null)).rating());
        }

        @Test
        @DisplayName("Second review for the same booking gets 409 REVIEW_ALREADY_EXISTS")
        void rejectsDuplicate() {
            doReturn(booking(BookingStatus.COMPLETED)).when(bookingFacade).getBookingParticipants(BOOKING);
            when(completionLookup.findConfirmedAt(BOOKING)).thenReturn(Optional.of(NOW));
            when(reviewRepository.existsByBookingId(BOOKING)).thenReturn(true);

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.createReview(CUSTOMER, new CreateReviewRequest(BOOKING, 5, null)));

            assertConflict(ex, "REVIEW_ALREADY_EXISTS");
        }

        @Test
        @DisplayName("Concurrent duplicate (unique index) gets 409 REVIEW_ALREADY_EXISTS")
        void concurrentDuplicateHitsUniqueIndex() {
            doReturn(booking(BookingStatus.COMPLETED)).when(bookingFacade).getBookingParticipants(BOOKING);
            when(completionLookup.findConfirmedAt(BOOKING)).thenReturn(Optional.of(NOW));
            when(reviewRepository.existsByBookingId(BOOKING)).thenReturn(false);
            when(reviewRepository.save(any(Review.class))).thenThrow(new DuplicateKeyException("dup"));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.createReview(CUSTOMER, new CreateReviewRequest(BOOKING, 5, null)));

            assertConflict(ex, "REVIEW_ALREADY_EXISTS");
        }
    }

    @Nested
    @DisplayName("updateReview")
    class UpdateReview {

        @Test
        @DisplayName("Owner edits within 48 hours: atomic update with the 48-hour limit, marked edited")
        void editsWithinWindow() {
            Instant createdAt = NOW.minus(Duration.ofHours(47));
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review(createdAt)));
            when(reviewUpdates.applyCustomerEdit("rev-1", CUSTOMER, 3, "Okay", NOW.minus(Duration.ofHours(48)), NOW))
                    .thenReturn(editedReview(createdAt, 3, "Okay"));

            ReviewResponse response = service.updateReview(CUSTOMER, "rev-1", new UpdateReviewRequest(3, "  Okay "));

            assertEquals(3, response.rating());
            assertEquals("Okay", response.comment());
            assertTrue(response.edited());
            assertEquals(NOW, response.editedAt());
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("Exactly 48 hours after posting is still allowed")
        void allowsOnLastMoment() {
            Instant createdAt = NOW.minus(Duration.ofHours(48));
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review(createdAt)));
            when(reviewUpdates.applyCustomerEdit("rev-1", CUSTOMER, 2, null, createdAt, NOW))
                    .thenReturn(editedReview(createdAt, 2, null));

            assertEquals(2, service.updateReview(CUSTOMER, "rev-1", new UpdateReviewRequest(2, null)).rating());
        }

        @Test
        @DisplayName("One second after 48 hours gets 409 REVIEW_EDIT_WINDOW_CLOSED")
        void rejectsAfterWindow() {
            when(reviewRepository.findById("rev-1"))
                    .thenReturn(Optional.of(review(NOW.minus(Duration.ofHours(48)).minusSeconds(1))));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.updateReview(CUSTOMER, "rev-1", new UpdateReviewRequest(3, null)));

            assertConflict(ex, "REVIEW_EDIT_WINDOW_CLOSED");
            verify(reviewUpdates, never()).applyCustomerEdit(any(), any(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("Missing createdAt gets 409 REVIEW_EDIT_WINDOW_UNAVAILABLE (no unlimited editing)")
        void rejectsWhenCreatedAtMissing() {
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review(null)));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.updateReview(CUSTOMER, "rev-1", new UpdateReviewRequest(3, null)));

            assertConflict(ex, "REVIEW_EDIT_WINDOW_UNAVAILABLE");
            verify(reviewUpdates, never()).applyCustomerEdit(any(), any(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("Provider replied at the same moment (atomic update matched nothing) gets 409 REVIEW_NOT_EDITABLE")
        void raceLostToProviderReply() {
            Review beforeReply = review(NOW.minus(Duration.ofHours(1)));
            Review afterReply = review(NOW.minus(Duration.ofHours(1)));
            afterReply.setProviderReply(new ProviderReply());
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(beforeReply), Optional.of(afterReply));
            when(reviewUpdates.applyCustomerEdit(eq("rev-1"), eq(CUSTOMER), eq(3), any(), any(), any()))
                    .thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.updateReview(CUSTOMER, "rev-1", new UpdateReviewRequest(3, null)));

            assertConflict(ex, "REVIEW_NOT_EDITABLE");
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("Other change at the same moment gets 409 REVIEW_UPDATE_CONFLICT, nothing overwritten")
        void raceLostToOtherChange() {
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review(NOW.minus(Duration.ofHours(1)))));
            when(reviewUpdates.applyCustomerEdit(eq("rev-1"), eq(CUSTOMER), eq(3), any(), any(), any()))
                    .thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.updateReview(CUSTOMER, "rev-1", new UpdateReviewRequest(3, null)));

            assertConflict(ex, "REVIEW_UPDATE_CONFLICT");
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("After the provider replied gets 409 REVIEW_NOT_EDITABLE")
        void rejectsAfterReply() {
            Review review = review(NOW.minus(Duration.ofHours(1)));
            review.setProviderReply(new ProviderReply());
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.updateReview(CUSTOMER, "rev-1", new UpdateReviewRequest(3, null)));

            assertConflict(ex, "REVIEW_NOT_EDITABLE");
        }

        @Test
        @DisplayName("Removed review gets 409 REVIEW_NOT_EDITABLE")
        void rejectsRemoved() {
            Review review = review(NOW.minus(Duration.ofHours(1)));
            review.setStatus(ReviewStatus.REMOVED);
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.updateReview(CUSTOMER, "rev-1", new UpdateReviewRequest(3, null)));

            assertConflict(ex, "REVIEW_NOT_EDITABLE");
        }

        @Test
        @DisplayName("Someone else's review gets 403 ACCESS_DENIED")
        void rejectsOtherCustomer() {
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review(NOW)));

            assertThrows(AccessDeniedException.class,
                    () -> service.updateReview("cust-2", "rev-1", new UpdateReviewRequest(3, null)));
        }

        @Test
        @DisplayName("Unknown review gets 404 REVIEW_NOT_FOUND")
        void unknownReview() {
            when(reviewRepository.findById("missing")).thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.updateReview(CUSTOMER, "missing", new UpdateReviewRequest(3, null)));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
            assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("replyToReview")
    class ReplyToReview {

        @Test
        @DisplayName("Provider adds a reply, then edits it (still one reply), written by the atomic update only")
        void addsAndEditsReply() {
            Review review = review(NOW.minus(Duration.ofDays(1)));
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review));
            when(reviewUpdates.applyProviderReply(eq("rev-1"), eq(PROVIDER), any(ProviderReply.class), eq(NOW)))
                    .thenAnswer(inv -> {
                        Review stored = review(NOW.minus(Duration.ofDays(1)));
                        stored.setProviderReply(inv.getArgument(2));
                        return Optional.of(stored);
                    });

            ReviewResponse first = service.replyToReview(PROVIDER, "rev-1", new ProviderReplyRequest(" Thank you! "));
            ReviewResponse edited = service.replyToReview(PROVIDER, "rev-1", new ProviderReplyRequest("Thanks again"));

            assertEquals("Thank you!", first.providerReply().text());
            assertEquals("Thanks again", edited.providerReply().text());
            assertEquals(NOW, edited.providerReply().repliedAt());
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("Review changed at the same moment (atomic update matched nothing) gets 409 REVIEW_UPDATE_CONFLICT")
        void raceLost() {
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review(NOW)));
            when(reviewUpdates.applyProviderReply(eq("rev-1"), eq(PROVIDER), any(ProviderReply.class), eq(NOW)))
                    .thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.replyToReview(PROVIDER, "rev-1", new ProviderReplyRequest("Hi")));

            assertConflict(ex, "REVIEW_UPDATE_CONFLICT");
        }

        @Test
        @DisplayName("Another provider gets 403 ACCESS_DENIED")
        void rejectsOtherProvider() {
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review(NOW)));

            assertThrows(AccessDeniedException.class,
                    () -> service.replyToReview("prov-2", "rev-1", new ProviderReplyRequest("Hi")));
        }

        @Test
        @DisplayName("Reply to a non-published review gets 409 REVIEW_NOT_PUBLISHED")
        void rejectsNotPublished() {
            Review review = review(NOW);
            review.setStatus(ReviewStatus.REMOVED);
            when(reviewRepository.findById("rev-1")).thenReturn(Optional.of(review));

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.replyToReview(PROVIDER, "rev-1", new ProviderReplyRequest("Hi")));

            assertConflict(ex, "REVIEW_NOT_PUBLISHED");
        }
    }

    @Nested
    @DisplayName("lists and rating summary")
    class ListsAndSummary {

        @Test
        @DisplayName("Public list asks for PUBLISHED reviews, newest first, page size capped at 50")
        void publicListUsesPublishedNewestFirst() {
            Page<Review> page = new PageImpl<>(List.of(review(NOW)));
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(reviewRepository.findByProviderIdAndStatus(eq(PROVIDER), eq(ReviewStatus.PUBLISHED), pageable.capture()))
                    .thenReturn(page);

            ReviewPageResponse response = service.getPublishedReviews(PROVIDER, 0, 500);

            assertEquals(1, response.content().size());
            assertEquals(50, pageable.getValue().getPageSize());
            assertEquals(Sort.Direction.DESC, pageable.getValue().getSort().getOrderFor("createdAt").getDirection());
        }

        @Test
        @DisplayName("Rating summary: average rounded to 1 decimal, counts for every star value")
        void summaryFromCounts() {
            when(ratingQuery.countPublishedByRating(PROVIDER)).thenReturn(Map.of(5, 3L, 4, 1L, 2, 1L));

            RatingSummaryResponse summary = service.getRatingSummary(PROVIDER);

            assertEquals(5, summary.reviewCount());
            assertEquals(4.2, summary.averageRating()); // (15 + 4 + 2) / 5
            assertEquals(0L, summary.ratingCounts().get(1));
            assertEquals(3L, summary.ratingCounts().get(5));
        }

        @Test
        @DisplayName("Rating summary with no reviews is 0.0 / 0")
        void emptySummary() {
            when(ratingQuery.countPublishedByRating(PROVIDER)).thenReturn(Map.of());

            RatingSummaryResponse summary = service.getRatingSummary(PROVIDER);

            assertEquals(0, summary.reviewCount());
            assertEquals(0.0, summary.averageRating());
            assertNull(summary.ratingCounts().get(6));
        }
    }
}
