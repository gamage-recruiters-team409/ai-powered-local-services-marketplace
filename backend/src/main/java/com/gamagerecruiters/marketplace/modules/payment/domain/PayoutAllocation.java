package com.gamagerecruiters.marketplace.modules.payment.domain;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Records the portion of a provider payout allocated to a real
 * payment and booking.
 *
 * <p>
 * Allocation records preserve financial traceability. They do not
 * independently reserve or transfer money. The payout transaction
 * service must persist allocations, ledger events, balance changes,
 * and payout status atomically.
 * </p>
 */
@Document(collection = "payout_allocations")
@CompoundIndexes({
        @CompoundIndex(
                name = "uq_payout_payment_booking",
                def = "{'payoutId': 1, 'paymentId': 1, 'bookingId': 1}",
                unique = true)
})
public class PayoutAllocation {

    @Id
    private String id;

    private String payoutId;
    private String providerId;
    private String paymentId;
    private String bookingId;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;

    private String currency;

    protected PayoutAllocation() {
        // Required by Spring Data MongoDB.
    }

    /**
     * Creates an allocation linked to an existing payout and
     * its originating payment and booking.
     *
     * @param payoutId   payout request identifier
     * @param providerId provider receiving the payout
     * @param paymentId  originating payment identifier
     * @param bookingId  originating booking identifier
     * @param amount     allocated earnings amount
     * @param currency   earnings currency
     */
    public PayoutAllocation(
            String payoutId,
            String providerId,
            String paymentId,
            String bookingId,
            BigDecimal amount,
            String currency) {

        this.payoutId = requireText(payoutId, "Payout ID");
        this.providerId = requireText(providerId, "Provider ID");
        this.paymentId = requireText(paymentId, "Payment ID");
        this.bookingId = requireText(bookingId, "Booking ID");
        this.currency = requireText(currency, "Currency");

        Objects.requireNonNull(amount, "Allocation amount is required");

        if (amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "Allocation amount must be positive with at most two decimal places");
        }

        this.amount = amount;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank");
        }

        return value.trim();
    }

    public String getId() {
        return id;
    }

    public String getPayoutId() {
        return payoutId;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getBookingId() {
        return bookingId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }
}
