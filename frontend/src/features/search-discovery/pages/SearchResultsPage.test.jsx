// @vitest-environment jsdom
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

// jsdom does not implement the native <dialog> methods used by the shared Modal.
// Provide minimal behaviour so focus-management can be exercised in tests.
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function showModal() {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function close() {
    this.open = false;
    this.dispatchEvent(new Event('close'));
  };
});

vi.mock('../services/searchAdapter', async (importOriginal) => {
  const actual = await importOriginal();

  return {
    ...actual,
    fetchSearchResults: vi.fn(),
  };
});

import { fetchSearchResults } from '../services/searchAdapter.js';
import SearchResultsPage from './SearchResultsPage.jsx';

const provider = {
  id: 'prv-001',
  name: "Maya's Home Care",
  image: 'maya.png',
  isVerified: true,
  backgroundChecked: true,
  rating: 4.9,
  reviewCount: 128,
  category: 'Standard home clean',
  description: 'Reliable home cleaning with supplies included.',
  distanceText: '1.2 miles away',
  distanceMiles: 1.2,
  responseTimeText: 'Replies within 15 min',
  nextAvailable: 'Today, 2:00 PM',
  pricing: { hourlyRate: 45, minimumHours: 2, currency: '$', unit: 'hr' },
  inclusions: ['Supplies & equipment'],
  cancellationPolicy: 'Free up to 24 hours before; then 50% of booking',
  serviceRadiusMiles: 5,
  location: 'Austin, TX 78701',
  inComparison: false,
};

const fixtureResponse = {
  isLive: false,
  isFixture: true,
  results: [provider],
  totalCount: 1,
  page: 0,
  size: 12,
  pageCount: 1,
  unsupportedFilters: [],
};

function pagedResponse({ page = 0, totalCount = 25, size = 12 } = {}) {
  const pageCount = Math.max(0, totalCount - page * size);
  const results = Array.from(
    { length: Math.max(0, Math.min(size, pageCount)) },
    (_, index) => ({
      ...provider,
      id: `prv-page-${page}-${index}`,
      providerId: `prv-page-${page}-${index}`,
      name: `Provider ${page * size + index + 1}`,
      inComparison: false,
    }),
  );

  return {
    isLive: true,
    isFixture: false,
    results,
    totalCount,
    page,
    size,
    pageCount: Math.ceil(totalCount / size),
    unsupportedFilters: [],
  };
}

function renderSearchPage() {
  return render(
    <MemoryRouter initialEntries={['/search']}>
      <SearchResultsPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'true');
  fetchSearchResults.mockResolvedValue(fixtureResponse);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllEnvs();
});

describe('SearchResultsPage search lifecycle', () => {
  it('re-runs the request when an unchanged search is submitted again', async () => {
    const user = userEvent.setup();
    const { container } = renderSearchPage();

    await screen.findByText("Maya's Home Care");
    expect(fetchSearchResults).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(fetchSearchResults).toHaveBeenCalledTimes(2));
    await screen.findByText("Maya's Home Care");
    expect(container.querySelector('.skeleton-card')).toBeNull();
  });

  it('retries unchanged criteria after a failure', async () => {
    fetchSearchResults
      .mockRejectedValueOnce(new Error('Network Error'))
      .mockResolvedValueOnce(fixtureResponse);

    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText('Unable to Load Providers');
    await user.click(screen.getByRole('button', { name: 'Retry Search' }));

    await screen.findByText("Maya's Home Care");
    expect(fetchSearchResults).toHaveBeenCalledTimes(2);
  });

  it('re-runs the request when a visible filter changes', async () => {
    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText("Maya's Home Care");

    await user.click(screen.getByLabelText(/verified providers/i));

    await waitFor(() => expect(fetchSearchResults).toHaveBeenCalledTimes(2));
    const lastCall = fetchSearchResults.mock.calls.at(-1)[0];
    expect(lastCall.filters.verifiedOnly).toBe(false);
  });

  it('does not show stale results after a subsequent failed search', async () => {
    fetchSearchResults
      .mockResolvedValueOnce(fixtureResponse)
      .mockRejectedValueOnce(new Error('Network Error'));

    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText("Maya's Home Care");

    await user.click(screen.getByRole('button', { name: 'Search' }));

    await screen.findByText('Unable to Load Providers');
    expect(screen.queryByText("Maya's Home Care")).toBeNull();
  });
});

describe('SearchResultsPage clear all and reset', () => {
  it('Clear all removes every restriction instead of excluding providers', async () => {
    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText("Maya's Home Care");

    await user.click(screen.getByRole('button', { name: 'Clear all' }));

    await waitFor(() => expect(fetchSearchResults).toHaveBeenCalledTimes(2));
    const filters = fetchSearchResults.mock.calls.at(-1)[0].filters;
    expect(filters.standardClean).toBe(false);
    expect(filters.deepClean).toBe(false);
    expect(filters.moveOutClean).toBe(false);
    expect(filters.recurringClean).toBe(false);
    expect(filters.verifiedOnly).toBe(false);
    expect(filters.highRating).toBe(false);
    expect(filters.backgroundChecked).toBe(false);
    expect(filters.availability).toBe('any');
    expect(filters.budgetMax).toBe(80);
    expect(filters.distance).toBe('25');
  });

  it('Reset restores the default filters', async () => {
    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText("Maya's Home Care");

    await user.click(screen.getByRole('button', { name: 'Reset' }));

    await waitFor(() => expect(fetchSearchResults).toHaveBeenCalledTimes(2));
    const filters = fetchSearchResults.mock.calls.at(-1)[0].filters;
    expect(filters.standardClean).toBe(true);
    expect(filters.verifiedOnly).toBe(true);
    expect(filters.budgetMax).toBe(60);
    expect(filters.distance).toBe('5');
    expect(filters.availability).toBe('any');
  });
});

describe('SearchResultsPage live defaults', () => {
  it('does not send fixture query or location on the initial live search', async () => {
    vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'false');
    fetchSearchResults.mockResolvedValue(pagedResponse());

    renderSearchPage();

    await screen.findByText('Provider 1');
    expect(fetchSearchResults.mock.calls[0][0]).toEqual(
      expect.objectContaining({ query: '', location: '' }),
    );
  });

  it('disables the unsupported budget filter in live mode', async () => {
    vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'false');
    fetchSearchResults.mockResolvedValue(pagedResponse());

    renderSearchPage();

    await screen.findByText('Provider 1');
    expect(screen.getByLabelText(/hourly budget upper limit/i).disabled).toBe(true);
    expect(screen.getByText(/unavailable in live search/i)).toBeTruthy();
  });

  it('resets live query and location to empty values', async () => {
    vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'false');
    fetchSearchResults.mockResolvedValue(pagedResponse());
    const user = userEvent.setup();

    renderSearchPage();
    await screen.findByText('Provider 1');

    await user.type(screen.getByLabelText(/what do you need/i), 'Colombo');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(fetchSearchResults).toHaveBeenCalledTimes(2));

    await user.click(screen.getByRole('button', { name: 'Reset' }));
    await waitFor(() => expect(fetchSearchResults).toHaveBeenCalledTimes(3));
    expect(fetchSearchResults.mock.calls.at(-1)[0]).toEqual(
      expect.objectContaining({ query: '', location: '' }),
    );
  });

  it('keeps the original sample defaults when fixture mode is explicitly enabled', async () => {
    vi.stubEnv('VITE_SEARCH_FIXTURE_MODE', 'true');

    renderSearchPage();

    await screen.findByText("Maya's Home Care");
    expect(fetchSearchResults.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        query: 'Home cleaning',
        location: 'Austin, TX 78701',
      }),
    );
  });
});

describe('SearchResultsPage fixture labelling', () => {
  it('labels demo results when fixtures are served', async () => {
    renderSearchPage();
    const notice = await screen.findByRole('status');
    expect(notice.textContent).toMatch(/demo mode/i);
  });

  it('falls back to a placeholder when a provider image fails to load', async () => {
    renderSearchPage();

    const image = await screen.findByAltText(/maya's home care service presentation/i);
    fireEvent.error(image);

    expect(image.src).toMatch(/^data:image\/svg\+xml/);
  });
});

describe('SearchResultsPage profile navigation', () => {
  it('links each result to the matching provider profile ID', async () => {
    renderSearchPage();

    const profileLink = await screen.findByRole('link', {
      name: "View profile for Maya's Home Care",
    });

    expect(profileLink.getAttribute('href')).toBe('/providers/prv-001');
  });
});

describe('SearchResultsPage provider comparison', () => {
  const maya = { ...provider, inComparison: true };
  const brightNest = {
    ...provider,
    id: 'prv-002',
    name: 'BrightNest Cleaning',
    rating: 4.8,
    reviewCount: 96,
    distanceText: '2.4 miles away',
    distanceMiles: 2.4,
    responseTimeText: 'Replies within 30 min',
    nextAvailable: 'Tomorrow, 9:00 AM',
    pricing: { hourlyRate: 40, minimumHours: 2, currency: '$', unit: 'hr' },
    cancellationPolicy: 'Free up to 12 hours before; then 50% of booking',
    serviceRadiusMiles: 8,
    location: 'Austin, TX 78704',
    inComparison: true,
  };
  const neat = {
    ...provider,
    id: 'prv-003',
    name: 'Neat & Local',
    rating: 4.7,
    reviewCount: 84,
    distanceText: '3.1 miles away',
    distanceMiles: 3.1,
    responseTimeText: 'Replies within 1 hour',
    nextAvailable: 'Today, 4:30 PM',
    pricing: { hourlyRate: 50, minimumHours: 2, currency: '$', unit: 'hr' },
    serviceRadiusMiles: 5,
    location: 'Austin, TX 78702',
    inComparison: true,
  };
  const capitalCity = {
    ...provider,
    id: 'prv-004',
    name: 'Capital City Cleaners',
    isVerified: false,
    backgroundChecked: false,
    rating: 4.6,
    reviewCount: 42,
    category: 'Move-out cleaning',
    distanceText: '4.8 miles away',
    distanceMiles: 4.8,
    responseTimeText: 'Replies within 2 hours',
    nextAvailable: 'Thursday, 10:00 AM',
    pricing: { hourlyRate: 55, minimumHours: 3, currency: '$', unit: 'hr' },
    serviceRadiusMiles: 10,
    location: 'Austin, TX 78759',
    inComparison: false,
  };

  const comparisonResponse = {
    isLive: false,
    isFixture: true,
    results: [maya, brightNest],
    totalCount: 2,
  };

  const threeProviderResponse = {
    isLive: false,
    isFixture: true,
    results: [maya, brightNest, neat, capitalCity],
    totalCount: 4,
  };

  beforeEach(() => {
    fetchSearchResults.mockResolvedValue(comparisonResponse);
  });

  async function openComparisonModal() {
    const user = userEvent.setup();
    renderSearchPage();

    const compareButton = await screen.findByRole('button', { name: /compare \(2\)/i });
    await user.click(compareButton);

    const dialog = await screen.findByRole('dialog', { name: 'Compare your shortlist' });

    return { user, compareButton, dialog };
  }

  it('opens a side-by-side comparison with the approved fields', async () => {
    const { dialog } = await openComparisonModal();

    expect(
      within(dialog).getByText(
        '2 providers · Home cleaning in Austin · Choose the best fit for your home.',
      ),
    ).toBeTruthy();
    expect(within(dialog).getByText('Standard home clean')).toBeTruthy();
    expect(within(dialog).getByText('Within 5 miles')).toBeTruthy();
    expect(within(dialog).getByText('Compare up to 3 providers')).toBeTruthy();
    expect(within(dialog).getByRole('heading', { name: "Maya's Home Care" })).toBeTruthy();
    expect(within(dialog).getByRole('heading', { name: 'BrightNest Cleaning' })).toBeTruthy();
    expect(within(dialog).getByText('From $45/hr · 2-hour minimum')).toBeTruthy();
    expect(within(dialog).getByText('4.9/5')).toBeTruthy();
    expect(within(dialog).getByText('128 reviews')).toBeTruthy();
    expect(within(dialog).getAllByText('Identity + background checked')).toHaveLength(2);
    expect(within(dialog).getByText('Within 15 minutes')).toBeTruthy();
    expect(within(dialog).getByText('Today · 2:00 PM')).toBeTruthy();
    expect(within(dialog).getByText('1.2 miles · Austin, 5-mile radius')).toBeTruthy();
    expect(within(dialog).getAllByText('Supplies & equipment')).toHaveLength(2);
    expect(
      within(dialog).getByText('Free up to 24 hours before; then 50% of booking'),
    ).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: 'Choose Maya' })).toBeTruthy();
    expect(within(dialog).getByText(/Starting rates are indicative/i)).toBeTruthy();
  });

  it('dismisses with Close and restores focus to the Compare button', async () => {
    const { user, compareButton } = await openComparisonModal();

    await user.click(screen.getByRole('button', { name: 'Close dialog' }));

    await waitFor(() => expect(document.activeElement).toBe(compareButton));
  });

  it('dismisses with Escape and restores focus to the Compare button', async () => {
    const { compareButton, dialog } = await openComparisonModal();

    fireEvent(dialog, new Event('cancel', { bubbles: true, cancelable: true }));

    await waitFor(() => expect(document.activeElement).toBe(compareButton));
  });

  it('keeps booking unavailable while exposing working profile links', async () => {
    const { user, dialog } = await openComparisonModal();

    await user.click(within(dialog).getByRole('button', { name: 'Choose Maya' }));
    expect(within(dialog).getByRole('status').textContent).toMatch(
      /Booking is not available/i,
    );

    const profileLinks = within(dialog).getAllByRole('link', { name: 'View profile' });
    expect(profileLinks[0].getAttribute('href')).toBe('/providers/prv-001');
    expect(within(dialog).getAllByText(/Profile links are available/i).length).toBeGreaterThan(0);
    expect(within(dialog).queryByText(/profiles are not available/i)).toBeNull();
  });

  it('disables Compare until two providers are selected', async () => {
    fetchSearchResults.mockResolvedValue({
      ...comparisonResponse,
      results: [maya],
      totalCount: 1,
    });

    renderSearchPage();

    const compareButton = await screen.findByRole('button', { name: /compare \(1\)/i });

    expect(compareButton.disabled).toBe(true);
    expect(screen.getByText('Select at least 2 providers to compare.')).toBeTruthy();
  });

  it('removes a provider and moves focus to the next remove control', async () => {
    fetchSearchResults.mockResolvedValue(threeProviderResponse);

    const user = userEvent.setup();
    renderSearchPage();

    await user.click(await screen.findByRole('button', { name: /compare \(3\)/i }));
    const dialog = await screen.findByRole('dialog', { name: 'Compare your shortlist' });

    await user.click(
      within(dialog).getByRole('button', {
        name: "Remove Maya's Home Care from comparison",
      }),
    );

    expect(within(dialog).queryByRole('heading', { name: "Maya's Home Care" })).toBeNull();

    const nextRemove = within(dialog).getByRole('button', {
      name: 'Remove BrightNest Cleaning from comparison',
    });

    await waitFor(() => expect(document.activeElement).toBe(nextRemove));
  });

  it('shows the minimum state when removal leaves one provider', async () => {
    const { user, dialog } = await openComparisonModal();

    await user.click(
      within(dialog).getByRole('button', {
        name: "Remove Maya's Home Care from comparison",
      }),
    );

    expect(
      within(dialog).getByRole('heading', { name: 'Select one more provider' }),
    ).toBeTruthy();
    expect(within(dialog).getByText('BrightNest Cleaning')).toBeTruthy();

    const remainingRemove = within(dialog).getByRole('button', {
      name: 'Remove BrightNest Cleaning from comparison',
    });

    await waitFor(() => expect(document.activeElement).toBe(remainingRemove));
  });

  it('falls back to the results when closing with a disabled Compare trigger', async () => {
    const { user, dialog } = await openComparisonModal();

    await user.click(
      within(dialog).getByRole('button', {
        name: "Remove Maya's Home Care from comparison",
      }),
    );

    fireEvent(dialog, new Event('cancel', { bubbles: true, cancelable: true }));

    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('region', { name: 'Search results list' }),
      ),
    );
  });

  it('handles removal of the final provider and restores focus to the results', async () => {
    const { user, dialog } = await openComparisonModal();

    await user.click(
      within(dialog).getByRole('button', {
        name: "Remove Maya's Home Care from comparison",
      }),
    );
    await user.click(
      within(dialog).getByRole('button', {
        name: 'Remove BrightNest Cleaning from comparison',
      }),
    );

    expect(
      within(dialog).getByRole('heading', { name: 'No providers selected' }),
    ).toBeTruthy();

    const emptyStateAction = within(dialog).getByRole('button', {
      name: 'Return to search results',
    });

    await waitFor(() => expect(document.activeElement).toBe(emptyStateAction));

    await user.click(emptyStateAction);

    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('region', { name: 'Search results list' }),
      ),
    );
  });

  it('does not attribute the search location to providers without their own location', async () => {
    fetchSearchResults.mockResolvedValue({
      isLive: true,
      isFixture: false,
      results: [
        {
          ...provider,
          id: 'prv-loc-1',
          name: 'No Location One',
          location: undefined,
          serviceRadiusMiles: undefined,
          distanceText: '1.2 miles away',
          inComparison: true,
        },
        {
          ...provider,
          id: 'prv-loc-2',
          name: 'No Location Two',
          location: undefined,
          serviceRadiusMiles: undefined,
          distanceText: undefined,
          inComparison: true,
        },
      ],
      totalCount: 2,
    });

    const user = userEvent.setup();
    renderSearchPage();

    await user.click(await screen.findByRole('button', { name: /compare \(2\)/i }));
    const dialog = await screen.findByRole('dialog', { name: 'Compare your shortlist' });

    expect(
      within(dialog).getByText(
        '2 providers · Home cleaning in Austin · Choose the best fit for your home.',
      ),
    ).toBeTruthy();

    const firstProvider = within(dialog).getByRole('article', {
      name: 'No Location One',
    });
    expect(within(firstProvider).getByText('Home cleaning · Location not provided')).toBeTruthy();
    expect(within(firstProvider).getByText('1.2 miles')).toBeTruthy();
    expect(within(firstProvider).queryByText(/Austin/)).toBeNull();

    const secondProvider = within(dialog).getByRole('article', {
      name: 'No Location Two',
    });
    expect(within(secondProvider).getByText('Home cleaning · Location not provided')).toBeTruthy();
    expect(within(secondProvider).getByText('Not provided')).toBeTruthy();
    expect(within(secondProvider).queryByText(/Austin/)).toBeNull();
  });

  it('renders Not provided instead of inventing missing comparison data', async () => {
    fetchSearchResults.mockResolvedValue({
      isLive: true,
      isFixture: false,
      results: [
        {
          id: 'live-1',
          name: 'Sparse Provider One',
          image: 'one.png',
          inComparison: true,
        },
        {
          id: 'live-2',
          name: 'Sparse Provider Two',
          image: 'two.png',
          inComparison: true,
        },
      ],
      totalCount: 2,
    });

    const user = userEvent.setup();
    renderSearchPage();

    await user.click(await screen.findByRole('button', { name: /compare \(2\)/i }));
    const dialog = await screen.findByRole('dialog', { name: 'Compare your shortlist' });

    expect(within(dialog).getAllByText('Not provided').length).toBeGreaterThanOrEqual(7);
  });

  it('still blocks a fourth provider from being added', async () => {
    fetchSearchResults.mockResolvedValue(threeProviderResponse);

    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByTestId('provider-card-prv-004');

    const fourthCard = screen.getByTestId('provider-card-prv-004');
    await user.click(within(fourthCard).getByRole('checkbox'));

    expect(screen.getByText('Maximum 3 Providers Allowed')).toBeTruthy();
  });
});

describe('SearchResultsPage pagination', () => {
  it('requests the selected page and shows the actual result range', async () => {
    fetchSearchResults.mockImplementation(async ({ page }) => pagedResponse({ page }));

    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText('Provider 1');
    expect(fetchSearchResults).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0, size: 12 }),
    );
    expect(screen.getByText('Showing 1–12 of 25 providers')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Previous page' }).disabled).toBe(true);

    await user.click(screen.getByRole('button', { name: 'Next page' }));

    await screen.findByText('Provider 13');
    expect(fetchSearchResults).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, size: 12 }),
    );
    expect(screen.getByText('Showing 13–24 of 25 providers')).toBeTruthy();
    expect(screen.getByRole('button', { name: '2' }).getAttribute('aria-current')).toBe(
      'page',
    );
    expect(screen.getByRole('button', { name: 'Previous page' }).disabled).toBe(false);
  });

  it('bounds pagination controls for large result sets', async () => {
    fetchSearchResults.mockImplementation(async ({ page }) =>
      pagedResponse({ page, totalCount: 1200 }),
    );

    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText('Provider 1');
    const numberedButtons = () =>
      within(screen.getByRole('navigation', { name: 'Search pagination' }))
        .getAllByRole('button')
        .filter((button) => /^\d+$/.test(button.textContent.trim()));

    expect(numberedButtons().length).toBeLessThanOrEqual(7);
    expect(numberedButtons().map((button) => button.textContent.trim())).toEqual([
      '1',
      '2',
      '100',
    ]);

    await user.click(screen.getByRole('button', { name: 'Next page' }));

    await screen.findByText('Provider 13');
    expect(numberedButtons().map((button) => button.textContent.trim())).toEqual([
      '1',
      '2',
      '3',
      '100',
    ]);
    expect(screen.getByRole('button', { name: '2' }).getAttribute('aria-current')).toBe('page');
  });

  it('disables Next on the last page and shows the final partial range', async () => {
    fetchSearchResults.mockImplementation(async ({ page }) =>
      pagedResponse({ page, totalCount: 13 }),
    );

    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText('Provider 1');
    expect(screen.getByRole('button', { name: 'Next page' }).disabled).toBe(false);

    await user.click(screen.getByRole('button', { name: '2' }));

    await screen.findByText('Provider 13');
    expect(screen.getByText('Showing 13–13 of 13 providers')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Next page' }).disabled).toBe(true);
  });

  it('resets to page 0 when a new search is submitted', async () => {
    fetchSearchResults.mockImplementation(async ({ page }) => pagedResponse({ page }));

    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText('Provider 1');
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await screen.findByText('Provider 13');

    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() =>
      expect(fetchSearchResults).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 0 }),
      ),
    );
  });

  it('resets to page 0 when a filter changes', async () => {
    fetchSearchResults.mockImplementation(async ({ page }) => pagedResponse({ page }));

    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText('Provider 1');
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await screen.findByText('Provider 13');

    await user.click(screen.getByLabelText(/verified providers/i));

    await waitFor(() =>
      expect(fetchSearchResults).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 0, filters: expect.objectContaining({ verifiedOnly: false }) }),
      ),
    );
  });

  it('resets to page 0 on Clear all and Reset', async () => {
    fetchSearchResults.mockImplementation(async ({ page }) => pagedResponse({ page }));

    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText('Provider 1');
    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await screen.findByText('Provider 13');

    await user.click(screen.getByRole('button', { name: 'Clear all' }));
    await waitFor(() =>
      expect(fetchSearchResults).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 0 }),
      ),
    );
    await screen.findByText('Provider 1');

    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await screen.findByText('Provider 13');

    await user.click(screen.getByRole('button', { name: 'Reset' }));
    await waitFor(() =>
      expect(fetchSearchResults).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 0 }),
      ),
    );
  });

  it('ignores a delayed response from a superseded search', async () => {
    let resolveFirst;
    let resolveSecond;

    fetchSearchResults
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveFirst = () =>
              resolve({
                ...fixtureResponse,
                results: [{ ...provider, id: 'old', name: 'Old Provider' }],
              });
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveSecond = () =>
              resolve({
                ...fixtureResponse,
                results: [{ ...provider, id: 'new', name: 'New Provider' }],
              });
          }),
      );

    const user = userEvent.setup();
    renderSearchPage();

    await waitFor(() => expect(fetchSearchResults).toHaveBeenCalledTimes(1));
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(fetchSearchResults).toHaveBeenCalledTimes(2));

    await act(async () => {
      resolveSecond();
      await Promise.resolve();
    });
    await screen.findByText('New Provider');

    await act(async () => {
      resolveFirst();
      await Promise.resolve();
    });

    expect(screen.queryByText('Old Provider')).toBeNull();
    expect(screen.getByText('New Provider')).toBeTruthy();
  });

  it('retries with the active query, location, filters and current page', async () => {
    fetchSearchResults
      .mockImplementationOnce(async ({ page }) => pagedResponse({ page }))
      .mockRejectedValueOnce(new Error('Network Error'))
      .mockImplementation(async ({ page }) => pagedResponse({ page }));

    const user = userEvent.setup();
    renderSearchPage();

    await screen.findByText('Provider 1');
    await user.click(screen.getByRole('button', { name: 'Next page' }));

    await screen.findByText('Unable to Load Providers');
    fireEvent.change(screen.getByLabelText(/what do you need/i), {
      target: { value: 'Unsubmitted query' },
    });

    await user.click(screen.getByRole('button', { name: 'Retry Search' }));

    await screen.findByText('Provider 13');
    expect(fetchSearchResults).toHaveBeenLastCalledWith(
      expect.objectContaining({
        query: 'Home cleaning',
        location: 'Austin, TX 78701',
        page: 1,
        filters: expect.objectContaining({ verifiedOnly: true, distance: '5' }),
      }),
    );
  });

  it('shows a neutral note when live filters are not enforced', async () => {
    fetchSearchResults.mockResolvedValue({
      ...fixtureResponse,
      isLive: true,
      isFixture: false,
      unsupportedFilters: ['availability', 'minRating'],
    });

    renderSearchPage();

    const note = await screen.findByText(/does not enforce availability/i);
    expect(note.textContent).toMatch(/service type, rating, or budget filters/i);
    expect(note.textContent).toMatch(/may not reflect these preferences/i);
    expect(screen.queryByText(/demo mode/i)).toBeNull();
  });

  it('compares live listings independently when one provider has several listings', async () => {
    fetchSearchResults.mockResolvedValue({
      ...fixtureResponse,
      isLive: true,
      isFixture: false,
      results: [
        { ...provider, id: 'lst-1', listingId: 'lst-1', providerId: 'same-provider', name: 'First Listing' },
        { ...provider, id: 'lst-2', listingId: 'lst-2', providerId: 'same-provider', name: 'Second Listing' },
      ],
      totalCount: 2,
    });

    renderSearchPage();

    const resultsRegion = screen.getByRole('region', { name: 'Search results list' });
    const checkboxes = await within(resultsRegion).findAllByRole('checkbox');
    expect(checkboxes).toHaveLength(2);
    await userEvent.click(checkboxes[0]);
    expect(checkboxes[0].checked).toBe(true);
    expect(checkboxes[1].checked).toBe(false);
  });

  it('shows a verified live amount without inventing currency or unit', async () => {
    fetchSearchResults.mockResolvedValue({
      ...fixtureResponse,
      isLive: true,
      isFixture: false,
      results: [
        {
          id: 'legacy-id',
          providerId: 'canonical-id',
          name: 'Sparse Provider',
          image: 'sparse.png',
          pricing: {
            hourlyRate: null,
            amount: 55,
            model: 'FIXED',
            durationMinutes: 60,
            currency: null,
            unit: null,
          },
          inComparison: false,
        },
      ],
      totalCount: 1,
    });

    renderSearchPage();

    const amount = await screen.findByText('55');
    expect(amount.closest('.pricing-primary').textContent).toContain('From 55 (fixed price)');
    expect(screen.queryByText(/From \$45\/hr/)).toBeNull();
    expect(
      screen.getByRole('link', { name: 'View profile for Sparse Provider' }).getAttribute('href'),
    ).toBe('/providers/canonical-id');
  });
});
