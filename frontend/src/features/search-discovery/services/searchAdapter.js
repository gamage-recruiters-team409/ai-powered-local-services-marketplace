import axios from 'axios';
import { MOCK_SEARCH_RESULTS } from '../fixtures/mockSearchResults.js';
import { FALLBACK_PROVIDER_IMAGE } from '../fixtures/providerImages.js';

const API_BASE_URL =
  (typeof import.meta !== 'undefined' && import.meta.env?.VITE_API_BASE_URL) ||
  'http://localhost:8080/api/v1';

export const UNSUPPORTED_LIVE_FILTERS = [
  'serviceTypes',
  'availability',
  'maxDistanceMiles',
  'backgroundChecked',
  'minRating',
  'budgetMax',
];

const SUPPORTED_LIVE_SORTS = ['recommended', 'price_asc', 'price_desc'];

export const DEFAULT_SEARCH_PAGE = 0;
export const DEFAULT_SEARCH_SIZE = 12;

const LIVE_REQUEST_TIMEOUT_MS = 2500;

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

function toNonNegativeInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : fallback;
}

function toPositiveInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

export function getRequestedUnsupportedLiveFilters(filters = {}) {
  const requested = [];

  if (getActiveServiceTypes(filters).length > 0) {
    requested.push('serviceTypes');
  }

  if (
    filters.availability &&
    filters.availability !== 'any' &&
    filters.availability !== 'custom'
  ) {
    requested.push('availability');
  }

  if (filters.distance != null && String(filters.distance).trim() !== '') {
    requested.push('maxDistanceMiles');
  }

  if (filters.backgroundChecked) {
    requested.push('backgroundChecked');
  }

  if (filters.highRating) {
    requested.push('minRating');
  }

  if (
    typeof filters.budgetMax === 'number' &&
    Number.isFinite(filters.budgetMax)
  ) {
    requested.push('budgetMax');
  }

  return requested;
}

export function mapLivePricing(pricing) {
  const source = pricing && typeof pricing === 'object' ? pricing : {};
  const amount =
    typeof source.amount === 'number' && Number.isFinite(source.amount)
      ? source.amount
      : null;
  const model = typeof source.model === 'string' ? source.model : null;

  return {
    hourlyRate: model === 'HOURLY' ? amount : null,
    amount,
    model,
    durationMinutes:
      typeof source.durationMinutes === 'number' &&
      Number.isFinite(source.durationMinutes)
        ? source.durationMinutes
        : null,
    currency: null,
    unit: null,
  };
}

export function mapLiveSupportedFlags(supported) {
  const source = supported && typeof supported === 'object' ? supported : {};

  return {
    distance: Boolean(source.distance),
    availability: Boolean(source.availability),
    backgroundCheck: Boolean(source.backgroundCheck),
    serviceTypes: Boolean(source.serviceTypes),
  };
}

export function mapProviderSearchResult(result) {
  const source = result && typeof result === 'object' ? result : {};

  return {
    id: source.listingId ?? source.providerId,
    providerId: source.providerId,
    profileId: source.profileId ?? null,
    listingId: source.listingId ?? null,
    name: source.name,
    image: source.image || FALLBACK_PROVIDER_IMAGE,
    isVerified: Boolean(source.verified),
    rating: typeof source.rating === 'number' ? source.rating : null,
    reviewCount: typeof source.reviewCount === 'number' ? source.reviewCount : null,
    category: source.category ?? null,
    description: source.description ?? null,
    location: source.location ?? null,
    pricing: mapLivePricing(source.pricing),
    distanceText: null,
    responseTimeText: null,
    nextAvailable: null,
    serviceTypes: [],
    inclusions: [],
    cancellationPolicy: null,
    serviceRadiusMiles: null,
    supported: mapLiveSupportedFlags(source.supported),
  };
}

function buildLiveParams({
  trimmedQuery,
  trimmedLocation,
  filters,
  sortBy,
  page,
  size,
}) {
  const params = {};

  if (trimmedQuery) {
    params.q = trimmedQuery;
  }

  if (trimmedLocation) {
    params.location = trimmedLocation;
  }

  if (filters.verifiedOnly === true) {
    params.verified = true;
  }

  params.sort = SUPPORTED_LIVE_SORTS.includes(sortBy) ? sortBy : 'recommended';
  params.page = page;
  params.size = size;

  return params;
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
  page = DEFAULT_SEARCH_PAGE,
  size = DEFAULT_SEARCH_SIZE,
} = {}) {
  const trimmedQuery = query.trim();
  const trimmedLocation = location.trim();
  const normalizedPage = toNonNegativeInteger(page, DEFAULT_SEARCH_PAGE);
  const normalizedSize = toPositiveInteger(size, DEFAULT_SEARCH_SIZE);

  if (isFixtureModeEnabled()) {
    // Brief latency simulation for realistic UI transition
    await new Promise((resolve) => setTimeout(resolve, 200));
    const filteredResults = filterFixtureResults({ query, location, filters, sortBy });
    const start = normalizedPage * normalizedSize;
    const results = filteredResults.slice(start, start + normalizedSize);
    return {
      isLive: false,
      isFixture: true,
      results,
      totalCount: filteredResults.length,
      page: normalizedPage,
      size: normalizedSize,
      pageCount: Math.ceil(filteredResults.length / normalizedSize),
      unsupportedFilters: [],
    };
  }

  const response = await axios.get(`${API_BASE_URL}/search`, {
    params: buildLiveParams({
      trimmedQuery,
      trimmedLocation,
      filters,
      sortBy,
      page: normalizedPage,
      size: normalizedSize,
    }),
    timeout: LIVE_REQUEST_TIMEOUT_MS,
  });

  const data = response?.data;
  if (!Array.isArray(data?.results)) {
    throw new Error('Search service returned an unexpected response.');
  }

  const totalCount =
    Number.isFinite(Number(data.totalCount)) && data.totalCount != null
      ? Number(data.totalCount)
      : data.results.length;
  const responsePage = toNonNegativeInteger(data.page, normalizedPage);
  const responseSize = toPositiveInteger(data.size, normalizedSize);
  const pageCount = Number.isFinite(Number(data.pageCount))
    ? Number(data.pageCount)
    : Math.ceil(totalCount / responseSize);
  const backendUnsupported = Array.isArray(data.unsupportedFilters)
    ? data.unsupportedFilters.filter((name) => typeof name === 'string')
    : [];

  return {
    isLive: true,
    isFixture: false,
    results: data.results.map(mapProviderSearchResult),
    totalCount,
    page: responsePage,
    size: responseSize,
    pageCount,
    unsupportedFilters: [
      ...new Set([
        ...getRequestedUnsupportedLiveFilters(filters),
        ...backendUnsupported,
      ]),
    ],
  };
}
