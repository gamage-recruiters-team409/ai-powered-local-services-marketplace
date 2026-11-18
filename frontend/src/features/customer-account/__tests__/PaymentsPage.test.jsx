// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PaymentsPage } from '../pages/PaymentsPage';
import * as paymentsApi from '../services/paymentsApi';

vi.mock('../services/paymentsApi');

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const mockPaymentsResponse = {
  content: [
    {
      paymentId: 'pay-1',
      bookingId: 'bk-1042',
      amount: 6500,
      currency: 'LKR',
      status: 'CAPTURED',
      refundedAmount: 0,
      createdAt: '2026-10-12T10:00:00Z',
    },
    {
      paymentId: 'pay-2',
      bookingId: 'bk-1038',
      amount: 12000,
      currency: 'LKR',
      status: 'REFUND_PENDING',
      refundedAmount: 12000,
      createdAt: '2026-09-28T14:30:00Z',
    },
    {
      paymentId: 'pay-3',
      bookingId: 'bk-1027',
      amount: 9500,
      currency: 'LKR',
      status: 'PARTIALLY_REFUNDED',
      refundedAmount: 3000,
      createdAt: '2026-09-02T09:15:00Z',
    },
    {
      paymentId: 'pay-4',
      bookingId: 'bk-1019',
      amount: 15200,
      currency: 'LKR',
      status: 'FAILED',
      refundedAmount: 0,
      createdAt: '2026-08-18T16:00:00Z',
    },
    {
      paymentId: 'pay-5',
      bookingId: 'bk-1050',
      amount: 4800,
      currency: 'LKR',
      status: 'INITIATED',
      refundedAmount: 0,
      createdAt: '2026-08-01T12:00:00Z',
    },
  ],
  page: 0,
  size: 5,
  totalElements: 14,
  totalPages: 3,
};

describe('PaymentsPage', () => {
  it('renders empty state when there are no payments', async () => {
    paymentsApi.getMyPayments.mockResolvedValueOnce({
      content: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    });

    render(
      <MemoryRouter>
        <PaymentsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/no payments yet/i)).toBeTruthy();
      expect(screen.getByRole('link', { name: /browse services/i }).getAttribute('href')).toBe('/search');
    });
  });

  it('renders payment rows with correct status mappings and disabled links', async () => {
    paymentsApi.getMyPayments.mockResolvedValueOnce(mockPaymentsResponse);

    render(
      <MemoryRouter>
        <PaymentsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/#BK-1042/i)).toBeTruthy();
      expect(screen.getByText(/#BK-1038/i)).toBeTruthy();
      expect(screen.getByText(/#BK-1027/i)).toBeTruthy();
      expect(screen.getByText(/#BK-1019/i)).toBeTruthy();
    });

    // Payment status mappings
    expect(screen.getAllByText('Paid').length).toBeGreaterThan(0);
    expect(screen.getByText('Partially refunded')).toBeTruthy();
    expect(screen.getByText('Failed')).toBeTruthy();
    expect(screen.getByText('Pending')).toBeTruthy();

    // Refund status mappings
    expect(screen.getByText('Processing')).toBeTruthy();
    expect(screen.getByText(/requested 12,000 lkr/i)).toBeTruthy();
    expect(screen.getByText(/refunded 3,000 of 9,500 lkr/i)).toBeTruthy();

    // Disabled links with correct testid and title
    const receiptBtns = screen.getAllByTestId('Link_ViewReceipt');
    expect(receiptBtns[0].hasAttribute('disabled')).toBe(true);
    expect(receiptBtns[0].getAttribute('title')).toBe('Not available yet');

    const refundDetailBtns = screen.getAllByTestId('Link_ViewRefundDetails');
    expect(refundDetailBtns[0].hasAttribute('disabled')).toBe(true);
    expect(refundDetailBtns[0].getAttribute('title')).toBe('Not available yet');

    // Pagination summary
    expect(screen.getByText(/showing 1-5 of 14 payments/i)).toBeTruthy();
  });

  it('navigates to next page on pagination click', async () => {
    paymentsApi.getMyPayments.mockResolvedValue(mockPaymentsResponse);

    render(
      <MemoryRouter>
        <PaymentsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/#BK-1042/i)).toBeTruthy();
    });

    const nextBtn = screen.getByRole('button', { name: /next page/i });
    fireEvent.click(nextBtn);

    await waitFor(() => {
      expect(paymentsApi.getMyPayments).toHaveBeenCalledWith(1, 10);
    });
  });
});
