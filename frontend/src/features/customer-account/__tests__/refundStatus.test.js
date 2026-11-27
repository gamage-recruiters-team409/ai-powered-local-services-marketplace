import { describe, it, expect } from 'vitest';
import { getRefundStatusInfo } from '../config/refundStatus';

describe('getRefundStatusInfo', () => {
  it('returns null for empty or null status', () => {
    expect(getRefundStatusInfo(null)).toBeNull();
    expect(getRefundStatusInfo(undefined)).toBeNull();
    expect(getRefundStatusInfo('')).toBeNull();
  });

  it('maps standard customer refund statuses correctly', () => {
    expect(getRefundStatusInfo('UNDER_REVIEW')).toEqual({
      label: 'Under review',
      variant: 'warning',
    });
    expect(getRefundStatusInfo('PROCESSING')).toEqual({
      label: 'Processing',
      variant: 'info',
    });
    expect(getRefundStatusInfo('COMPLETED')).toEqual({
      label: 'Completed',
      variant: 'success',
    });
    expect(getRefundStatusInfo('FAILED')).toEqual({
      label: 'Failed',
      variant: 'danger',
    });
  });

  it('maps WP07 internal refund statuses gracefully', () => {
    expect(getRefundStatusInfo('SUCCEEDED')).toEqual({
      label: 'Completed',
      variant: 'success',
    });
    expect(getRefundStatusInfo('succeeded')).toEqual({
      label: 'Completed',
      variant: 'success',
    });
    expect(getRefundStatusInfo('REQUIRES_RECONCILIATION')).toEqual({
      label: 'Under review',
      variant: 'warning',
    });
    expect(getRefundStatusInfo('PENDING')).toEqual({
      label: 'Processing',
      variant: 'info',
    });
    expect(getRefundStatusInfo('REJECTED')).toEqual({
      label: 'Failed',
      variant: 'danger',
    });
    expect(getRefundStatusInfo('CANCELLED')).toEqual({
      label: 'Cancelled',
      variant: 'muted',
    });
  });

  it('falls back to humanized title case and muted variant for unknown statuses', () => {
    expect(getRefundStatusInfo('ESCALATED_TO_SUPPORT')).toEqual({
      label: 'Escalated To Support',
      variant: 'muted',
    });
    expect(getRefundStatusInfo('awaiting_gateway_clearance')).toEqual({
      label: 'Awaiting Gateway Clearance',
      variant: 'muted',
    });
  });
});
