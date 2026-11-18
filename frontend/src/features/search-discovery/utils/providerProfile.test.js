import { describe, expect, it } from 'vitest';

import {
  getProviderAvailabilityState,
  getProviderReviewState,
  getProviderServiceOptions,
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
  it('maps known service types to readable packages', () => {
    const services = getProviderServiceOptions({
      serviceTypes: ['standard', 'deep'],
      description: 'Provider description',
    });

    expect(services.map((service) => service.label)).toEqual([
      'Standard home clean',
      'Deep clean',
    ]);
    expect(services[1].description).toMatch(/baseboards/i);
  });

  it('falls back to the provider category when service types are absent', () => {
    const services = getProviderServiceOptions({
      category: 'Move-out cleaning',
      description: 'Turnaround care',
    });

    expect(services).toEqual([
      {
        key: 'category',
        label: 'Move-out cleaning',
        description: 'Turnaround care',
      },
    ]);
  });

  it('title-cases unknown service types and removes duplicates', () => {
    const services = getProviderServiceOptions({
      serviceTypes: ['window_clean', 'window_clean'],
      description: 'Window care',
    });

    expect(services).toHaveLength(1);
    expect(services[0].label).toBe('Window Clean');
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
