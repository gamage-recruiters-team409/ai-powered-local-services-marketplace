import { describe, it, expect } from 'vitest';
import { BOOKING_STATUS_CONFIG, getBookingStatusInfo } from '../config/bookingStatus';

describe('bookingStatus', () => {
  it('maps all 13 BookingStatus enum values correctly', () => {
    expect(getBookingStatusInfo('PENDING_PROVIDER')).toEqual({
      label: 'Awaiting provider',
      variant: 'warning',
    });
    expect(getBookingStatusInfo('AWAITING_PAYMENT')).toEqual({
      label: 'Awaiting payment',
      variant: 'warning',
    });
    expect(getBookingStatusInfo('CONFIRMED')).toEqual({
      label: 'Confirmed',
      variant: 'info',
    });
    expect(getBookingStatusInfo('IN_PROGRESS')).toEqual({
      label: 'In progress',
      variant: 'info',
    });
    expect(getBookingStatusInfo('COMPLETION_PENDING')).toEqual({
      label: 'Completion pending',
      variant: 'info',
    });
    expect(getBookingStatusInfo('COMPLETED')).toEqual({
      label: 'Completed',
      variant: 'success',
    });
    expect(getBookingStatusInfo('DECLINED')).toEqual({
      label: 'Declined',
      variant: 'danger',
    });
    expect(getBookingStatusInfo('EXPIRED')).toEqual({
      label: 'Expired',
      variant: 'muted',
    });
    expect(getBookingStatusInfo('CANCELLED_BY_CUSTOMER')).toEqual({
      label: 'Cancelled by you',
      variant: 'muted',
    });
    expect(getBookingStatusInfo('CANCELLED_BY_PROVIDER')).toEqual({
      label: 'Cancelled by provider',
      variant: 'muted',
    });
    expect(getBookingStatusInfo('DISPUTED')).toEqual({
      label: 'Disputed',
      variant: 'danger',
    });
    expect(getBookingStatusInfo('REFUNDED')).toEqual({
      label: 'Refunded',
      variant: 'muted',
    });
    expect(getBookingStatusInfo('PARTIALLY_REFUNDED')).toEqual({
      label: 'Partially refunded',
      variant: 'info',
    });
  });

  it('handles case-insensitivity and whitespace gracefully', () => {
    expect(getBookingStatusInfo('pending_provider')).toEqual({
      label: 'Awaiting provider',
      variant: 'warning',
    });
    expect(getBookingStatusInfo('  confirmed  ')).toEqual({
      label: 'Confirmed',
      variant: 'info',
    });
  });

  it('handles null, undefined, and empty string with neutral muted badge', () => {
    expect(getBookingStatusInfo(null)).toEqual({
      label: 'Unknown',
      variant: 'muted',
    });
    expect(getBookingStatusInfo(undefined)).toEqual({
      label: 'Unknown',
      variant: 'muted',
    });
    expect(getBookingStatusInfo('')).toEqual({
      label: 'Unknown',
      variant: 'muted',
    });
  });

  it('handles unknown status with a humanized label and muted variant', () => {
    expect(getBookingStatusInfo('CUSTOM_STATUS_NAME')).toEqual({
      label: 'Custom Status Name',
      variant: 'muted',
    });
    expect(getBookingStatusInfo('UNKNOWN_NEW_STATE')).toEqual({
      label: 'Unknown New State',
      variant: 'muted',
    });
  });

  it('defines exactly 13 status configs in BOOKING_STATUS_CONFIG', () => {
    expect(Object.keys(BOOKING_STATUS_CONFIG)).toHaveLength(13);
  });
});
