import { describe, expect, it } from 'vitest';

import {
  getProviderAvailabilityState,
  getProviderReviewState,
  getProviderServiceOptions,
  getRatingStarStates,
  normalizeProfileTab,
} from './providerProfile.js';

describe('normalizeProfileTab', () => {
  it('defaults unknown and missing tabs to overview', () => {
    expect(normalizeProfileTab()).toBe('overview');
    expect(normalizeProfileTab('unknown')).toBe('overview');
  });

  it('keeps supported tabs and accepts package/slot aliases', () => {
    expect(normalizeProfileTab('services')).toBe('services');
    expect(normalizeProfileTab('reviews')).toBe('reviews');
    expect(normalizeProfileTab('availability')).toBe('availability');
    expect(normalizeProfileTab('packages')).toBe('services');
    expect(normalizeProfileTab('slots')).toBe('availability');
  });
});

describe('getProviderServiceOptions', () => {
  it('maps known service types to readable labels without inventing package details', () => {
    const services = getProviderServiceOptions({
      serviceTypes: ['standard', 'deep'],
      description: 'Provider description',
      pricing: {
        hourlyRate: 40,
        minimumHours: 2,
        currency: '$',
        unit: 'hr',
      },
      inclusions: ['Supplies & equipment'],
    });

    expect(services.map((service) => service.label)).toEqual([
      'Standard home clean',
      'Deep clean',
    ]);
    expect(services[0]).toMatchObject({
      description: null,
      pricing: null,
      inclusions: null,
      isFallback: false,
    });
    expect(services[1]).toMatchObject({
      description: null,
      pricing: null,
      inclusions: null,
      isFallback: false,
    });
  });

  it('preserves supplied package-specific pricing, descriptions, and inclusions', () => {
    const services = getProviderServiceOptions({
      serviceTypes: [
        {
          type: 'standard',
          label: 'Standard clean',
          description: 'Standard package description',
          pricing: {
            hourlyRate: 38,
            minimumHours: 2,
            currency: '$',
            unit: 'hr',
          },
          inclusions: ['Eco supplies'],
        },
        {
          type: 'deep',
          name: 'Deep clean',
          description: 'Deep package description',
          pricing: {
            hourlyRate: 60,
            minimumHours: 3,
            currency: '$',
            unit: 'hr',
          },
          inclusions: ['Heavy-duty equipment', 'Supplies'],
        },
      ],
    });

    expect(services).toHaveLength(2);
    expect(services[0]).toMatchObject({
      label: 'Standard clean',
      description: 'Standard package description',
      inclusions: ['Eco supplies'],
    });
    expect(services[0].pricing).toMatchObject({ hourlyRate: 38 });
    expect(services[1]).toMatchObject({
      label: 'Deep clean',
      description: 'Deep package description',
      inclusions: ['Heavy-duty equipment', 'Supplies'],
    });
    expect(services[1].pricing).toMatchObject({ hourlyRate: 60 });
  });

  it('does not copy provider-level details into services without package data', () => {
    const services = getProviderServiceOptions({
      serviceTypes: ['standard', 'deep'],
      description: 'Provider description',
      pricing: {
        hourlyRate: 40,
        minimumHours: 2,
        currency: '$',
        unit: 'hr',
      },
      inclusions: ['Supplies & equipment'],
    });

    expect(
      services.every(
        (service) =>
          service.description === null &&
          service.pricing === null &&
          service.inclusions === null,
      ),
    ).toBe(true);
  });

  it('falls back to the provider category without reusing provider details', () => {
    const services = getProviderServiceOptions({
      category: 'Move-out cleaning',
      description: 'Turnaround care',
      pricing: {
        hourlyRate: 55,
        minimumHours: 3,
        currency: '$',
        unit: 'hr',
      },
      inclusions: ['Heavy-duty equipment'],
    });

    expect(services).toEqual([
      {
        key: 'category',
        label: 'Move-out cleaning',
        description: null,
        pricing: null,
        inclusions: null,
        isFallback: true,
      },
    ]);
  });

  it('title-cases unknown service types and removes duplicates', () => {
    const services = getProviderServiceOptions({
      serviceTypes: ['window_clean', 'window_clean'],
      description: 'Window care',
    });

    expect(services).toHaveLength(1);
    expect(services[0]).toMatchObject({
      key: 'window_clean',
      label: 'Window Clean',
      description: null,
      pricing: null,
      inclusions: null,
    });
  });
});

describe('getRatingStarStates', () => {
  it('renders empty stars for a zero-review rating', () => {
    expect(getRatingStarStates(0).map((star) => star.state)).toEqual([
      'empty',
      'empty',
      'empty',
      'empty',
      'empty',
    ]);
  });

  it('reflects low ratings without overstating them', () => {
    expect(getRatingStarStates(2).map((star) => star.state)).toEqual([
      'full',
      'full',
      'empty',
      'empty',
      'empty',
    ]);
  });

  it('uses a partial star for intermediate ratings', () => {
    const stars = getRatingStarStates(3.5);

    expect(stars.map((star) => star.state)).toEqual([
      'full',
      'full',
      'full',
      'partial',
      'empty',
    ]);
    expect(stars[3].fillPercent).toBe(50);
  });

  it('uses a partial star for high ratings that are not rounded up', () => {
    const stars = getRatingStarStates(4.9);

    expect(stars.map((star) => star.state)).toEqual([
      'full',
      'full',
      'full',
      'full',
      'partial',
    ]);
    expect(stars[4].fillPercent).toBe(90);
  });

  it('returns no stars when a numeric rating is unavailable', () => {
    expect(getRatingStarStates()).toEqual([]);
    expect(getRatingStarStates(null)).toEqual([]);
    expect(getRatingStarStates(Number.NaN)).toEqual([]);
  });
});

describe('getProviderReviewState', () => {
  it('keeps the aggregate and labels missing review content', () => {
    const state = getProviderReviewState({
      rating: 4.9,
      reviewCount: 128,
    });

    expect(state.rating).toBe(4.9);
    expect(state.reviewCount).toBe(128);
    expect(state.hasReviewContent).toBe(false);
    expect(state.message).toMatch(/individual review content is not available/i);
  });

  it('uses a no-reviews message for a zero count', () => {
    const state = getProviderReviewState({ rating: 0, reviewCount: 0 });

    expect(state.message).toMatch(/no reviews yet/i);
  });

  it('reports missing review details when no count is supplied', () => {
    const state = getProviderReviewState({});

    expect(state.reviewCount).toBeNull();
    expect(state.message).toMatch(/review details are not provided/i);
  });

  it('marks supplied review content as available', () => {
    const state = getProviderReviewState({
      rating: 5,
      reviewCount: 1,
      reviews: [{ id: 'r1', authorName: 'Alex R.', rating: 5, text: 'Great.' }],
    });

    expect(state.hasReviewContent).toBe(true);
    expect(state.message).toBeNull();
    expect(state.reviews).toHaveLength(1);
  });
});

describe('getProviderAvailabilityState', () => {
  it('exposes the next available label without inventing slot data', () => {
    const state = getProviderAvailabilityState({
      nextAvailable: 'Today, 2:00 PM',
    });

    expect(state.nextAvailable).toBe('Today, 2:00 PM');
    expect(state.hasSlotPreview).toBe(false);
    expect(state.slots).toEqual([]);
    expect(state.message).toMatch(/full slot preview is not available/i);
  });

  it('returns supplied slots when a payload includes them', () => {
    const state = getProviderAvailabilityState({
      nextAvailable: 'Tomorrow, 9:00 AM',
      availableSlots: [{ id: 'slot-1', label: '9:00 AM' }],
    });

    expect(state.hasSlotPreview).toBe(true);
    expect(state.message).toBeNull();
    expect(state.slots).toHaveLength(1);
  });
});
