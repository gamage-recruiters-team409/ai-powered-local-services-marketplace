/**
 * Refund status mapping configuration for Customer Payments (WP01).
 *
 * Requirements:
 * UNDER_REVIEW -> "Under review", variant warning
 * PROCESSING -> "Processing", variant info
 * COMPLETED -> "Completed", variant success
 * FAILED -> "Failed", variant danger
 * Unknown status -> neutral badge (variant 'muted') with humanised raw value
 */

export const REFUND_STATUS_CONFIG = {
  UNDER_REVIEW: {
    label: 'Under review',
    variant: 'warning',
  },
  PROCESSING: {
    label: 'Processing',
    variant: 'info',
  },
  COMPLETED: {
    label: 'Completed',
    variant: 'success',
  },
  FAILED: {
    label: 'Failed',
    variant: 'danger',
  },
};

function humanizeStatus(status) {
  if (!status) return '';
  return status
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

export function getRefundStatusInfo(status) {
  if (!status) return null;
  return (
    REFUND_STATUS_CONFIG[status] || {
      label: humanizeStatus(status),
      variant: 'muted',
    }
  );
}
