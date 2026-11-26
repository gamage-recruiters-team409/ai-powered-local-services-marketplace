// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RecentBookings } from '../components/RecentBookings';
import * as customerAccountApi from '../services/customerAccountApi';

vi.mock('../services/customerAccountApi');

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('RecentBookings', () => {
  beforeEach(() => {
    customerAccountApi.getRecentBookings.mockResolvedValue({
      content: [],
      page: 0,
      size: 5,
      totalElements: 0,
      totalPages: 0,
    });
  });

  it('renders loading state initially', () => {
    customerAccountApi.getRecentBookings.mockReturnValue(new Promise(() => {}));
    render(<RecentBookings />);

    expect(screen.getByText(/loading recent bookings\.\.\./i)).toBeTruthy();
    expect(screen.getByRole('heading', { name: /recent bookings/i })).toBeTruthy();
  });

  it('renders rows with serviceTitle, ref, schedule, formatted price, and status badge', async () => {
    customerAccountApi.getRecentBookings.mockResolvedValueOnce({
      content: [
        {
          id: 'b-1',
          ref: 'BK-1001',
          serviceTitle: 'Plumbing Repair',
          status: 'CONFIRMED',
          localDate: '2026-11-28',
          localStart: '10:00',
          localEnd: '12:00',
          totalPrice: 4500,
          currency: 'LKR',
        },
        {
          id: 'b-2',
          ref: 'BK-1002',
          serviceTitle: null, // fallback to "Service booking"
          status: 'COMPLETED',
          localDate: '2026-11-20',
          localStart: null,
          localEnd: null,
          totalPrice: null, // show nothing for price
          currency: 'LKR',
        },
      ],
      page: 0,
      size: 5,
      totalElements: 2,
      totalPages: 1,
    });

    render(<RecentBookings />);

    await waitFor(() => {
      expect(screen.getByText('Plumbing Repair')).toBeTruthy();
    });

    // Row 1 checks
    const row1 = screen.getByText('BK-1001').closest('tr');
    expect(within(row1).getByText('Plumbing Repair')).toBeTruthy();
    expect(within(row1).getByText('2026-11-28, 10:00–12:00')).toBeTruthy();
    expect(within(row1).getByText('4,500 LKR')).toBeTruthy();
    expect(within(row1).getByText('Confirmed')).toBeTruthy();

    // Row 2 checks (fallback serviceTitle & null price)
    const row2 = screen.getByText('BK-1002').closest('tr');
    expect(within(row2).getByText('Service booking')).toBeTruthy();
    expect(within(row2).getByText('2026-11-20')).toBeTruthy();
    expect(within(row2).getByText('Completed')).toBeTruthy();
  });

  it('displays empty state when bookings list is empty', async () => {
    customerAccountApi.getRecentBookings.mockResolvedValueOnce({
      content: [],
      totalElements: 0,
    });

    render(<RecentBookings />);

    await waitFor(() => {
      expect(screen.getByText('You have no bookings yet.')).toBeTruthy();
    });
  });

  it('displays inline error message when API call fails without throwing', async () => {
    customerAccountApi.getRecentBookings.mockRejectedValueOnce(new Error('Network error on bookings'));

    render(<RecentBookings />);

    await waitFor(() => {
      expect(screen.getByText('Recent bookings are unavailable right now.')).toBeTruthy();
    });

    // Does not show empty state or table
    expect(screen.queryByText('You have no bookings yet.')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
  });
});
