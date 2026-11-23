package com.gamagerecruiters.marketplace.modules.notification.service;

import java.util.Map;
import org.springframework.stereotype.Component;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;

@Component
public class NotificationTextBuilder {
    public NotificationText build(NotificationType type, Map<String, String> data) {
        String title = switch (type) {
            case BOOKING_REQUEST_RECEIVED -> "New booking request"; case BOOKING_WITHDRAWN -> "Booking withdrawn";
            case BOOKING_AUTO_DECLINED -> "Booking auto-declined"; case BOOKING_ACCEPTED -> "Booking accepted";
            case BOOKING_DECLINED -> "Booking declined"; case BOOKING_EXPIRED -> "Booking expired";
            case BOOKING_CONFIRMED -> "Booking confirmed"; case BOOKING_CANCELLED -> "Booking cancelled";
            case BOOKING_RESPONSE_REMINDER -> "Booking response reminder"; case RESCHEDULE_REQUESTED -> "Reschedule requested";
            case RESCHEDULE_APPROVED -> "Reschedule approved"; case RESCHEDULE_REJECTED -> "Reschedule rejected";
            case RESCHEDULE_EXPIRED -> "Reschedule expired"; case CANCELLATION_PENALTY_APPLIED -> "Cancellation penalty applied";
            case PAYMENT_SUCCESSFUL -> "Payment successful"; case PAYMENT_FAILED -> "Payment failed";
            case REFUND_PROCESSED -> "Refund processed"; case EARNINGS_AVAILABLE -> "Earnings available";
            case PAYOUT_REQUESTED -> "Payout requested"; case PAYOUT_PAID -> "Payout paid";
            case PAYOUT_FAILED -> "Payout failed"; case PAYOUT_ON_HOLD -> "Payout on hold";
            case PROVIDER_PENALTY_RECORDED -> "Provider penalty recorded"; case COMPLETION_CONFIRM_REMINDER -> "Completion confirmation reminder";
            case PROVIDER_COMPLETE_REMINDER -> "Service completion reminder"; case BOOKING_NOT_STARTED_REMINDER -> "Booking not started";
            case REVIEW_INVITATION -> "Share your review"; case VERIFICATION_APPROVED -> "Verification approved";
            case VERIFICATION_REJECTED -> "Verification rejected"; case VERIFICATION_RESUBMISSION_REQUESTED -> "Verification resubmission required";
            case VERIFICATION_EXPIRED -> "Verification expired"; case DISPUTE_OPENED -> "Dispute opened";
            case DISPUTE_OUTCOME -> "Dispute outcome available"; case REVIEW_REMOVED -> "Review removed";
            case REVIEW_RESTORED -> "Review restored"; case REVIEW_PUBLISHED -> "Review published"; case NEW_MESSAGE -> "New message";
            case SERVICE_COMPLETED_AWAITING_CONFIRMATION -> "Completion confirmation needed"; case COMPLETION_CONFIRMED -> "Completion confirmed";
            case ISSUE_RAISED -> "Issue raised"; case NO_SHOW_REPORTED -> "No-show reported";
            case REVIEW_RECEIVED -> "Review received"; case REVIEW_REPLY_POSTED -> "Review reply posted";
        };
        String message = switch (type) {
            case BOOKING_REQUEST_RECEIVED -> "You received a new booking request."; case BOOKING_WITHDRAWN -> "A booking request was withdrawn.";
            case BOOKING_AUTO_DECLINED -> "A booking request was automatically declined."; case BOOKING_ACCEPTED -> "Your booking request was accepted.";
            case BOOKING_DECLINED -> "Your booking request was declined."; case BOOKING_EXPIRED -> "Your booking request expired.";
            case BOOKING_CONFIRMED -> "Your booking is confirmed."; case BOOKING_CANCELLED -> "Your booking was cancelled.";
            case BOOKING_RESPONSE_REMINDER -> "Please respond to your pending booking request."; case RESCHEDULE_REQUESTED -> "A reschedule was requested.";
            case RESCHEDULE_APPROVED -> "Your reschedule was approved."; case RESCHEDULE_REJECTED -> "Your reschedule was rejected.";
            case RESCHEDULE_EXPIRED -> "Your reschedule request expired."; case CANCELLATION_PENALTY_APPLIED -> "A cancellation penalty was applied.";
            case PAYMENT_SUCCESSFUL -> "Your payment was successful."; case PAYMENT_FAILED -> "Your payment failed.";
            case REFUND_PROCESSED -> "Your refund was processed."; case EARNINGS_AVAILABLE -> "Your earnings are available.";
            case PAYOUT_REQUESTED -> "Your payout was requested."; case PAYOUT_PAID -> "Your payout was paid.";
            case PAYOUT_FAILED -> "Your payout failed."; case PAYOUT_ON_HOLD -> "Your payout is on hold.";
            case PROVIDER_PENALTY_RECORDED -> "A provider penalty was recorded."; case COMPLETION_CONFIRM_REMINDER -> "Please confirm service completion.";
            case PROVIDER_COMPLETE_REMINDER -> "Please mark the service as complete."; case BOOKING_NOT_STARTED_REMINDER -> "Your booking has not started.";
            case REVIEW_INVITATION -> "Please share your experience in a review."; case VERIFICATION_APPROVED -> "Your verification was approved.";
            case VERIFICATION_REJECTED -> "Your verification was rejected."; case VERIFICATION_RESUBMISSION_REQUESTED -> "Please resubmit your verification.";
            case VERIFICATION_EXPIRED -> "Your verification expired."; case DISPUTE_OPENED -> "A dispute was opened.";
            case DISPUTE_OUTCOME -> "A dispute outcome is available."; case REVIEW_REMOVED -> "Your review was removed.";
            case REVIEW_RESTORED -> "Your review was restored."; case REVIEW_PUBLISHED -> "Your review was published."; case NEW_MESSAGE -> "You received a new message.";
            case SERVICE_COMPLETED_AWAITING_CONFIRMATION -> "Please confirm that the service was completed."; case COMPLETION_CONFIRMED -> "Service completion was confirmed.";
            case ISSUE_RAISED -> "An issue was raised for your booking."; case NO_SHOW_REPORTED -> "A no-show was reported for your booking.";
            case REVIEW_RECEIVED -> "You received a new review."; case REVIEW_REPLY_POSTED -> "A reply to your review was posted.";
        };
        return new NotificationText(title, message + details(data));
    }
    private String details(Map<String, String> data) {
        if (data == null) return "";
        StringBuilder result = new StringBuilder();
        append(result, "district", data.get("district")); append(result, "slotStart", data.get("slotStart"));
        append(result, "reference", data.get("reference")); append(result, "reason", data.get("reason"));
        return result.toString();
    }
    private void append(StringBuilder result, String name, String value) { if (value != null && !value.isBlank()) result.append(' ').append(name).append(": ").append(value).append('.'); }
}
