// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  ProviderProfileNotFoundError,
  ProviderProfileUnavailableError,
  fetchProviderProfile,
} from './providerProfileAdapter.js';

afterEach(() => {
  vi.unstubAllEnvs();
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
  });
});

describe('fetchProviderProfile live mode', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'false');
  });

  it('reports public profiles as unavailable instead of using fixtures', async () => {
    await expect(fetchProviderProfile('prv-001')).rejects.toBeInstanceOf(
      ProviderProfileUnavailableError,
    );

    await expect(fetchProviderProfile('prv-001')).rejects.toMatchObject({
      code: 'PROVIDER_PROFILE_UNAVAILABLE',
    });
  });
});
