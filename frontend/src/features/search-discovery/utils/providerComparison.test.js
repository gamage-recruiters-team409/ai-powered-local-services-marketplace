import { describe, expect, it } from 'vitest';
import {
  LOCATION_NOT_PROVIDED,
  NOT_PROVIDED,
  formatCancellationPolicy,
  formatDistanceArea,
  formatInclusions,
  formatNextAvailable,
  formatPrice,
  formatProviderLocation,
  formatRating,
  formatResponseTime,
  formatReviewCount,
  formatVerification,
  getCommonCategory,
  getDistanceChip,
  getLocationCity,
  getProviderShortName,
} from './providerComparison.js';

describe('provider comparison formatting', () => {
  it('formats price with a minimum duration', () => {
    expect(
      formatPrice({ hourlyRate: 45, minimumHours: 2, currency: '$', unit: 'hr' }),
    ).toBe('From $45/hr · 2-hour minimum');
  });

  it('formats price without a minimum duration', () => {
    expect(formatPrice({ hourlyRate: 40, currency: '$', unit: 'hr' })).toBe('From $40/hr');
  });

  it('does not assume currency or billing unit for partial pricing data', () => {
    expect(formatPrice({ hourlyRate: 45, unit: 'hr' })).toBe(NOT_PROVIDED);
    expect(formatPrice({ hourlyRate: 45, currency: '$' })).toBe(NOT_PROVIDED);
    expect(formatPrice({ hourlyRate: 45, currency: '  ', unit: 'hr' })).toBe(NOT_PROVIDED);
    expect(formatPrice({ hourlyRate: 45, currency: '$', unit: '  ' })).toBe(NOT_PROVIDED);
  });

  it('returns Not provided for missing price data', () => {
    expect(formatPrice()).toBe(NOT_PROVIDED);
    expect(formatPrice({})).toBe(NOT_PROVIDED);
  });

  it('formats ratings and review counts', () => {
    expect(formatRating(4.9)).toBe('4.9/5');
    expect(formatRating()).toBe(NOT_PROVIDED);
    expect(formatReviewCount(128)).toBe('128 reviews');
    expect(formatReviewCount(1)).toBe('1 review');
    expect(formatReviewCount(0)).toBe('No reviews yet');
    expect(formatReviewCount()).toBe(NOT_PROVIDED);
  });

  it('formats verification from the existing booleans', () => {
    expect(formatVerification({ isVerified: true, backgroundChecked: true })).toBe(
      'Identity + background checked',
    );
    expect(formatVerification({ isVerified: true, backgroundChecked: false })).toBe(
      'Identity verified',
    );
    expect(formatVerification({ isVerified: false, backgroundChecked: true })).toBe(
      'Not verified',
    );
    expect(formatVerification({ isVerified: false })).toBe('Not verified');
    expect(formatVerification()).toBe(NOT_PROVIDED);
  });

  it('normalizes response time copy', () => {
    expect(formatResponseTime('Replies within 15 min')).toBe('Within 15 minutes');
    expect(formatResponseTime('Replies within 1 hour')).toBe('Within 1 hour');
    expect(formatResponseTime()).toBe(NOT_PROVIDED);
  });

  it('formats next availability', () => {
    expect(formatNextAvailable('Today, 2:00 PM')).toBe('Today · 2:00 PM');
    expect(formatNextAvailable()).toBe(NOT_PROVIDED);
  });

  it('derives the city from a location string', () => {
    expect(getLocationCity('Austin, TX 78701')).toBe('Austin');
    expect(getLocationCity()).toBe('');
  });

  it('labels a missing provider location without borrowing another city', () => {
    expect(formatProviderLocation('Austin, TX 78701')).toBe('Austin');
    expect(formatProviderLocation()).toBe(LOCATION_NOT_PROVIDED);
    expect(formatProviderLocation('  ')).toBe(LOCATION_NOT_PROVIDED);
  });

  it('formats distance, city and service radius', () => {
    expect(
      formatDistanceArea({
        distanceText: '1.2 miles away',
        location: 'Austin, TX 78701',
        serviceRadiusMiles: 5,
      }),
    ).toBe('1.2 miles · Austin, 5-mile radius');

    expect(
      formatDistanceArea({
        distanceText: '2.4 miles away',
        location: 'Austin, TX 78704',
      }),
    ).toBe('2.4 miles · Austin');

    expect(formatDistanceArea({ distanceText: '1.2 miles away' })).toBe('1.2 miles');
    expect(formatDistanceArea({ serviceRadiusMiles: 5 })).toBe('5-mile radius');
    expect(formatDistanceArea()).toBe(NOT_PROVIDED);
  });

  it('formats inclusions and cancellation policy', () => {
    expect(formatInclusions(['Supplies & equipment'])).toBe('Supplies & equipment');
    expect(formatInclusions(['Heavy-duty equipment', 'Supplies & equipment'])).toBe(
      'Heavy-duty equipment, Supplies & equipment',
    );
    expect(formatInclusions([])).toBe(NOT_PROVIDED);
    expect(formatInclusions()).toBe(NOT_PROVIDED);
    expect(formatCancellationPolicy('Free up to 24 hours before; then 50% of booking')).toBe(
      'Free up to 24 hours before; then 50% of booking',
    );
    expect(formatCancellationPolicy('  ')).toBe(NOT_PROVIDED);
    expect(formatCancellationPolicy()).toBe(NOT_PROVIDED);
  });

  it('derives a short provider name for the choose action', () => {
    expect(getProviderShortName("Maya's Home Care")).toBe('Maya');
    expect(getProviderShortName('BrightNest Cleaning')).toBe('BrightNest');
    expect(getProviderShortName('Neat & Local')).toBe('Neat');
    expect(getProviderShortName()).toBe('');
  });

  it('derives the common category for the context chips', () => {
    expect(
      getCommonCategory([
        { category: 'Standard home clean' },
        { category: 'Standard home clean' },
      ]),
    ).toBe('Standard home clean');

    expect(
      getCommonCategory([
        { category: 'Standard home clean' },
        { category: 'Move-out cleaning' },
      ]),
    ).toBe('Mixed services');

    expect(getCommonCategory([])).toBe('Selected services');
  });

  it('derives the distance chip from the active filters', () => {
    expect(getDistanceChip({ distance: '5' })).toBe('Within 5 miles');
    expect(getDistanceChip({ distance: '25' })).toBe('All distances');
    expect(getDistanceChip({})).toBe('All distances');
  });
});
