// @vitest-environment jsdom
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/providerProfileAdapter', async (importOriginal) => {
  const actual = await importOriginal();

  return {
    ...actual,
    fetchProviderProfile: vi.fn(),
  };
});

import { MOCK_SEARCH_RESULTS } from '../fixtures/mockSearchResults.js';
import {
  ProviderProfileNotFoundError,
  ProviderProfileUnavailableError,
  fetchProviderProfile,
} from '../services/providerProfileAdapter.js';
import ProviderProfilePage from './ProviderProfilePage.jsx';

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function showModal() {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function close() {
    this.open = false;
    this.dispatchEvent(new Event('close'));
  };
});

const maya = MOCK_SEARCH_RESULTS[0];
const brightNest = MOCK_SEARCH_RESULTS[1];

function fixtureResponse(provider) {
  return {
    isFixture: true,
    isLive: false,
    provider,
  };
}

function liveResponse(provider) {
  return {
    isFixture: false,
    isLive: true,
    provider,
  };
}

function renderProfile(path = '/providers/prv-001') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/providers/:providerId"
          element={<ProviderProfilePage />}
        />
        <Route path="/search" element={<div>Search page</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

function ProviderSwitchControls() {
  const navigate = useNavigate();

  return (
    <button
      type="button"
      onClick={() => navigate('/providers/user-b?tab=reviews')}
    >
      Switch provider
    </button>
  );
}

function renderProfileWithSwitch(path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ProviderSwitchControls />
      <Routes>
        <Route
          path="/providers/:providerId"
          element={<ProviderProfilePage />}
        />
        <Route path="/search" element={<div>Search page</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  fetchProviderProfile.mockResolvedValue(fixtureResponse(maya));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllEnvs();
});

describe('ProviderProfilePage provider summary', () => {
  it('renders the provider identity, rating, badges, and booking summary', async () => {
    renderProfile();

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: "Maya's Home Care",
      }),
    ).toBeTruthy();
    expect(screen.getByRole('status').textContent).toMatch(/demo mode/i);
    expect(screen.getByText('Identity verified')).toBeTruthy();
    expect(screen.getByText('Background checked')).toBeTruthy();
    expect(screen.getByText('Top rated')).toBeTruthy();
    expect(screen.getAllByText('4.9/5').length).toBeGreaterThan(0);
    expect(screen.getByText('128 reviews')).toBeTruthy();
    expect(
      screen.getByText('From $45/hr · 2-hour minimum'),
    ).toBeTruthy();
    expect(screen.getByText('Today · 2:00 PM')).toBeTruthy();
    expect(screen.getByText('Booking summary')).toBeTruthy();
  });

  it('renders the overview content by default', async () => {
    renderProfile('/providers/prv-001?tab=unknown');

    expect(
      await screen.findByRole('heading', {
        name: "About Maya's Home Care",
      }),
    ).toBeTruthy();
    expect(screen.getByText(/reliable home cleaning/i)).toBeTruthy();
    expect(screen.getAllByText('Supplies & equipment').length).toBeGreaterThan(
      0,
    );
    expect(screen.getByRole('link', { name: 'Read reviews' })).toBeTruthy();
  });

  it('falls back to the placeholder image when the provider image fails', async () => {
    renderProfile();

    const image = await screen.findByAltText(
      /maya's home care service presentation/i,
    );
    fireEvent.error(image);

    expect(image.src).toMatch(/^data:image\/svg\+xml/);
  });
});

describe('ProviderProfilePage rating display', () => {
  async function renderRating(rating, reviewCount = 10) {
    fetchProviderProfile.mockResolvedValueOnce(
      fixtureResponse({ ...maya, rating, reviewCount }),
    );
    renderProfile();

    await screen.findByRole('heading', {
      level: 1,
      name: "Maya's Home Care",
    });

    return screen.getByTestId('provider-rating-stars');
  }

  function getStarStates(stars) {
    return within(stars)
      .getAllByTestId('provider-rating-star')
      .map((star) => star.dataset.state);
  }

  it('reflects low ratings without showing five filled stars', async () => {
    const stars = await renderRating(2);

    expect(stars.dataset.rating).toBe('2');
    expect(getStarStates(stars)).toEqual([
      'full',
      'full',
      'empty',
      'empty',
      'empty',
    ]);
  });

  it('renders a partial star for intermediate ratings', async () => {
    const stars = await renderRating(3.5);

    expect(getStarStates(stars)).toEqual([
      'full',
      'full',
      'full',
      'partial',
      'empty',
    ]);
    expect(
      within(stars).getAllByTestId('provider-rating-star')[3].dataset
        .fillPercent,
    ).toBe('50');
  });

  it('renders a partial final star for high ratings', async () => {
    const stars = await renderRating(4.9, 128);

    expect(getStarStates(stars)).toEqual([
      'full',
      'full',
      'full',
      'full',
      'partial',
    ]);
    expect(
      within(stars).getAllByTestId('provider-rating-star')[4].dataset
        .fillPercent,
    ).toBe('90');
  });

  it('renders empty stars for a zero-review rating', async () => {
    const stars = await renderRating(0, 0);

    expect(getStarStates(stars)).toEqual([
      'empty',
      'empty',
      'empty',
      'empty',
      'empty',
    ]);
    expect(screen.getByText('No reviews yet')).toBeTruthy();
  });
});

describe('ProviderProfilePage service presentation', () => {
  it('shows provider-level details once and package fallbacks when service data is absent', async () => {
    fetchProviderProfile.mockResolvedValueOnce(fixtureResponse(brightNest));
    renderProfile('/providers/prv-002?tab=services');

    const panel = await screen.findByRole('region', {
      name: 'Services & packages',
    });

    expect(
      within(panel).getAllByText('From $40/hr · 2-hour minimum'),
    ).toHaveLength(1);
    expect(within(panel).getAllByText('Supplies & equipment')).toHaveLength(1);
    expect(
      within(panel).getAllByText('Package price not provided'),
    ).toHaveLength(2);
    expect(
      within(panel).getAllByText('Package description not provided.'),
    ).toHaveLength(2);
    expect(
      within(panel).getAllByText('Package-specific inclusions not provided.'),
    ).toHaveLength(2);
  });

  it('renders live service cards from actual listings with authoritative details', async () => {
    const liveProvider = {
      ...maya,
      providerId: 'user-1',
      category: 'Electrical',
      serviceTypes: [
        'Electrical',
        'Wiring Repair',
        'Painting',
        'Interior Painting',
      ],
      listings: [
        {
          id: 'listing-1',
          title: 'Wiring Repair',
          description: 'Licensed electrical repair.',
          categoryName: 'Electrical',
          pricing: { amount: 3000, model: 'FIXED' },
        },
        {
          id: 'listing-2',
          title: 'Interior Painting',
          description: 'Two-room painting package.',
          categoryName: 'Painting',
          pricing: { amount: 1500, model: 'FIXED' },
        },
      ],
    };

    fetchProviderProfile.mockResolvedValueOnce(liveResponse(liveProvider));
    renderProfile('/providers/user-1?tab=services');

    const panel = await screen.findByRole('region', {
      name: 'Services & packages',
    });
    const cards = await within(panel).findAllByRole('article');

    expect(cards).toHaveLength(2);
    expect(within(panel).getByText('Wiring Repair')).toBeTruthy();
    expect(within(panel).getByText('Interior Painting')).toBeTruthy();
    expect(within(panel).getByText('Licensed electrical repair.')).toBeTruthy();
    expect(within(panel).getByText('Two-room painting package.')).toBeTruthy();
    expect(within(panel).getByText('From 3000 (fixed price)')).toBeTruthy();
    expect(within(panel).getByText('From 1500 (fixed price)')).toBeTruthy();
  });

  it('renders package-specific details only when supplied', async () => {
    const providerWithPackages = {
      ...brightNest,
      serviceTypes: [
        {
          type: 'standard',
          label: 'Standard clean',
          description: 'Standard package description.',
          pricing: {
            hourlyRate: 38,
            minimumHours: 2,
            currency: '$',
            unit: 'hr',
          },
          inclusions: ['Eco supplies'],
        },
        {
          type: 'deep',
          label: 'Deep clean',
          description: 'Deep package description.',
          inclusions: ['Heavy-duty equipment', 'Supplies'],
        },
      ],
    };

    fetchProviderProfile.mockResolvedValueOnce(
      fixtureResponse(providerWithPackages),
    );
    renderProfile('/providers/prv-002?tab=services');

    const panel = await screen.findByRole('region', {
      name: 'Services & packages',
    });

    expect(within(panel).getByText('Standard clean')).toBeTruthy();
    expect(within(panel).getByText('Deep clean')).toBeTruthy();
    expect(
      within(panel).getByText('Standard package description.'),
    ).toBeTruthy();
    expect(within(panel).getByText('Deep package description.')).toBeTruthy();
    expect(
      within(panel).getByText('From $38/hr · 2-hour minimum'),
    ).toBeTruthy();
    expect(within(panel).getByText('Package includes: Eco supplies')).toBeTruthy();
    expect(
      within(panel).getByText('Package includes: Heavy-duty equipment, Supplies'),
    ).toBeTruthy();
    expect(
      within(panel).getAllByText('Package price not provided'),
    ).toHaveLength(1);
    expect(
      within(panel).queryAllByText('Package-specific inclusions not provided.'),
    ).toHaveLength(0);
  });
});

describe('ProviderProfilePage tabs', () => {
  it('switches between URL-synced services, reviews, and availability views', async () => {
    const user = userEvent.setup();
    renderProfile('/providers/prv-001?tab=services');

    expect(
      await screen.findByRole('heading', { name: 'Services & packages' }),
    ).toBeTruthy();
    expect(
      screen.getByRole('heading', { name: 'Standard home clean' }),
    ).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Request Standard home clean' }),
    ).toBeTruthy();

    await user.click(screen.getByRole('link', { name: /Reviews/ }));

    expect(
      await screen.findByRole('heading', {
        name: 'What customers are saying',
      }),
    ).toBeTruthy();
    expect(
      screen.getByText(/individual review content is not available/i),
    ).toBeTruthy();
    expect(
      screen.getByRole('link', { name: /Reviews/ }).getAttribute(
        'aria-current',
      ),
    ).toBe('page');

    await user.click(screen.getByRole('link', { name: 'Availability' }));

    expect(
      await screen.findByRole('heading', {
        name: 'Find a time that works',
      }),
    ).toBeTruthy();
    expect(screen.getByText('Preview only')).toBeTruthy();
    expect(
      screen.getByText(/full slot preview is not available/i),
    ).toBeTruthy();
  });
});

describe('ProviderProfilePage public review content', () => {
  it('renders only the returned public review fields and links to more pages', async () => {
    fetchProviderProfile.mockResolvedValueOnce(liveResponse({
      ...maya,
      providerId: 'user-1',
      name: 'Review Provider',
      reviewCount: 12,
      reviews: [{
        id: 'rev-1',
        rating: 5,
        text: 'Excellent work',
        createdAt: '2026-11-20T10:15:30Z',
        providerReply: { text: 'Thank you', repliedAt: '2026-11-21T09:00:00Z' },
        customerName: 'Private Customer',
        bookingId: 'booking-private',
      }],
      reviewContentStatus: 'AVAILABLE',
      reviewPage: 0,
      reviewPageSize: 5,
      reviewTotalElements: 12,
      reviewTotalPages: 3,
    }));
    renderProfile('/providers/user-1?tab=reviews');

    const panel = await screen.findByRole('region', {
      name: 'What customers are saying',
    });

    expect(within(panel).getByText('Excellent work')).toBeTruthy();
    expect(within(panel).getByText('Provider reply: Thank you')).toBeTruthy();
    expect(within(panel).getByText('2026-11-20')).toBeTruthy();
    expect(within(panel).queryByText(/private customer/i)).toBeNull();
    // Page 0 of 3 — only "Next page" is shown; no "Previous page" on first page
    expect(within(panel).getByRole('link', { name: 'Next page' })).toBeTruthy();
    expect(within(panel).queryByRole('link', { name: 'Previous page' })).toBeNull();
  });

  it('keeps the usable profile visible and offers a review retry when content fails', async () => {
    const unavailableProvider = {
      ...maya,
      providerId: 'user-2',
      name: 'Retry Provider',
      reviewCount: 9,
      reviews: [],
      reviewContentStatus: 'UNAVAILABLE',
      reviewPage: 0,
      reviewTotalElements: 0,
      reviewTotalPages: 0,
    };
    fetchProviderProfile
      .mockResolvedValueOnce(liveResponse(unavailableProvider))
      .mockResolvedValueOnce(liveResponse(unavailableProvider));
    const user = userEvent.setup();
    renderProfile('/providers/user-2?tab=reviews');

    expect(
      await screen.findByRole('heading', { name: 'Retry Provider' }),
    ).toBeTruthy();
    const panel = screen.getByRole('region', {
      name: 'What customers are saying',
    });
    expect(within(panel).getByText(/temporarily unavailable/i)).toBeTruthy();
    await user.click(within(panel).getByRole('button', { name: 'Retry reviews' }));

    await waitFor(() => {
      expect(fetchProviderProfile).toHaveBeenCalledTimes(2);
    });
    expect(fetchProviderProfile).toHaveBeenNthCalledWith(1, 'user-2', 0);
    expect(fetchProviderProfile).toHaveBeenNthCalledWith(2, 'user-2', 0);
  });

  it('does not render a stale profile after the provider changes', async () => {
    let resolveFirstProfile;
    const firstProvider = {
      ...maya,
      providerId: 'user-a',
      name: 'First Provider',
      reviews: [],
    };
    const secondProvider = {
      ...maya,
      providerId: 'user-b',
      name: 'Second Provider',
      reviews: [],
    };
    fetchProviderProfile
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveFirstProfile = resolve;
      }))
      .mockResolvedValueOnce(liveResponse(secondProvider));
    const user = userEvent.setup();
    renderProfileWithSwitch('/providers/user-a?tab=reviews');

    await user.click(screen.getByRole('button', { name: 'Switch provider' }));
    await act(async () => {
      resolveFirstProfile(liveResponse(firstProvider));
    });

    expect(
      await screen.findByRole('heading', { name: 'Second Provider' }),
    ).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'First Provider' })).toBeNull();
    expect(fetchProviderProfile).toHaveBeenNthCalledWith(1, 'user-a', 0);
    expect(fetchProviderProfile).toHaveBeenNthCalledWith(2, 'user-b', 0);
  });
});

describe('ProviderProfilePage review pagination navigation', () => {
  function makeReviewPage(page, totalPages, text = 'A review') {
    return liveResponse({
      ...maya,
      providerId: 'paged-prov',
      name: 'Paged Provider',
      reviewCount: totalPages * 5,
      reviews: [{
        id: `rev-p${page}`,
        rating: 4,
        text,
        createdAt: '2026-11-01T08:00:00Z',
        providerReply: null,
      }],
      reviewContentStatus: 'AVAILABLE',
      reviewPage: page,
      reviewPageSize: 5,
      reviewTotalElements: totalPages * 5,
      reviewTotalPages: totalPages,
    });
  }

  it('navigates from page 0 to page 1 and shows Previous page on page 1', async () => {
    fetchProviderProfile
      .mockResolvedValueOnce(makeReviewPage(0, 3, 'Page zero review'))
      .mockResolvedValueOnce(makeReviewPage(1, 3, 'Page one review'));

    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/providers/paged-prov?tab=reviews']}>
        <Routes>
          <Route path="/providers/:providerId" element={<ProviderProfilePage />} />
        </Routes>
      </MemoryRouter>,
    );

    // Page 0: Next page shown, no Previous page
    const nextLink = await screen.findByRole('link', { name: 'Next page' });
    expect(nextLink.getAttribute('href')).toContain('reviewPage=1');
    expect(screen.queryByRole('link', { name: 'Previous page' })).toBeNull();

    await user.click(nextLink);

    // Page 1: Both Previous and Next page shown (3 total pages)
    expect(
      await screen.findByRole('link', { name: 'Previous page' }),
    ).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Next page' })).toBeTruthy();
    expect(screen.getByText('Page one review')).toBeTruthy();
    expect(fetchProviderProfile).toHaveBeenNthCalledWith(1, 'paged-prov', 0);
    expect(fetchProviderProfile).toHaveBeenNthCalledWith(2, 'paged-prov', 1);
  });

  it('shows no Next page on the final page and Previous page navigates back', async () => {
    fetchProviderProfile
      .mockResolvedValueOnce(makeReviewPage(1, 2, 'Last page review'))
      .mockResolvedValueOnce(makeReviewPage(0, 2, 'First page review'));

    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/providers/paged-prov?tab=reviews&reviewPage=1']}>
        <Routes>
          <Route path="/providers/:providerId" element={<ProviderProfilePage />} />
        </Routes>
      </MemoryRouter>,
    );

    // Page 1 (final of 2): Previous shown, Next hidden
    const prevLink = await screen.findByRole('link', { name: 'Previous page' });
    expect(prevLink.getAttribute('href')).not.toContain('reviewPage');
    expect(screen.queryByRole('link', { name: 'Next page' })).toBeNull();

    await user.click(prevLink);

    // Page 0: only Next shown
    expect(
      await screen.findByRole('link', { name: 'Next page' }),
    ).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Previous page' })).toBeNull();
    expect(screen.getByText('First page review')).toBeTruthy();
    expect(fetchProviderProfile).toHaveBeenNthCalledWith(1, 'paged-prov', 1);
    expect(fetchProviderProfile).toHaveBeenNthCalledWith(2, 'paged-prov', 0);
  });

  it('renders a later page directly from a URL without showing next on the last page', async () => {
    fetchProviderProfile.mockResolvedValueOnce(makeReviewPage(2, 3, 'Third page review'));

    render(
      <MemoryRouter initialEntries={['/providers/paged-prov?tab=reviews&reviewPage=2']}>
        <Routes>
          <Route path="/providers/:providerId" element={<ProviderProfilePage />} />
        </Routes>
      </MemoryRouter>,
    );

    // Page 2 (final of 3): Previous shown, no Next
    expect(
      await screen.findByRole('link', { name: 'Previous page' }),
    ).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Next page' })).toBeNull();
    expect(screen.getByText('Third page review')).toBeTruthy();
    expect(fetchProviderProfile).toHaveBeenCalledWith('paged-prov', 2);
  });

  it('clamps a malformed reviewPage URL parameter to 0', async () => {
    fetchProviderProfile.mockResolvedValueOnce(makeReviewPage(0, 2, 'Clamped page review'));

    render(
      <MemoryRouter initialEntries={['/providers/paged-prov?tab=reviews&reviewPage=not-a-number']}>
        <Routes>
          <Route path="/providers/:providerId" element={<ProviderProfilePage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(
      await screen.findByText('Clamped page review'),
    ).toBeTruthy();
    expect(fetchProviderProfile).toHaveBeenCalledWith('paged-prov', 0);
  });
});

describe('ProviderProfilePage states', () => {
  it('shows a not-found state and returns to search', async () => {
    fetchProviderProfile.mockRejectedValueOnce(
      new ProviderProfileNotFoundError('missing'),
    );

    const user = userEvent.setup();
    renderProfile('/providers/missing');

    expect(
      await screen.findByRole('heading', { name: 'Provider not found' }),
    ).toBeTruthy();
    expect(
      screen.getByText(/couldn't find a provider with ID "missing"/i),
    ).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Back to search' }));

    expect(await screen.findByText('Search page')).toBeTruthy();
  });

  it('shows an unavailable state in live mode without fixture content', async () => {
    fetchProviderProfile.mockRejectedValueOnce(
      new ProviderProfileUnavailableError(),
    );

    renderProfile();

    expect(
      await screen.findByRole('heading', {
        name: 'Provider profiles are not available yet',
      }),
    ).toBeTruthy();
    expect(screen.queryByText("Maya's Home Care")).toBeNull();
    expect(screen.queryByText(/demo mode/i)).toBeNull();
  });

  it('retries the same provider after an unexpected error', async () => {
    const consoleError = vi
      .spyOn(console, 'error')
      .mockImplementation(() => {});

    fetchProviderProfile
      .mockRejectedValueOnce(new Error('Network Error'))
      .mockResolvedValueOnce(fixtureResponse(maya));

    const user = userEvent.setup();
    renderProfile();

    expect(
      await screen.findByRole('heading', {
        name: 'Could not load provider profile',
      }),
    ).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: "Maya's Home Care",
      }),
    ).toBeTruthy();
    expect(fetchProviderProfile).toHaveBeenCalledTimes(2);
    expect(fetchProviderProfile).toHaveBeenLastCalledWith('prv-001', 0);

    consoleError.mockRestore();
  });

  it('does not let a delayed previous provider overwrite the current profile', async () => {
    let resolveMaya;
    let resolveBrightNest;

    fetchProviderProfile.mockImplementation((providerId) => {
      if (providerId === 'prv-001') {
        return new Promise((resolve) => {
          resolveMaya = resolve;
        });
      }

      return new Promise((resolve) => {
        resolveBrightNest = resolve;
      });
    });

    function ProfileRouteHarness() {
      const navigate = useNavigate();

      return (
        <>
          <button
            type="button"
            onClick={() => navigate('/providers/prv-002')}
          >
            Go to BrightNest
          </button>
          <ProviderProfilePage />
        </>
      );
    }

    render(
      <MemoryRouter initialEntries={['/providers/prv-001']}>
        <Routes>
          <Route
            path="/providers/:providerId"
            element={<ProfileRouteHarness />}
          />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(fetchProviderProfile).toHaveBeenCalledWith('prv-001', 0),
    );

    const user = userEvent.setup();
    await user.click(
      screen.getByRole('button', { name: 'Go to BrightNest' }),
    );

    await waitFor(() =>
      expect(fetchProviderProfile).toHaveBeenCalledWith('prv-002', 0),
    );

    await act(async () => {
      resolveBrightNest(fixtureResponse(brightNest));
    });

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'BrightNest Cleaning',
      }),
    ).toBeTruthy();

    await act(async () => {
      resolveMaya(fixtureResponse(maya));
      await Promise.resolve();
    });

    expect(
      screen.getByRole('heading', {
        level: 1,
        name: 'BrightNest Cleaning',
      }),
    ).toBeTruthy();
    expect(screen.queryByText("Maya's Home Care")).toBeNull();
  });
});

describe('ProviderProfilePage pending actions', () => {
  it('labels booking and messaging as unavailable and restores focus', async () => {
    const user = userEvent.setup();
    renderProfile();

    const bookingButton = await screen.findByRole('button', {
      name: 'Request booking',
    });
    await user.click(bookingButton);

    const bookingDialog = await screen.findByRole('dialog', {
      name: 'Booking is not available yet',
    });
    expect(
      within(bookingDialog).getByText(/provider booking and scheduling/i),
    ).toBeTruthy();

    await user.click(
      within(bookingDialog).getByRole('button', { name: 'Understood' }),
    );

    await waitFor(() =>
      expect(document.activeElement).toBe(bookingButton),
    );

    await user.click(
      screen.getByRole('button', { name: 'Message provider' }),
    );

    expect(
      await screen.findByRole('dialog', {
        name: 'Messaging is not available yet',
      }),
    ).toBeTruthy();
  });
});
