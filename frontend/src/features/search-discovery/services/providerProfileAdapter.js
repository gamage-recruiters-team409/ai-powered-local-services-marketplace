import axios from 'axios';
import { MOCK_SEARCH_RESULTS } from '../fixtures/mockSearchResults.js';
import { FALLBACK_PROVIDER_IMAGE } from '../fixtures/providerImages.js';
import {
  isFixtureModeEnabled,
  mapLivePricing,
  mapLiveSupportedFlags,
} from './searchAdapter.js';

const API_BASE_URL =
  (typeof import.meta !== 'undefined' && import.meta.env?.VITE_API_BASE_URL) ||
  'http://localhost:8080/api/v1';

const FIXTURE_LATENCY_MS = 120;

export class ProviderProfileNotFoundError extends Error {
  constructor(providerId) {
    super(`No provider profile was found for "${providerId}".`);
    this.name = 'ProviderProfileNotFoundError';
    this.code = 'PROVIDER_PROFILE_NOT_FOUND';
    this.providerId = providerId;
  }
}

export class ProviderProfileUnavailableError extends Error {
  constructor(
    message = 'Public provider profiles are not available yet.',
  ) {
    super(message);
    this.name = 'ProviderProfileUnavailableError';
    this.code = 'PROVIDER_PROFILE_UNAVAILABLE';
  }
}

async function fetchLiveProviderProfile(providerId) {
  let response;

  try {
    response = await axios.get(
      `${API_BASE_URL}/providers/${encodeURIComponent(providerId)}`,
    );
  } catch (error) {
    if (Number(error?.response?.status) === 404) {
      throw new ProviderProfileNotFoundError(providerId);
    }

    throw error;
  }

  const data = response?.data;
  if (!data || typeof data !== 'object') {
    throw new Error('Provider profile service returned an unexpected response.');
  }

  const supported = mapLiveSupportedFlags(data.supported);
  const provider = {
    id: data.providerId ?? providerId,
    providerId: data.providerId ?? providerId,
    profileId: data.profileId ?? null,
    name: data.name,
    businessName: data.name,
    image: data.image || FALLBACK_PROVIDER_IMAGE,
    isVerified: Boolean(data.verified),
    rating: typeof data.rating === 'number' ? data.rating : null,
    reviewCount: typeof data.reviewCount === 'number' ? data.reviewCount : null,
    category: data.category ?? null,
    serviceTypes: Array.isArray(data.serviceTypes) ? data.serviceTypes : [],
    description: data.description ?? null,
    location: data.location ?? null,
    pricing: mapLivePricing(data.pricing),
    distanceText: null,
    responseTimeText: null,
    nextAvailable: null,
    availableSlots: [],
    reviews: [],
    inclusions: [],
    cancellationPolicy: null,
    serviceRadiusMiles: null,
    supported,
    listings: Array.isArray(data.listings) ? data.listings : [],
  };

  return {
    isLive: true,
    isFixture: false,
    provider,
    unsupported: Object.keys(supported).filter((flag) => !supported[flag]),
  };
}

/**
 * Public provider profile adapter.
 *
 * Fixture mode is explicit and opt-in. Live mode calls the public provider
 * endpoint, maps 404 to ProviderProfileNotFoundError, and propagates every other
 * failure. It never falls back to fixture data.
 */
export async function fetchProviderProfile(providerId) {
  if (!isFixtureModeEnabled()) {
    return fetchLiveProviderProfile(providerId);
  }

  await new Promise((resolve) => setTimeout(resolve, FIXTURE_LATENCY_MS));

  const provider = MOCK_SEARCH_RESULTS.find((item) => item.id === providerId);

  if (!provider) {
    throw new ProviderProfileNotFoundError(providerId);
  }

  return {
    isFixture: true,
    isLive: false,
    provider: { ...provider },
  };
}
