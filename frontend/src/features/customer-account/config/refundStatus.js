/**
 * Refund status mapping configuration for Customer Payments (WP01).
 *
 * Requirements & WP07 Contract Mapping:
 * UNDER_REVIEW / REQUIRES_RECONCILIATION -> "Under review", variant warning
 * PROCESSING / PENDING -> "Processing", variant info
 * COMPLETED / SUCCEEDED -> "Completed", variant success
 * FAILED / REJECTED -> "Failed", variant danger
 * CANCELLED -> "Cancelled", variant muted
 * Unknown status -> neutral badge (variant 'muted') with humanised raw value
 */

export const REFUND_STATUS_CONFIG = {
  UNDER_REVIEW: {
    label: 'Under review',
    variant: 'warning',
  },
  REQUIRES_RECONCILIATION: {
    label: 'Under review',
    variant: 'warning',
  },
  PROCESSING: {
    label: 'Processing',
    variant: 'info',
  },
  PENDING: {
    label: 'Processing',
    variant: 'info',
  },
  COMPLETED: {
    label: 'Completed',
    variant: 'success',
  },
  SUCCEEDED: {
    label: 'Completed',
    variant: 'success',
  },
  FAILED: {
    label: 'Failed',
    variant: 'danger',
  },
  REJECTED: {
    label: 'Failed',
    variant: 'danger',
  },
  CANCELLED: {
    label: 'Cancelled',
    variant: 'muted',
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
  const normalized = typeof status === 'string' ? status.trim().toUpperCase() : status;
  return (
    REFUND_STATUS_CONFIG[normalized] || {
      label: humanizeStatus(status),
      variant: 'muted',
    }
  );
}
