// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesPage } from '../pages/FavoritesPage';
import * as favouritesApi from '../services/favouritesApi';

vi.mock('../services/favouritesApi');

const mockToast = {
  success: vi.fn(),
  error: vi.fn(),
};
vi.mock('../../../shared/components/feedback/useToast', () => ({
  useToast: () => mockToast,
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const sampleProviders = {
  content: [
    {
      favouriteId: 'fav-1',
      type: 'PROVIDER',
      targetId: 'prov-1',
      name: 'Nuwan Perera - AC Solutions',
      rating: 4.9,
      reviewCount: 124,
      city: 'Colombo',
      priceFrom: 3500,
      currency: 'LKR',
      inactive: false,
      imageUrl: 'https://img.local/nuwan.jpg',
    },
    {
      favouriteId: 'fav-2',
      type: 'PROVIDER',
      targetId: 'prov-2',
      name: 'Lanka Lawn & Garden Care',
      rating: 4.6,
      reviewCount: 52,
      city: 'Gampaha',
      priceFrom: 2000,
      currency: 'LKR',
      inactive: true,
      imageUrl: null,
    },
  ],
  page: 0,
  size: 6,
  totalElements: 2,
  totalPages: 1,
};

const sampleListings = {
  content: [
    {
      favouriteId: 'fav-3',
      type: 'LISTING',
      targetId: 'list-1',
      name: 'Split AC Servicing and Gas Refill',
      subtitle: 'Nuwan Perera',
      category: 'AC Repair',
      rating: 4.9,
      reviewCount: 124,
      city: 'Colombo',
      priceFrom: 3500,
      priceUnit: 'fixed',
      currency: 'LKR',
      inactive: false,
      imageUrl: 'https://img.local/ac.jpg',
    },
  ],
  page: 0,
  size: 6,
  totalElements: 1,
  totalPages: 1,
};

describe('FavoritesPage', () => {
  beforeEach(() => {
    favouritesApi.getFavourites.mockImplementation((type) => {
      if (type === 'PROVIDER') return Promise.resolve(sampleProviders);
      if (type === 'LISTING') return Promise.resolve(sampleListings);
      return Promise.resolve({ content: [], page: 0, size: 6, totalElements: 0, totalPages: 0 });
    });
  });

  it('renders provider favourites grid by default', async () => {
    render(
      <MemoryRouter>
        <FavoritesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Nuwan Perera - AC Solutions')).toBeTruthy();
      expect(screen.getByText('Lanka Lawn & Garden Care')).toBeTruthy();
    });

    // Check inactive badge on second card
    expect(screen.getByText('Inactive')).toBeTruthy();

    // Check disabled View Profile button
    const viewProfileBtns = screen.getAllByRole('button', { name: /view profile/i });
    expect(viewProfileBtns[0].hasAttribute('disabled')).toBe(true);
    expect(viewProfileBtns[0].getAttribute('title')).toBe('Not available yet');
  });

  it('switches to listings tab and renders listing cards', async () => {
    render(
      <MemoryRouter>
        <FavoritesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Nuwan Perera - AC Solutions')).toBeTruthy();
    });

    const listingsTab = screen.getByRole('tab', { name: /listings/i });
    fireEvent.click(listingsTab);

    await waitFor(() => {
      expect(screen.getByText('Split AC Servicing and Gas Refill')).toBeTruthy();
      expect(screen.getByText(/by nuwan perera/i)).toBeTruthy();
      expect(screen.getByText('AC Repair')).toBeTruthy();
      expect(screen.getByRole('button', { name: /book now/i })).toBeTruthy();
    });
  });

  it('shows friendly unavailable state when listings return 503 FAVOURITES_LISTING_UNAVAILABLE', async () => {
    const error503 = new Error('Listings unavailable');
    error503.status = 503;
    error503.code = 'FAVOURITES_LISTING_UNAVAILABLE';
    favouritesApi.getFavourites.mockImplementation((type) => {
      if (type === 'LISTING') return Promise.reject(error503);
      return Promise.resolve(sampleProviders);
    });

    render(
      <MemoryRouter>
        <FavoritesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Nuwan Perera - AC Solutions')).toBeTruthy();
    });

    fireEvent.click(screen.getByRole('tab', { name: /listings/i }));

    await waitFor(() => {
      expect(screen.getByText(/listings are not available yet/i)).toBeTruthy();
    });

    // Providers tab must still work
    fireEvent.click(screen.getByRole('tab', { name: /providers/i }));
    await waitFor(() => {
      expect(screen.getByText('Nuwan Perera - AC Solutions')).toBeTruthy();
    });
  });

  it('removes favourite with optimistic update and rollback on failure', async () => {
    favouritesApi.removeFavourite.mockRejectedValueOnce(new Error('Network failure'));

    render(
      <MemoryRouter>
        <FavoritesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Nuwan Perera - AC Solutions')).toBeTruthy();
    });

    const removeBtns = screen.getAllByLabelText(/remove .* from favorites/i);
    fireEvent.click(removeBtns[0]);

    // Optimistically removed
    await waitFor(() => {
      expect(favouritesApi.removeFavourite).toHaveBeenCalledWith('PROVIDER', 'prov-1');
    });

    // Due to rejection, it should rollback and show error toast
    await waitFor(() => {
      expect(mockToast.error).toHaveBeenCalledWith('Network failure');
      expect(screen.getByText('Nuwan Perera - AC Solutions')).toBeTruthy();
    });
  });

  it('renders empty state when there are no favorites', async () => {
    favouritesApi.getFavourites.mockResolvedValueOnce({
      content: [],
      page: 0,
      size: 6,
      totalElements: 0,
      totalPages: 0,
    });

    render(
      <MemoryRouter>
        <FavoritesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/no favorites yet/i)).toBeTruthy();
      expect(screen.getByRole('link', { name: /browse services/i }).getAttribute('href')).toBe('/search');
    });
  });
});
