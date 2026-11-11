// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

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
});

describe('SearchResultsPage fixture labelling', () => {
  it('labels demo results when fixtures are served', async () => {
    render(<SearchResultsPage />);
    const notice = await screen.findByRole('status');
    expect(notice.textContent).toMatch(/demo mode/i);
  });
});
