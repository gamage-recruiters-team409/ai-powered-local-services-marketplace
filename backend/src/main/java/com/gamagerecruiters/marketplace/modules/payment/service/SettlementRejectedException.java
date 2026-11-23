
package com.gamagerecruiters.marketplace.modules.payment.service;

/**
 * Indicates a permanent rejection of a dispute financial settlement request.
 *
 * <p>
 * This exception is intended for deterministic validation failures,
 * such as unsupported settlement actions, invalid idempotency keys,
 * or mismatched booking and financial ownership details.
 * </p>
 *
 * <p>
 * Callers should not automatically retry the same unchanged request.
 * Transient infrastructure failures must not be wrapped in this exception,
 * so that the dispute settlement workflow can retry them separately.
 * </p>
 */
public class SettlementRejectedException extends RuntimeException {

    /**
     * Creates a permanent settlement rejection with a descriptive reason.
     *
     * @param message explanation of why the settlement cannot be applied
     */
    public SettlementRejectedException(String message) {
        super(message);
    }
}
