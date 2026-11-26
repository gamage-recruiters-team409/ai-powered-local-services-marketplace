package com.gamagerecruiters.marketplace.modules.booking.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;

/**
 * A customer's booking of a provider's listing for one time slot.
 * All timestamps are stored in UTC (FR-SCHD-004).
 *
 * <p>The price snapshot (FR-BOOK-003) is added when the listing module is available.
 */
@Document(collection = "bookings")
public class Booking {

    @Id
    private String id;

    /** Unique, human-readable reference number (FR-BOOK-006). */
    private String ref;

    private String customerId;
    private String providerId;
    private String listingId;

    /** Set only for bookings that belong to a recurring series. */
    private String seriesId;

    private Instant slotStart;
    private Instant slotEnd;
    private int durationMinutes;

    /** Copy of the service address at request time; later address edits do not change the booking. */
    private BookingAddress address;

    /** Customer notes for the provider (optional). */
    private String notes;

    /** C-02: provider must respond before the earlier of 12 h after the request or 1 h before the start. */
    private Instant responseDeadline;

    /** When the provider accepted the request (PENDING_PROVIDER → AWAITING_PAYMENT). */
    private Instant acceptedAt;

    /** Payment must be completed by this time (acceptedAt + payment window). */
    private Instant paymentDueAt;

    /** Price, commission and duration captured from the listing at creation (immutable). */
    private BookingPriceSnapshot priceSnapshot;

    private BookingStatus status;
    private List<BookingStatusChange> timeline = new ArrayList<>();

    /** Reschedule history (FR-BOOK-007); at most one PENDING at a time. */
    private List<RescheduleRequest> rescheduleRequests = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    /** Optimistic locking: stops two parallel status changes from overwriting each other. */
    @Version
    private Long version;

    /**
     * Moves the booking to a new status if the state model allows it, and records it in the timeline.
     *
     * @throws ResourceConflictException with code INVALID_STATUS_TRANSITION when the change is not allowed
     */
    public void changeStatus(BookingStatus target, String changedBy, String reason, Instant at) {
        if (status == null || !status.canTransitionTo(target)) {
            throw new ResourceConflictException("INVALID_STATUS_TRANSITION",
                    "Booking cannot move from " + status + " to " + target);
        }
        timeline.add(new BookingStatusChange(status, target, at, changedBy, reason));
        status = target;
        updatedAt = at;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getListingId() {
        return listingId;
    }

    public void setListingId(String listingId) {
        this.listingId = listingId;
    }

    public String getSeriesId() {
        return seriesId;
    }

    public void setSeriesId(String seriesId) {
        this.seriesId = seriesId;
    }

    public Instant getSlotStart() {
        return slotStart;
    }

    public void setSlotStart(Instant slotStart) {
        this.slotStart = slotStart;
    }

    public Instant getSlotEnd() {
        return slotEnd;
    }

    public void setSlotEnd(Instant slotEnd) {
        this.slotEnd = slotEnd;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public BookingAddress getAddress() {
        return address;
    }

    public void setAddress(BookingAddress address) {
        this.address = address;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getResponseDeadline() {
        return responseDeadline;
    }

    public void setResponseDeadline(Instant responseDeadline) {
        this.responseDeadline = responseDeadline;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public List<BookingStatusChange> getTimeline() {
        return timeline;
    }

    public void setTimeline(List<BookingStatusChange> timeline) {
        this.timeline = timeline;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public BookingPriceSnapshot getPriceSnapshot() {
        return priceSnapshot;
    }

    public void setPriceSnapshot(BookingPriceSnapshot priceSnapshot) {
        this.priceSnapshot = priceSnapshot;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public Instant getPaymentDueAt() {
        return paymentDueAt;
    }

    public void setPaymentDueAt(Instant paymentDueAt) {
        this.paymentDueAt = paymentDueAt;
    }

    public List<RescheduleRequest> getRescheduleRequests() {
        return rescheduleRequests;
    }

    public void setRescheduleRequests(List<RescheduleRequest> rescheduleRequests) {
        this.rescheduleRequests = rescheduleRequests == null ? new ArrayList<>() : rescheduleRequests;
    }

    /** The open reschedule request, if any. */
    public Optional<RescheduleRequest> pendingReschedule() {
        return rescheduleRequests == null ? Optional.empty()
                : rescheduleRequests.stream().filter(RescheduleRequest::isPending).findFirst();
    }

    /** How many times this booking has actually been moved. */
    public long approvedRescheduleCount() {
        return rescheduleRequests == null ? 0
                : rescheduleRequests.stream().filter(r -> r.getStatus() == RescheduleStatus.APPROVED).count();
    }
}
