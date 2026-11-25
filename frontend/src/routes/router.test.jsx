// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock(
  '../features/search-discovery/services/searchAdapter',
  async (importOriginal) => {
    const actual = await importOriginal();

    return {
      ...actual,
      fetchSearchResults: vi.fn().mockResolvedValue({
        isLive: false,
        isFixture: false,
        results: [],
        totalCount: 0,
      }),
    };
  },
);

vi.mock(
  '../features/listings/landing/services/publicCatalogService',
  () => ({
    getPublicCategoryTree: vi.fn().mockResolvedValue([]),
    getPublicDistrictLocations: vi.fn().mockResolvedValue([]),
    getFeaturedPublicServices: vi.fn().mockResolvedValue({ results: [], totalCount: 0 }),
    getLiveCustomerReviews: vi.fn().mockResolvedValue({ reviews: [], requiresAuth: true }),
  }),
);

import { router } from './router.jsx';

function collectPaths(routes, acc = []) {
  routes.forEach((route) => {
    if (route.path) acc.push(route.path);
    if (route.children) collectPaths(route.children, acc);
  });
  return acc;
}

describe('shared application router definitions', () => {
  const paths = collectPaths(router.routes);

  it('mounts search at /search', () => {
    expect(paths).toContain('/search');
  });

  it('mounts public provider profiles at /providers/:providerId', () => {
    expect(paths).toContain('/providers/:providerId');
  });

  it('preserves administrative routes', () => {
    expect(paths).toContain('/admin');
  });

  it('mounts the LocalServe public landing page at /', () => {
    expect(paths).toContain('/');
  });

  it('keeps a shared catch-all fallback', () => {
    expect(paths).toContain('*');
  });
});

/**
 * Renders the real application entry point (App.jsx) at a given URL. The router
 * is a module-level singleton, so modules are reset between cases to recreate
 * it against the desired location.
 */
async function renderAppAt(path) {
  vi.resetModules();
  window.history.replaceState({}, '', path);
  const { default: App } = await import('../App.jsx');
  return render(<App />);
}

describe('application entry point uses the shared router', () => {
  afterEach(() => {
    cleanup();
    window.sessionStorage.clear();
  });

  it('renders the search page at /search', async () => {
    await renderAppAt('/search');

    expect(await screen.findByText('Home cleaning in Austin')).toBeTruthy();
  });

  it('renders the LocalServe public landing page at /', async () => {
    await renderAppAt('/');

    expect(
      await screen.findByRole('heading', {
        name: /Hire verified pros with absolute certainty\./i,
      }),
    ).toBeTruthy();
    expect(window.location.pathname).toBe('/');
  });

  it('uses the shared catch-all on unknown paths', async () => {
    await renderAppAt('/does-not-exist');

    expect(await screen.findByText('Page not found')).toBeTruthy();
  });

  it('keeps /admin behind the admin authorization guard', async () => {
    await renderAppAt('/admin');

    await waitFor(() => expect(window.location.pathname).toBe('/login'));
  });
});
