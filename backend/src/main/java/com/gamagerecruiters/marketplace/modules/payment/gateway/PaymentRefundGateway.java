package com.gamagerecruiters.marketplace.modules.payment.gateway;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Gateway-independent contract for executing payment refunds.
 *
 * <p>
 * Refund requests must originate from a trusted, persisted refund
 * operation after authorization, refundable balance validation,
 * and financial reservation have been completed.
 * </p>
 *
 * <p>
 * This gateway does not modify payment records, booking states,
 * commission amounts, provider earnings, or payout balances.
 * Financial state changes are the responsibility of the refund
 * orchestration and settlement services.
 * </p>
 */
public interface PaymentRefundGateway {

    /**
     * Creates or retrieves a refund using a stable idempotency key.
     *
     * <p>
     * The same logical refund operation must reuse the same
     * idempotency key and identical request parameters.
     * Retries must respect the gateway's idempotency retention
     * period and reconciliation requirements.
     * </p>
     *
     * <p>
     * An exception or uncertain response does not prove that
     * the refund failed. Callers must reconcile uncertain outcomes
     * before attempting another financial operation.
     * </p>
     *
     * @param refundOperationId       persisted internal refund operation ID
     * @param gatewayPaymentReference verified gateway payment reference
     * @param amount                  trusted refund amount in major currency units
     * @param currency                ISO 4217 payment currency
     * @return gateway refund reference and reported refund details
     */
    RefundResult createRefund(
            String refundOperationId,
            String gatewayPaymentReference,
            BigDecimal amount,
            String currency);

    /**
     * Locates an existing gateway refund without creating a new refund.
     *
     * <p>
     * A known gateway refund reference should be retrieved directly.
     * When the reference is unavailable, the implementation must
     * identify the refund using the verified payment reference and
     * persisted refund operation metadata.
     * </p>
     *
     * <p>
     * An empty result does not establish that the refund failed.
     * Financial reservations must remain active until a verified
     * terminal gateway outcome is available.
     * </p>
     *
     * @param refundOperationId persisted internal refund operation ID
     * @param gatewayPaymentReference verified gateway payment reference
     * @param gatewayRefundReference known refund reference, or null
     * @param amount expected refund amount in major currency units
     * @param currency expected ISO 4217 currency
     * @return matching verified refund, if located
     */
    default Optional<RefundResult> findRefund(
            String refundOperationId,
            String gatewayPaymentReference,
            String gatewayRefundReference,
            BigDecimal amount,
            String currency) {

        throw new UnsupportedOperationException(
                "Refund reconciliation is not supported by this gateway");
    }


    /**
     * Gateway-independent representation of a refund status.
     *
     * <p>
     * Only a verified SUCCEEDED result may be considered
     * successful for subsequent financial settlement.
     * </p>
     */
    enum RefundStatus {

        /** The gateway reports that the refund succeeded. */
        SUCCEEDED,

        /** The refund has been accepted but is not yet complete. */
        PENDING,

        /** The gateway explicitly reports a failed refund. */
        FAILED,

        /** The gateway reports that the refund was canceled. */
        CANCELED,

        /** Additional action is required to complete the refund. */
        REQUIRES_ACTION,

        /** The reported status cannot be safely determined. */
        UNKNOWN
    }

    /**
     * Result returned by a payment refund gateway.
     *
     * <p>
     * The returned reference, amount, currency, and status
     * must be validated before applying local financial changes.
     * </p>
     *
     * @param gatewayRefundReference  unique gateway refund identifier
     * @param gatewayPaymentReference associated gateway payment reference
     * @param amount                  refund amount in major currency units
     * @param currency                normalized ISO 4217 currency
     * @param status                  gateway-reported refund status
     */
    record RefundResult(
            String gatewayRefundReference,
            String gatewayPaymentReference,
            BigDecimal amount,
            String currency,
            RefundStatus status) {
    }
}