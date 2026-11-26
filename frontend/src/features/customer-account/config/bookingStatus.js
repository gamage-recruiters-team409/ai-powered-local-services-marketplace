/**
 * Booking status mapping configuration for Customer Bookings (WP05).
 *
 * Maps all 13 backend BookingStatus enum values to human-friendly labels
 * and StatusBadge variant styles (success, info, warning, danger, muted).
 */

export const BOOKING_STATUS_CONFIG = {
  PENDING_PROVIDER: {
    label: 'Awaiting provider',
    variant: 'warning',
  },
  AWAITING_PAYMENT: {
    label: 'Awaiting payment',
    variant: 'warning',
  },
  CONFIRMED: {
    label: 'Confirmed',
    variant: 'info',
  },
  IN_PROGRESS: {
    label: 'In progress',
    variant: 'info',
  },
  COMPLETION_PENDING: {
    label: 'Completion pending',
    variant: 'info',
  },
  COMPLETED: {
    label: 'Completed',
    variant: 'success',
  },
  DECLINED: {
    label: 'Declined',
    variant: 'danger',
  },
  EXPIRED: {
    label: 'Expired',
    variant: 'muted',
  },
  CANCELLED_BY_CUSTOMER: {
    label: 'Cancelled by you',
    variant: 'muted',
  },
  CANCELLED_BY_PROVIDER: {
    label: 'Cancelled by provider',
    variant: 'muted',
  },
  DISPUTED: {
    label: 'Disputed',
    variant: 'danger',
  },
  REFUNDED: {
    label: 'Refunded',
    variant: 'muted',
  },
  PARTIALLY_REFUNDED: {
    label: 'Partially refunded',
    variant: 'info',
  },
};

function humanizeStatus(status) {
  if (!status) return 'Unknown';
  return String(status)
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

export function getBookingStatusInfo(status) {
  if (!status) {
    return {
      label: 'Unknown',
      variant: 'muted',
    };
  }
  const normalized = typeof status === 'string' ? status.trim().toUpperCase() : status;
  return (
    BOOKING_STATUS_CONFIG[normalized] || {
      label: humanizeStatus(status),
      variant: 'muted',
    }
  );
}
