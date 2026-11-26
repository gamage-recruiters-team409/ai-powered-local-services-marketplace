package com.gamagerecruiters.marketplace.modules.payment.gateway;

/**
 * Indicates that a checkout request was definitively not submitted
 * to the external payment gateway.
 *
 * <p>This exception permits the caller to release a newly acquired
 * checkout reservation because this invocation did not create a
 * gateway session.</p>
 *
 * <p>It must never be used for network timeouts, uncertain gateway
 * responses, interrupted requests or session persistence failures.
 * It does not establish the outcome of an earlier gateway attempt.</p>
 */
public final class CheckoutNotSubmittedException
        extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public CheckoutNotSubmittedException(String message) {
        super(message);
    }

    public CheckoutNotSubmittedException(
            String message, Throwable cause) {
        super(message, cause);
    }
}
