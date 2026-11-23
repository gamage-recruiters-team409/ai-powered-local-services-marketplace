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

    // Check View Profile links to provider profile page
    const viewProfileLinks = screen.getAllByRole('link', { name: /view profile/i });
    expect(viewProfileLinks).toHaveLength(2);
    expect(viewProfileLinks[0].getAttribute('href')).toBe('/providers/prov-1');
    expect(viewProfileLinks[1].getAttribute('href')).toBe('/providers/prov-2');
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
      const bookBtn = screen.getByRole('button', { name: /book now/i });
      expect(bookBtn).toBeTruthy();
      expect(bookBtn.hasAttribute('disabled')).toBe(true);
      expect(bookBtn.getAttribute('title')).toBe('Booking is not available yet');
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

  it('page 2 with last item removed refetches, navigates to previous page, and shows remaining favorites rather than empty state', async () => {
    favouritesApi.removeFavourite.mockResolvedValueOnce();

    favouritesApi.getFavourites.mockImplementation((type, page) => {
      if (page === 0) {
        return Promise.resolve({
          content: [
            {
              favouriteId: 'fav-1',
              type: 'PROVIDER',
              targetId: 'prov-1',
              name: 'First Page Provider',
              rating: 4.8,
              city: 'Colombo',
              priceFrom: 3000,
              currency: 'LKR',
              inactive: false,
              imageUrl: null,
            },
          ],
          page: 0,
          size: 6,
          totalElements: 7,
          totalPages: 2,
        });
      }
      if (page === 1) {
        return Promise.resolve({
          content: [
            {
              favouriteId: 'fav-7',
              type: 'PROVIDER',
              targetId: 'prov-7',
              name: 'Second Page Lone Provider',
              rating: 4.5,
              city: 'Kandy',
              priceFrom: 4000,
              currency: 'LKR',
              inactive: false,
              imageUrl: null,
            },
          ],
          page: 1,
          size: 6,
          totalElements: 7,
          totalPages: 2,
        });
      }
      return Promise.resolve({ content: [], page: 0, size: 6, totalElements: 0, totalPages: 0 });
    });

    render(
      <MemoryRouter>
        <FavoritesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('First Page Provider')).toBeTruthy();
    });

    // Navigate to page 2 (Next >)
    const nextBtn = screen.getByRole('button', { name: /next page/i });
    fireEvent.click(nextBtn);

    await waitFor(() => {
      expect(screen.getByText('Second Page Lone Provider')).toBeTruthy();
    });

    // Next time getFavourites is called for page 1 (after removal), it returns empty content but totalElements: 6, totalPages: 1
    favouritesApi.getFavourites.mockImplementation((type, page) => {
      if (page === 1) {
        return Promise.resolve({
          content: [],
          page: 1,
          size: 6,
          totalElements: 6,
          totalPages: 1,
        });
      }
      if (page === 0) {
        return Promise.resolve({
          content: [
            {
              favouriteId: 'fav-1',
              type: 'PROVIDER',
              targetId: 'prov-1',
              name: 'First Page Provider',
              rating: 4.8,
              city: 'Colombo',
              priceFrom: 3000,
              currency: 'LKR',
              inactive: false,
              imageUrl: null,
            },
          ],
          page: 0,
          size: 6,
          totalElements: 6,
          totalPages: 1,
        });
      }
      return Promise.resolve({ content: [], page: 0, size: 6, totalElements: 0, totalPages: 0 });
    });

    // Remove the only favourite on page 2
    const removeBtn = screen.getByLabelText(/remove .* from favorites/i);
    fireEvent.click(removeBtn);

    await waitFor(() => {
      expect(favouritesApi.removeFavourite).toHaveBeenCalledWith('PROVIDER', 'prov-7');
    });

    // The UI should fall back to page 0, showing "First Page Provider", NOT the "No favorites yet" empty state
    await waitFor(() => {
      expect(screen.getByText('First Page Provider')).toBeTruthy();
      expect(screen.queryByText(/no favorites yet/i)).toBeNull();
    });
  });

  it('shows empty state after removing the only favourite overall', async () => {
    favouritesApi.removeFavourite.mockResolvedValueOnce();
    favouritesApi.getFavourites.mockResolvedValueOnce({
      content: [
        {
          favouriteId: 'fav-only',
          type: 'PROVIDER',
          targetId: 'prov-only',
          name: 'Sole Provider',
          rating: 4.8,
          city: 'Colombo',
          priceFrom: 3000,
          currency: 'LKR',
          inactive: false,
          imageUrl: null,
        },
      ],
      page: 0,
      size: 6,
      totalElements: 1,
      totalPages: 1,
    });

    render(
      <MemoryRouter>
        <FavoritesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Sole Provider')).toBeTruthy();
    });

    // When refetched after removal, server returns empty
    favouritesApi.getFavourites.mockResolvedValueOnce({
      content: [],
      page: 0,
      size: 6,
      totalElements: 0,
      totalPages: 0,
    });

    const removeBtn = screen.getByLabelText(/remove .* from favorites/i);
    fireEvent.click(removeBtn);

    await waitFor(() => {
      expect(favouritesApi.removeFavourite).toHaveBeenCalledWith('PROVIDER', 'prov-only');
      expect(screen.getByText(/no favorites yet/i)).toBeTruthy();
    });
  });

  it('removing one item from a page with several items keeps page and updates pagination summary', async () => {
    favouritesApi.removeFavourite.mockResolvedValueOnce();
    favouritesApi.getFavourites.mockResolvedValueOnce({
      content: [
        {
          favouriteId: 'fav-1',
          type: 'PROVIDER',
          targetId: 'prov-1',
          name: 'Provider One',
          rating: 4.8,
          city: 'Colombo',
          priceFrom: 3000,
          currency: 'LKR',
          inactive: false,
          imageUrl: null,
        },
        {
          favouriteId: 'fav-2',
          type: 'PROVIDER',
          targetId: 'prov-2',
          name: 'Provider Two',
          rating: 4.7,
          city: 'Gampaha',
          priceFrom: 2500,
          currency: 'LKR',
          inactive: false,
          imageUrl: null,
        },
      ],
      page: 0,
      size: 6,
      totalElements: 8,
      totalPages: 2,
    });

    render(
      <MemoryRouter>
        <FavoritesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Provider One')).toBeTruthy();
      expect(screen.getByText('Provider Two')).toBeTruthy();
      expect(screen.getByText(/showing 1-2 of 8 favorites/i)).toBeTruthy();
    });

    // Refetch returns updated totals
    favouritesApi.getFavourites.mockResolvedValueOnce({
      content: [
        {
          favouriteId: 'fav-2',
          type: 'PROVIDER',
          targetId: 'prov-2',
          name: 'Provider Two',
          rating: 4.7,
          city: 'Gampaha',
          priceFrom: 2500,
          currency: 'LKR',
          inactive: false,
          imageUrl: null,
        },
      ],
      page: 0,
      size: 6,
      totalElements: 7,
      totalPages: 2,
    });

    const removeBtns = screen.getAllByLabelText(/remove .* from favorites/i);
    fireEvent.click(removeBtns[0]);

    await waitFor(() => {
      expect(favouritesApi.removeFavourite).toHaveBeenCalledWith('PROVIDER', 'prov-1');
      expect(screen.getByText(/showing 1-1 of 7 favorites/i)).toBeTruthy();
      expect(screen.getByText('Provider Two')).toBeTruthy();
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

  it('disables Book now button on active listings with explanatory title and clicking does nothing', async () => {
    favouritesApi.getFavourites.mockImplementation((type) => {
      if (type === 'LISTING') {
        return Promise.resolve({
          content: [
            {
              favouriteId: 'fav-active',
              type: 'LISTING',
              targetId: 'list-active',
              name: 'Deep House Cleaning Service',
              subtitle: 'Clean Co',
              category: 'Cleaning',
              rating: 4.8,
              city: 'Colombo',
              priceFrom: 4500,
              currency: 'LKR',
              inactive: false,
              imageUrl: null,
            },
          ],
          page: 0,
          size: 6,
          totalElements: 1,
          totalPages: 1,
        });
      }
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
      expect(screen.getByText('Deep House Cleaning Service')).toBeTruthy();
    });

    const bookBtn = screen.getByRole('button', { name: /book now/i });
    expect(bookBtn).toBeTruthy();
    expect(bookBtn.hasAttribute('disabled')).toBe(true);
    expect(bookBtn.getAttribute('aria-disabled')).toBe('true');
    expect(bookBtn.getAttribute('title')).toBe('Booking is not available yet');

    // Clicking disabled button does not trigger errors or change page state
    fireEvent.click(bookBtn);
    expect(screen.getByText('Deep House Cleaning Service')).toBeTruthy();
  });

  it('disables Book now button on inactive listings with inactive title', async () => {
    favouritesApi.getFavourites.mockImplementation((type) => {
      if (type === 'LISTING') {
        return Promise.resolve({
          content: [
            {
              favouriteId: 'fav-inactive',
              type: 'LISTING',
              targetId: 'list-inactive',
              name: 'Old Garden Care Package',
              subtitle: 'Old Provider',
              category: 'Gardening',
              rating: 3.9,
              city: 'Kandy',
              priceFrom: 2000,
              currency: 'LKR',
              inactive: true,
              imageUrl: null,
            },
          ],
          page: 0,
          size: 6,
          totalElements: 1,
          totalPages: 1,
        });
      }
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
      expect(screen.getByText('Old Garden Care Package')).toBeTruthy();
    });

    // Check Inactive badge
    expect(screen.getByText('Inactive')).toBeTruthy();

    const bookBtn = screen.getByRole('button', { name: /book now/i });
    expect(bookBtn).toBeTruthy();
    expect(bookBtn.hasAttribute('disabled')).toBe(true);
    expect(bookBtn.getAttribute('aria-disabled')).toBe('true');
    expect(bookBtn.getAttribute('title')).toBe('This listing is inactive');

    fireEvent.click(bookBtn);
    expect(screen.getByText('Old Garden Care Package')).toBeTruthy();
  });

  it('renders View profile links with correct href for active and inactive providers', async () => {
    render(
      <MemoryRouter>
        <FavoritesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Nuwan Perera - AC Solutions')).toBeTruthy();
      expect(screen.getByText('Lanka Lawn & Garden Care')).toBeTruthy();
    });

    const links = screen.getAllByTestId('Link_ViewProfile');
    expect(links).toHaveLength(2);
    expect(links[0].getAttribute('href')).toBe('/providers/prov-1');
    expect(links[1].getAttribute('href')).toBe('/providers/prov-2');
  });
});
