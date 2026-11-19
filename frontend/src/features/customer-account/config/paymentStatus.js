/**
 * Status mapping configuration for Customer Payments (WP01).
 *
 * Requirements:
 * INITIATED -> "Pending"
 * AUTHORIZED -> "Authorized"
 * CAPTURED and RELEASED -> "Paid"
 * PARTIALLY_REFUNDED -> "Partially refunded"
 * REFUNDED -> "Refunded"
 * FAILED -> "Failed"
 * REFUND_PENDING -> "Paid"
 */

export const PAYMENT_STATUS_CONFIG = {
  INITIATED: {
    label: 'Pending',
    variant: 'warning',
  },
  AUTHORIZED: {
    label: 'Authorized',
    variant: 'info',
  },
  CAPTURED: {
    label: 'Paid',
    variant: 'success',
  },
  RELEASED: {
    label: 'Paid',
    variant: 'success',
  },
  REFUND_PENDING: {
    label: 'Paid',
    variant: 'success',
  },
  PARTIALLY_REFUNDED: {
    label: 'Partially refunded',
    variant: 'info',
  },
  REFUNDED: {
    label: 'Refunded',
    variant: 'info',
  },
  FAILED: {
    label: 'Failed',
    variant: 'danger',
  },
};

export function getPaymentStatusInfo(status) {
  return PAYMENT_STATUS_CONFIG[status] || {
    label: status || 'Unknown',
    variant: 'muted',
  };
}
