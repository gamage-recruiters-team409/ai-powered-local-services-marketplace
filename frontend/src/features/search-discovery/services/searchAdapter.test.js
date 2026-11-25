// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('axios', () => ({
  default: { get: vi.fn() },
}));

import axios from 'axios';
import { FALLBACK_PROVIDER_IMAGE } from '../fixtures/providerImages.js';
import {
  HIGH_RATING_THRESHOLD,
  UNSUPPORTED_LIVE_FILTERS,
  fetchSearchResults,
  filterFixtureResults,
} from './searchAdapter.js';

afterEach(() => {
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});

describe('filterFixtureResults', () => {
  const DEFAULT_FILTERS = {
    standardClean: true,
    deepClean: false,
    moveOutClean: false,
    recurringClean: false,
    verifiedOnly: true,
    budgetMax: 60,
    availability: 'any',
    highRating: false,
    backgroundChecked: false,
    distance: '5',
  };

  const CLEARED_FILTERS = {
    standardClean: false,
    deepClean: false,
    moveOutClean: false,
    recurringClean: false,
    verifiedOnly: false,
    budgetMax: 80,
    availability: 'any',
    highRating: false,
    backgroundChecked: false,
    distance: '25',
  };

  it('returns the reference providers for the default search', () => {
    const results = filterFixtureResults({
      query: 'Home cleaning',
      location: 'Austin, TX 78701',
      filters: DEFAULT_FILTERS,
    });

    expect(results.map((item) => item.id).sort()).toEqual([
      'prv-001',
      'prv-002',
      'prv-003',
    ]);
  });

  it('does not exclude nearby providers because of a full ZIP code', () => {
    const results = filterFixtureResults({ location: 'Austin, TX 78701', filters: {} });
    expect(results).toHaveLength(4);
  });

  it('treats "no service types selected" as no restriction (Clear all)', () => {
    const results = filterFixtureResults({
      query: 'Home cleaning',
      location: 'Austin, TX 78701',
      filters: CLEARED_FILTERS,
    });

    expect(results.map((item) => item.id).sort()).toEqual([
      'prv-001',
      'prv-002',
      'prv-003',
      'prv-004',
    ]);
  });

  it('applies the 4.5-star rating threshold (not 4.8)', () => {
    expect(HIGH_RATING_THRESHOLD).toBe(4.5);

    const results = filterFixtureResults({ filters: { highRating: true } });

    // Capital City Cleaners is rated 4.6, so a 4.5 threshold must keep it.
    expect(results.map((item) => item.id)).toContain('prv-004');
  });

  it('filters by availability (today / tomorrow)', () => {
    const today = filterFixtureResults({ filters: { availability: 'today' } });
    expect(today.map((item) => item.id).sort()).toEqual(['prv-001', 'prv-003']);

    const tomorrow = filterFixtureResults({ filters: { availability: 'tomorrow' } });
    expect(tomorrow.map((item) => item.id)).toEqual(['prv-002']);
  });

  it('filters by distance', () => {
    const results = filterFixtureResults({ filters: { distance: '1.5' } });
    expect(results.map((item) => item.id)).toEqual(['prv-001']);
  });

  it('filters by background checked', () => {
    const results = filterFixtureResults({ filters: { backgroundChecked: true } });
    expect(results.map((item) => item.id).sort()).toEqual([
      'prv-001',
      'prv-002',
      'prv-003',
    ]);
  });

  it('filters by hourly budget', () => {
    const results = filterFixtureResults({ filters: { budgetMax: 45 } });
    expect(results.map((item) => item.id).sort()).toEqual(['prv-001', 'prv-002']);
  });

  it('sorts by price ascending', () => {
    const results = filterFixtureResults({ sortBy: 'price_asc' });
    const rates = results.map((item) => item.pricing.hourlyRate);
    expect(rates).toEqual([...rates].sort((a, b) => a - b));
  });
});

describe('fetchSearchResults', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'true');
  });

  it('serves clearly-identified fixtures in explicit fixture mode', async () => {
    const data = await fetchSearchResults({ query: 'Home cleaning' });

    expect(data.isLive).toBe(false);
    expect(data.isFixture).toBe(true);
    expect(data.results.length).toBeGreaterThan(0);
    expect(axios.get).not.toHaveBeenCalled();
  });

  it('references Cloudinary-hosted provider images', async () => {
    const data = await fetchSearchResults({ query: 'Home cleaning' });

    data.results.forEach((item) => {
      expect(item.image).toMatch(/^https:\/\/res\.cloudinary\.com\//);
    });
  });

  it('paginates fixture results and preserves the full filtered count', async () => {
    const firstPage = await fetchSearchResults({
      query: 'Home cleaning',
      page: 0,
      size: 2,
    });
    const secondPage = await fetchSearchResults({
      query: 'Home cleaning',
      page: 1,
      size: 2,
    });

    expect(firstPage.results.map((item) => item.id)).toEqual(['prv-001', 'prv-002']);
    expect(secondPage.results.map((item) => item.id)).toEqual(['prv-003', 'prv-004']);
    expect(firstPage.totalCount).toBe(4);
    expect(secondPage.totalCount).toBe(4);
    expect(firstPage.page).toBe(0);
    expect(secondPage.page).toBe(1);
    expect(firstPage.size).toBe(2);
    expect(secondPage.size).toBe(2);
    expect(firstPage.pageCount).toBe(2);
    expect(secondPage.pageCount).toBe(2);
    expect(firstPage.unsupportedFilters).toEqual([]);
  });
});

describe('fetchSearchResults live mode', () => {
  const liveResult = {
    providerId: 'usr-123',
    profileId: 'prf-456',
    listingId: 'lst-789',
    name: 'Live Provider',
    image: null,
    verified: true,
    rating: null,
    reviewCount: null,
    category: 'Home cleaning',
    description: 'Live provider description',
    location: 'Austin, TX',
    pricing: {
      amount: 55,
      model: 'FIXED',
      durationMinutes: 120,
      currency: 'USD',
      unit: 'job',
    },
    coverageRadiusKm: 10,
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

  it('propagates live API failures instead of falling back to fixtures', async () => {
    axios.get.mockRejectedValueOnce(new Error('Network Error'));

    await expect(fetchSearchResults({ query: 'Home cleaning' })).rejects.toThrow(
      'Network Error',
    );
    expect(axios.get).toHaveBeenCalledTimes(1);
  });

  it('propagates malformed live responses', async () => {
    axios.get.mockResolvedValueOnce({ data: { unexpected: true } });

    await expect(fetchSearchResults({ query: 'Home cleaning' })).rejects.toThrow(
      /unexpected response/i,
    );
  });

  it('sends only supported live params and reports requested unsupported filters', async () => {
    axios.get.mockResolvedValueOnce({
      data: {
        results: [],
        totalCount: 0,
        page: 0,
        size: 12,
        pageCount: 0,
        unsupportedFilters: [],
      },
    });

    const data = await fetchSearchResults({
      query: ' Home cleaning ',
      location: ' Austin ',
      sortBy: 'rating',
      page: 0,
      size: 12,
      filters: {
        verifiedOnly: true,
        budgetMax: 60,
        standardClean: true,
        distance: '5',
        availability: 'today',
        backgroundChecked: true,
        highRating: true,
      },
    });

    const [url, config] = axios.get.mock.calls[0];
    expect(url).toBe('http://localhost:8080/api/v1/search');
    expect(config.timeout).toBe(2500);
    expect(config.params).toEqual({
      q: 'Home cleaning',
      location: 'Austin',
      verified: true,
      sort: 'recommended',
      page: 0,
      size: 12,
    });
    expect(config.params.maxPrice).toBeUndefined();
    expect(UNSUPPORTED_LIVE_FILTERS).toEqual([
      'serviceTypes',
      'availability',
      'maxDistanceMiles',
      'backgroundChecked',
      'minRating',
      'budgetMax',
    ]);
    expect(data.unsupportedFilters).toEqual(UNSUPPORTED_LIVE_FILTERS);
  });

  it('does not apply the budget threshold across FIXED or HOURLY live listings', async () => {
    for (const model of ['FIXED', 'HOURLY']) {
      axios.get.mockResolvedValueOnce({
        data: {
          results: [{ ...liveResult, pricing: { amount: 55, model } }],
          totalCount: 1,
          page: 0,
          size: 12,
          pageCount: 1,
          unsupportedFilters: [],
        },
      });

      const data = await fetchSearchResults({
        filters: { budgetMax: 55 },
      });

      const [, config] = axios.get.mock.calls.at(-1);
      expect(config.params.maxPrice).toBeUndefined();
      expect(config.params).not.toHaveProperty('maxHourlyRate');
      expect(data.results[0].pricing.model).toBe(model);
      expect(data.unsupportedFilters).toContain('budgetMax');
    }
  });

  it('maps live results into UI items with null-safe pricing', async () => {
    axios.get.mockResolvedValueOnce({
      data: {
        results: [
          liveResult,
          {
            ...liveResult,
            providerId: 'usr-456',
            pricing: {
              amount: 40,
              model: 'HOURLY',
              durationMinutes: null,
              currency: null,
              unit: null,
            },
          },
        ],
        totalCount: 2,
        page: 0,
        size: 12,
        pageCount: 1,
      },
    });

    const data = await fetchSearchResults({
      query: 'Home cleaning',
      filters: { standardClean: true },
    });

    expect(data.isLive).toBe(true);
    expect(data.isFixture).toBe(false);
    expect(data.results[0]).toMatchObject({
      id: 'lst-789',
      providerId: 'usr-123',
      profileId: 'prf-456',
      listingId: 'lst-789',
      name: 'Live Provider',
      isVerified: true,
      rating: null,
      reviewCount: null,
      image: FALLBACK_PROVIDER_IMAGE,
      distanceText: null,
      responseTimeText: null,
      nextAvailable: null,
      serviceTypes: [],
      inclusions: [],
      cancellationPolicy: null,
      serviceRadiusMiles: null,
    });
    expect(data.results[0].pricing).toEqual({
      hourlyRate: null,
      amount: 55,
      model: 'FIXED',
      durationMinutes: 120,
      currency: null,
      unit: null,
    });
    expect(data.results[0].supported).toEqual({
      distance: false,
      availability: false,
      backgroundCheck: false,
      serviceTypes: false,
    });
    expect(data.results[1].pricing.hourlyRate).toBe(40);
    expect(data.unsupportedFilters).toEqual(['serviceTypes']);
  });

  it('maps pagination metadata from the live response', async () => {
    axios.get.mockResolvedValueOnce({
      data: {
        results: [liveResult],
        totalCount: 37,
        page: 2,
        size: 12,
        pageCount: 4,
        unsupportedFilters: ['availability'],
      },
    });

    const data = await fetchSearchResults({ page: 2, size: 12 });

    expect(data.totalCount).toBe(37);
    expect(data.page).toBe(2);
    expect(data.size).toBe(12);
    expect(data.pageCount).toBe(4);
    expect(data.unsupportedFilters).toEqual(['availability']);
  });

  it('merges backend unsupported filters with local ones without duplicates', async () => {
    axios.get.mockResolvedValueOnce({
      data: {
        results: [],
        totalCount: 0,
        page: 0,
        size: 12,
        unsupportedFilters: ['availability', 'backendOnly'],
      },
    });

    const data = await fetchSearchResults({
      filters: { availability: 'today' },
    });

    expect(data.unsupportedFilters).toEqual(['availability', 'backendOnly']);
  });
});
