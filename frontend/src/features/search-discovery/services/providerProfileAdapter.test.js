// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('axios', () => ({
  default: { get: vi.fn() },
}));

import axios from 'axios';
import { FALLBACK_PROVIDER_IMAGE } from '../fixtures/providerImages.js';
import {
  ProviderProfileNotFoundError,
  fetchProviderProfile,
} from './providerProfileAdapter.js';

afterEach(() => {
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});

describe('fetchProviderProfile fixture mode', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'true');
  });

  it('returns the requested provider by ID', async () => {
    const data = await fetchProviderProfile('prv-001');

    expect(data.isFixture).toBe(true);
    expect(data.isLive).toBe(false);
    expect(data.provider.id).toBe('prv-001');
    expect(data.provider.name).toBe("Maya's Home Care");
    expect(data.provider.image).toMatch(/^https:\/\/res\.cloudinary\.com\//);
  });

  it('throws a not-found error for an unknown provider ID', async () => {
    await expect(fetchProviderProfile('missing-provider')).rejects.toBeInstanceOf(
      ProviderProfileNotFoundError,
    );

    await expect(fetchProviderProfile('missing-provider')).rejects.toMatchObject({
      code: 'PROVIDER_PROFILE_NOT_FOUND',
      providerId: 'missing-provider',
    });

    expect(axios.get).not.toHaveBeenCalled();
  });
});

describe('fetchProviderProfile live mode', () => {
  const liveProfile = {
    providerId: 'usr-123',
    profileId: 'prf-456',
    name: 'Live Provider',
    image: null,
    verified: true,
    rating: 4.7,
    reviewCount: 12,
    category: 'Home cleaning',
    serviceTypes: ['standard', 'deep'],
    description: 'Live provider description',
    location: 'Austin, TX',
    pricing: {
      amount: 55,
      model: 'HOURLY',
      durationMinutes: 60,
      currency: 'USD',
      unit: 'hour',
    },
    coverageRadiusKm: 10,
    listings: [
      {
        id: 'lst-789',
        title: 'Standard clean',
        description: 'Live listing',
        categoryName: 'Home cleaning',
        pricing: { amount: 55, model: 'HOURLY', durationMinutes: 60 },
        image: null,
        coverageRadiusKm: 10,
        serviceAreas: ['Austin'],
      },
    ],
    availabilityStatus: 'UNAVAILABLE',
    supported: {
      distance: false,
      availability: false,
      backgroundCheck: false,
      serviceTypes: false,
    },
  };

  beforeEach(() => {
    vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'false');
  });

  it('fetches the encoded live profile URL and maps the UI provider shape', async () => {
    axios.get.mockResolvedValueOnce({ data: liveProfile });

    const data = await fetchProviderProfile('user/123');

    expect(axios.get).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/providers/user%2F123',
    );
    expect(data.isLive).toBe(true);
    expect(data.isFixture).toBe(false);
    expect(data.provider).toMatchObject({
      id: 'usr-123',
      profileId: 'prf-456',
      name: 'Live Provider',
      businessName: 'Live Provider',
      image: FALLBACK_PROVIDER_IMAGE,
      isVerified: true,
      rating: 4.7,
      reviewCount: 12,
      category: 'Home cleaning',
      serviceTypes: ['standard', 'deep'],
      description: 'Live provider description',
      location: 'Austin, TX',
      distanceText: null,
      responseTimeText: null,
      nextAvailable: null,
      availableSlots: [],
      reviews: [],
      inclusions: [],
      cancellationPolicy: null,
      serviceRadiusMiles: null,
      listings: [
        {
          ...liveProfile.listings[0],
          image: FALLBACK_PROVIDER_IMAGE,
          pricing: {
            hourlyRate: 55,
            amount: 55,
            model: 'HOURLY',
            durationMinutes: 60,
            currency: null,
            unit: null,
          },
        },
      ],
      supported: {
        distance: false,
        availability: false,
        backgroundCheck: false,
        serviceTypes: false,
      },
    });
    expect(data.provider.pricing).toEqual({
      hourlyRate: 55,
      amount: 55,
      model: 'HOURLY',
      durationMinutes: 60,
      currency: null,
      unit: null,
    });
    expect(data.unsupported).toEqual([
      'distance',
      'availability',
      'backgroundCheck',
      'serviceTypes',
    ]);
  });

  it('keeps hourlyRate null for non-hourly live pricing and missing values', async () => {
    axios.get.mockResolvedValueOnce({
      data: {
        ...liveProfile,
        rating: null,
        reviewCount: null,
        pricing: { amount: 200, model: 'FIXED', durationMinutes: null },
      },
    });

    const { provider, unsupported } = await fetchProviderProfile('usr-123');

    expect(provider.rating).toBeNull();
    expect(provider.reviewCount).toBeNull();
    expect(provider.pricing).toEqual({
      hourlyRate: null,
      amount: 200,
      model: 'FIXED',
      durationMinutes: null,
      currency: null,
      unit: null,
    });
    expect(unsupported).toEqual([
      'distance',
      'availability',
      'backgroundCheck',
      'serviceTypes',
    ]);
  });

  it('maps a 404 response to ProviderProfileNotFoundError', async () => {
    axios.get
      .mockRejectedValueOnce({ response: { status: 404 } })
      .mockRejectedValueOnce({ response: { status: 404 } });

    await expect(fetchProviderProfile('missing')).rejects.toBeInstanceOf(
      ProviderProfileNotFoundError,
    );
    await expect(fetchProviderProfile('missing')).rejects.toMatchObject({
      code: 'PROVIDER_PROFILE_NOT_FOUND',
      providerId: 'missing',
    });
  });

  it('propagates other live failures instead of reporting unavailable or using fixtures', async () => {
    axios.get.mockRejectedValueOnce({ response: { status: 500 } });

    await expect(fetchProviderProfile('usr-123')).rejects.toMatchObject({
      response: { status: 500 },
    });
  });

  it('propagates malformed live profile responses', async () => {
    axios.get.mockResolvedValueOnce({ data: null });

    await expect(fetchProviderProfile('usr-123')).rejects.toThrow(
      /unexpected response/i,
    );
  });
});
