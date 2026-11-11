// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('axios', () => ({
  default: { get: vi.fn() },
}));

import axios from 'axios';
import {
  HIGH_RATING_THRESHOLD,
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
});

describe('fetchSearchResults live mode', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'false');
  });

  it('propagates live API failures instead of falling back to fixtures', async () => {
    axios.get.mockRejectedValueOnce(new Error('Network Error'));

    await expect(fetchSearchResults({ query: 'Home cleaning' })).rejects.toThrow(
      'Network Error',
    );
  });

  it('propagates malformed live responses', async () => {
    axios.get.mockResolvedValueOnce({ data: { unexpected: true } });

    await expect(fetchSearchResults({ query: 'Home cleaning' })).rejects.toThrow(
      /unexpected response/i,
    );
  });

  it('returns live results and forwards the supported filters', async () => {
    axios.get.mockResolvedValueOnce({
      data: { results: [{ id: 'live-1' }], totalCount: 1 },
    });

    const data = await fetchSearchResults({
      query: 'Home cleaning',
      location: 'Austin',
      sortBy: 'rating',
      filters: {
        verifiedOnly: true,
        standardClean: true,
        budgetMax: 60,
        highRating: true,
        availability: 'today',
        distance: '5',
        backgroundChecked: true,
      },
    });

    expect(data.isLive).toBe(true);
    expect(data.isFixture).toBe(false);

    const params = axios.get.mock.calls[0][1].params;
    expect(params.minRating).toBe(4.5);
    expect(params.availability).toBe('today');
    expect(params.maxHourlyRate).toBe(60);
    expect(params.maxDistanceMiles).toBe(5);
    expect(params.backgroundChecked).toBe(true);
  });
});
