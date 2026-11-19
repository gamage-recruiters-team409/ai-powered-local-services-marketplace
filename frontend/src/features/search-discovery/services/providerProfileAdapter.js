import { MOCK_SEARCH_RESULTS } from '../fixtures/mockSearchResults.js';
import { isFixtureModeEnabled } from './searchAdapter.js';

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

/**
 * Public provider profile adapter.
 *
 * Fixture mode is explicit and opt-in. Live mode currently fails with a clear
 * unavailable error because no public provider-profile contract exists yet; it
 * must never fall back to fixture data.
 */
export async function fetchProviderProfile(providerId) {
  if (!isFixtureModeEnabled()) {
    throw new ProviderProfileUnavailableError();
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
