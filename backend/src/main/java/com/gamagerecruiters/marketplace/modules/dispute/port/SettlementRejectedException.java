package com.gamagerecruiters.marketplace.modules.dispute.port;

/**
 * Thrown by a {@link DisputeSettlementPort} adapter when the settlement is PERMANENTLY rejected and a retry cannot fix
 * it (for example the booking has no captured payment). The dispute's settlement becomes REJECTED with the manual
 * review reason {@code SETTLEMENT_REJECTED:<reasonCode>}; it is never retried and the booking is not changed.
 *
 * <p>{@code reasonCode} is a short machine code (letters, digits, '_' or '-'); it is stored and shown to admins, so it
 * must not contain personal data or amounts. The message is never stored or logged.</p>
 */
public class SettlementRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reasonCode;

    public SettlementRejectedException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
