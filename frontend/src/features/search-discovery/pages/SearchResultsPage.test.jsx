// @vitest-environment jsdom
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

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

vi.mock('../services/searchAdapter', () => ({
  fetchSearchResults: vi.fn(),
}));

import { fetchSearchResults } from '../services/searchAdapter.js';
import SearchResultsPage from './SearchResultsPage.jsx';

const provider = {
  id: 'prv-001',
  name: "Maya's Home Care",
  image: 'maya.png',
  isVerified: true,
  rating: 4.9,
  reviewCount: 128,
  description: 'Reliable home cleaning with supplies included.',
  distanceText: '1.2 miles away',
  responseTimeText: 'Replies within 15 min',
  nextAvailable: 'Today, 2:00 PM',
  pricing: { hourlyRate: 45, minimumHours: 2, currency: '$', unit: 'hr' },
  inComparison: false,
};

const fixtureResponse = {
  isLive: false,
  isFixture: true,
  results: [provider],
  totalCount: 1,
};

beforeEach(() => {
  fetchSearchResults.mockResolvedValue(fixtureResponse);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('SearchResultsPage search lifecycle', () => {
  it('re-runs the request when an unchanged search is submitted again', async () => {
    const user = userEvent.setup();
    const { container } = render(<SearchResultsPage />);

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
    render(<SearchResultsPage />);

    await screen.findByText('Unable to Load Providers');
    await user.click(screen.getByRole('button', { name: 'Retry Search' }));

    await screen.findByText("Maya's Home Care");
    expect(fetchSearchResults).toHaveBeenCalledTimes(2);
  });

  it('re-runs the request when a visible filter changes', async () => {
    const user = userEvent.setup();
    render(<SearchResultsPage />);

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
    render(<SearchResultsPage />);

    await screen.findByText("Maya's Home Care");

    await user.click(screen.getByRole('button', { name: 'Search' }));

    await screen.findByText('Unable to Load Providers');
    expect(screen.queryByText("Maya's Home Care")).toBeNull();
  });
});

describe('SearchResultsPage clear all and reset', () => {
  it('Clear all removes every restriction instead of excluding providers', async () => {
    const user = userEvent.setup();
    render(<SearchResultsPage />);

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
    render(<SearchResultsPage />);

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

describe('SearchResultsPage fixture labelling', () => {
  it('labels demo results when fixtures are served', async () => {
    render(<SearchResultsPage />);
    const notice = await screen.findByRole('status');
    expect(notice.textContent).toMatch(/demo mode/i);
  });

  it('falls back to a placeholder when a provider image fails to load', async () => {
    render(<SearchResultsPage />);

    const image = await screen.findByAltText(/maya's home care service presentation/i);
    fireEvent.error(image);

    expect(image.src).toMatch(/^data:image\/svg\+xml/);
  });
});

describe('SearchResultsPage comparison modal', () => {
  const comparisonResponse = {
    isLive: false,
    isFixture: true,
    results: [
      { ...provider, inComparison: true },
      { ...provider, id: 'prv-002', name: 'BrightNest Cleaning', inComparison: true },
    ],
    totalCount: 2,
  };

  beforeEach(() => {
    fetchSearchResults.mockResolvedValue(comparisonResponse);
  });

  async function openComparisonModal() {
    const user = userEvent.setup();
    render(<SearchResultsPage />);

    const compareButton = await screen.findByRole('button', { name: /compare \(2\)/i });
    await user.click(compareButton);

    return { user, compareButton };
  }

  it('dismisses with Understood and restores focus to the Compare button', async () => {
    const { user, compareButton } = await openComparisonModal();

    expect(screen.getByText('Side-by-Side Comparison')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Understood' }));

    await waitFor(() => expect(document.activeElement).toBe(compareButton));
  });

  it('dismisses with Escape and restores focus to the Compare button', async () => {
    const { compareButton } = await openComparisonModal();

    const dialog = screen.getByRole('dialog');
    fireEvent(dialog, new Event('cancel', { bubbles: true, cancelable: true }));

    await waitFor(() => expect(document.activeElement).toBe(compareButton));
  });
});
