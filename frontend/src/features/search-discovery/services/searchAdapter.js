import axios from 'axios';
import { MOCK_SEARCH_RESULTS } from '../fixtures/mockSearchResults.js';

const API_BASE_URL =
  (typeof import.meta !== 'undefined' && import.meta.env?.VITE_API_BASE_URL) ||
  'http://localhost:8080/api/v1';

/**
 * Fixture mode is explicit and opt-in. It must never be enabled implicitly in
 * live operation. Enable it for demos/local development with:
 *   VITE_SEARCH_FIXTURE_MODE=true
 */
export function isFixtureModeEnabled() {
  return String(import.meta.env?.VITE_SEARCH_FIXTURE_MODE ?? '').toLowerCase() === 'true';
}

export const HIGH_RATING_THRESHOLD = 4.5;

const AVAILABILITY_MATCHERS = {
  today: (item) => /^today\b/i.test(item.nextAvailable || ''),
  tomorrow: (item) => /^tomorrow\b/i.test(item.nextAvailable || ''),
};

const SERVICE_TYPE_KEYS = [
  ['standardClean', 'standard'],
  ['deepClean', 'deep'],
  ['moveOutClean', 'moveout'],
  ['recurringClean', 'recurring'],
];

function getActiveServiceTypes(filters = {}) {
  return SERVICE_TYPE_KEYS.filter(([key]) => filters[key]).map(([, type]) => type);
}

function buildLiveParams({ trimmedQuery, trimmedLocation, filters, sortBy }) {
  const availability =
    filters.availability && filters.availability !== 'any' && filters.availability !== 'custom'
      ? filters.availability
      : undefined;

  return {
    q: trimmedQuery,
    location: trimmedLocation,
    sort: sortBy,
    verified: filters.verifiedOnly,
    serviceTypes: getActiveServiceTypes(filters).join(','),
    maxHourlyRate: typeof filters.budgetMax === 'number' ? filters.budgetMax : undefined,
    minRating: filters.highRating ? HIGH_RATING_THRESHOLD : undefined,
    availability,
    maxDistanceMiles: filters.distance != null ? Number(filters.distance) : undefined,
    backgroundChecked: filters.backgroundChecked ? true : undefined,
  };
}

/**
 * Applies every visible sidebar filter to the fixture data so results match
 * the controls the user can actually see and change.
 */
export function filterFixtureResults({
  query = '',
  location = '',
  filters = {},
  sortBy = 'recommended',
} = {}) {
  const trimmedQuery = query.trim().toLowerCase();
  const trimmedLocation = location.trim().toLowerCase();
  // Match on the city portion of the location so nearby reference providers are
  // not excluded by an overly strict full ZIP-code comparison.
  const locationCity = trimmedLocation.split(',')[0].trim();
  const queryTokens = trimmedQuery.split(/\s+/).filter(Boolean);

  let results = [...MOCK_SEARCH_RESULTS];

  // 1. Text Query Filter
  if (trimmedQuery) {
    results = results.filter((item) => {
      const haystack = `${item.name} ${item.description} ${item.category}`.toLowerCase();
      if (haystack.includes(trimmedQuery)) return true;
      // Lenient token matching: a multi-word service query such as
      // "Home cleaning" should still match providers whose listings use
      // related wording (e.g. "Standard home clean", "deep cleans").
      return queryTokens.some((token) => haystack.includes(token));
    });
  }

  // 2. Location Filter
  if (trimmedLocation) {
    results = results.filter((item) => {
      const itemLocation = item.location.toLowerCase();
      if (itemLocation.includes(trimmedLocation)) return true;
      return locationCity.length > 0 && itemLocation.includes(locationCity);
    });
  }

  // 3. Service Type Filter
  // No selected service types means "no restriction", so clearing the service
  // filters broadens the search instead of excluding every provider.
  const activeServiceTypes = getActiveServiceTypes(filters);
  if (activeServiceTypes.length > 0) {
    results = results.filter((item) =>
      item.serviceTypes?.some((t) => activeServiceTypes.includes(t)),
    );
  }

  // 4. Verified Only Filter
  if (filters.verifiedOnly) {
    results = results.filter((item) => item.isVerified);
  }

  // 5. Hourly Budget Filter
  if (typeof filters.budgetMax === 'number') {
    results = results.filter((item) => item.pricing.hourlyRate <= filters.budgetMax);
  }

  // 6. Rating Filter (4.5 stars & above)
  if (filters.highRating) {
    results = results.filter((item) => item.rating >= HIGH_RATING_THRESHOLD);
  }

  // 7. Background Checked Filter
  if (filters.backgroundChecked) {
    results = results.filter((item) => item.backgroundChecked === true);
  }

  // 8. Distance Filter
  if (filters.distance != null) {
    const maxDistance = Number(filters.distance);
    if (Number.isFinite(maxDistance)) {
      results = results.filter(
        (item) =>
          typeof item.distanceMiles !== 'number' || item.distanceMiles <= maxDistance,
      );
    }
  }

  // 9. Availability Filter ("custom" date is not supported yet and is disabled in the UI)
  const availabilityMatcher = AVAILABILITY_MATCHERS[filters.availability];
  if (availabilityMatcher) {
    results = results.filter(availabilityMatcher);
  }

  // 10. Sorting
  if (sortBy === 'rating') {
    results.sort((a, b) => b.rating - a.rating);
  } else if (sortBy === 'price_asc') {
    results.sort((a, b) => a.pricing.hourlyRate - b.pricing.hourlyRate);
  } else if (sortBy === 'price_desc') {
    results.sort((a, b) => b.pricing.hourlyRate - a.pricing.hourlyRate);
  }

  return results;
}

/**
 * Search Adapter for WP04 Search & Discovery foundation.
 *
 * Two explicit modes:
 * - Fixture mode (VITE_SEARCH_FIXTURE_MODE=true): serves clearly-labelled demo
 *   providers and never calls the backend.
 * - Live mode (default): calls `${API_BASE_URL}/search` and propagates genuine
 *   failures so the page can show its error/retry state. It never falls back to
 *   fixtures silently.
 */
export async function fetchSearchResults({
  query = '',
  location = '',
  filters = {},
  sortBy = 'recommended',
} = {}) {
  const trimmedQuery = query.trim().toLowerCase();
  const trimmedLocation = location.trim().toLowerCase();

  if (isFixtureModeEnabled()) {
    // Brief latency simulation for realistic UI transition
    await new Promise((resolve) => setTimeout(resolve, 200));
    const results = filterFixtureResults({ query, location, filters, sortBy });
    return {
      isLive: false,
      isFixture: true,
      results,
      totalCount: results.length,
    };
  }

  const response = await axios.get(`${API_BASE_URL}/search`, {
    params: buildLiveParams({ trimmedQuery, trimmedLocation, filters, sortBy }),
    timeout: 2500,
  });

  const results = response?.data?.results;
  if (!Array.isArray(results)) {
    throw new Error('Search service returned an unexpected response.');
  }

  return {
    isLive: true,
    isFixture: false,
    results,
    totalCount: response.data.totalCount ?? results.length,
  };
}
