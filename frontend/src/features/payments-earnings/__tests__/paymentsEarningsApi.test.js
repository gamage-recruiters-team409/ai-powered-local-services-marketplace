import { describe, it, expect, vi, beforeEach } from 'vitest';

import apiClient from '../../../shared/services/apiClient';

import {
  getProviderEarningsSummary,
  getProviderEarningsTransactions,
  getProviderItemizedEarnings,
  requestProviderPayout,
  getProviderPayoutHistory,
  getProviderPayoutDetails,
  getAdminPayoutSummary,
  getAdminPayoutHistory,
  getAdminPayoutDetails,
  markPayoutProcessing,
  markPayoutPaid,
  markPayoutFailed,
} from '../services/paymentsEarningsApi';

vi.mock('../../../shared/services/apiClient');

describe('paymentsEarningsApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  // --------------------------------------------------
  // Provider earnings
  // --------------------------------------------------

  it('retrieves the authenticated provider earnings summary', async () => {
    const mockData = {
      currency: 'LKR',
      pending: 2000,
      available: 15000,
      held: 1000,
      paidOut: 5000,
    };

    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getProviderEarningsSummary();

    expect(apiClient.get).toHaveBeenCalledWith(
      '/provider/earnings/me',
    );
    expect(result).toEqual(mockData);
  });

  // --------------------------------------------------
  // Provider earnings transaction history
  // --------------------------------------------------

  it('retrieves earnings transactions with default pagination', async () => {
    const mockData = {
      content: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    };

    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getProviderEarningsTransactions();

    expect(apiClient.get).toHaveBeenCalledExactlyOnceWith(
      '/provider/earnings/transactions',
      { params: { page: 0, size: 10 } },
    );

    expect(result).toEqual(mockData);
  });

  it('retrieves earnings transactions with custom pagination', async () => {
    const mockData = {
      content: [],
      page: 2,
      size: 20,
      totalElements: 40,
      totalPages: 2,
    };

    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getProviderEarningsTransactions(2, 20);

    expect(apiClient.get).toHaveBeenCalledExactlyOnceWith(
      '/provider/earnings/transactions',
      { params: { page: 2, size: 20 } },
    );

    expect(result).toEqual(mockData);
  });

  it('preserves verified ledger transaction response fields', async () => {
    const mockData = {
      content: [
        {
          transactionId: 'transaction-001',
          paymentId: 'payment-001',
          bookingId: 'booking-001',
          eventType: 'EARNING_PENDING',
          amount: 4500.25,
          currency: 'LKR',
          sequenceNumber: 5,
          createdAt: '2026-11-26T10:00:00Z',
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    };

    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getProviderEarningsTransactions();

    expect(result).toBe(mockData);
  });

  it('propagates earnings transaction retrieval errors', async () => {
    const error = new Error('Unable to retrieve earnings transactions');

    apiClient.get.mockRejectedValueOnce(error);

    await expect(
      getProviderEarningsTransactions(),
    ).rejects.toBe(error);
  });

  // --------------------------------------------------
  // Provider payout operations
  // --------------------------------------------------

  it('submits a provider payout request with the correct payload', async () => {
    const payload = {
      requestKey: 'request-001',
      amount: 5000,
    };

    const mockData = {
      id: 'payout-001',
      ...payload,
      currency: 'LKR',
      status: 'PENDING',
    };

    apiClient.post.mockResolvedValueOnce({ data: mockData });

    const result = await requestProviderPayout(payload);

    expect(apiClient.post).toHaveBeenCalledWith(
      '/provider/payouts',
      payload,
    );
    expect(result).toEqual(mockData);
  });

  it('retrieves paginated provider payout history', async () => {
    const mockData = {
      content: [{ id: 'payout-001', status: 'PENDING' }],
      page: 1,
      size: 5,
      totalElements: 6,
      totalPages: 2,
    };

    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getProviderPayoutHistory(1, 5);

    expect(apiClient.get).toHaveBeenCalledWith(
      '/provider/payouts/me',
      { params: { page: 1, size: 5 } },
    );
    expect(result).toEqual(mockData);
  });

  it('retrieves provider payout details by payout ID', async () => {
    const mockData = {
      id: 'payout-001',
      amount: 5000,
      currency: 'LKR',
      status: 'PENDING',
    };

    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getProviderPayoutDetails('payout-001');

    expect(apiClient.get).toHaveBeenCalledWith(
      '/provider/payouts/payout-001',
    );
    expect(result).toEqual(mockData);
  });

  // --------------------------------------------------
  // Finance admin payout operations
  // --------------------------------------------------

  it('retrieves global admin payout status counts', async () => {
    const summary = {
      pending: 6,
      processing: 12,
      paid: 148,
      failed: 3,
    };

    apiClient.get.mockResolvedValueOnce({ data: summary });

    const result = await getAdminPayoutSummary();

    expect(apiClient.get).toHaveBeenCalledWith(
      '/admin/payouts/summary',
    );
    expect(result).toEqual(summary);
  });

  it('preserves zero counts returned by the summary API', async () => {
    const summary = {
      pending: 0,
      processing: 0,
      paid: 0,
      failed: 0,
    };

    apiClient.get.mockResolvedValueOnce({ data: summary });

    expect(await getAdminPayoutSummary()).toEqual(summary);
  });

  it('sends all supported admin payout filters', async () => {
    const filters = {
      search: '  provider-001  ',
      status: 'PENDING',
      from: '2026-11-01T00:00:00Z',
      to: '2026-12-01T00:00:00Z',
    };

    const response = {
      content: [],
      page: 1,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    };

    apiClient.get.mockResolvedValueOnce({ data: response });

    const result = await getAdminPayoutHistory(1, 10, filters);

    expect(apiClient.get).toHaveBeenCalledWith(
      '/admin/payouts',
      {
        params: {
          page: 1,
          size: 10,
          search: 'provider-001',
          status: 'PENDING',
          from: filters.from,
          to: filters.to,
        },
      },
    );

    expect(result).toEqual(response);
  });

  it('omits empty admin payout filters', async () => {
    apiClient.get.mockResolvedValueOnce({ data: {} });

    await getAdminPayoutHistory(0, 10, {
      search: '   ',
      status: '',
      from: null,
      to: undefined,
    });

    expect(apiClient.get).toHaveBeenCalledWith(
      '/admin/payouts',
      { params: { page: 0, size: 10 } },
    );
  });

  it('supports an independent upper date boundary', async () => {
    const to = '2026-12-01T00:00:00Z';

    apiClient.get.mockResolvedValueOnce({ data: {} });

    await getAdminPayoutHistory(0, 10, { to });

    expect(apiClient.get).toHaveBeenCalledWith(
      '/admin/payouts',
      { params: { page: 0, size: 10, to } },
    );
  });

  it('propagates admin payout summary access errors', async () => {
    const error = Object.assign(
      new Error('Access denied'),
      { status: 403 },
    );

    apiClient.get.mockRejectedValueOnce(error);

    await expect(getAdminPayoutSummary()).rejects.toBe(error);
  });

  it('retrieves paginated admin payout history', async () => {
    const mockData = {
      content: [
        {
          id: 'payout-001',
          providerId: 'provider-001',
          status: 'PENDING',
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    };

    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getAdminPayoutHistory(0, 20);

    expect(apiClient.get).toHaveBeenCalledWith(
      '/admin/payouts',
      { params: { page: 0, size: 20 } },
    );
    expect(result).toEqual(mockData);
  });

  it('retrieves admin payout details by payout ID', async () => {
    const mockData = {
      id: 'payout-001',
      providerId: 'provider-001',
      amount: 5000,
      currency: 'LKR',
      status: 'PENDING',
    };

    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getAdminPayoutDetails('payout-001');

    expect(apiClient.get).toHaveBeenCalledWith(
      '/admin/payouts/payout-001',
    );
    expect(result).toEqual(mockData);
  });

  it('marks a payout as processing', async () => {
    const mockData = {
      id: 'payout-001',
      status: 'PROCESSING',
    };

    apiClient.post.mockResolvedValueOnce({ data: mockData });

    const result = await markPayoutProcessing('payout-001');

    expect(apiClient.post).toHaveBeenCalledWith(
      '/admin/payouts/payout-001/process',
    );
    expect(result).toEqual(mockData);
  });

  it('marks a payout as paid with its transfer reference', async () => {
    const mockData = {
      id: 'payout-001',
      status: 'PAID',
      transferReference: 'BANK-TRANSFER-001',
    };

    apiClient.post.mockResolvedValueOnce({ data: mockData });

    const result = await markPayoutPaid(
      'payout-001',
      'BANK-TRANSFER-001',
    );

    expect(apiClient.post).toHaveBeenCalledWith(
      '/admin/payouts/payout-001/paid',
      { transferReference: 'BANK-TRANSFER-001' },
    );
    expect(result).toEqual(mockData);
  });

  it('marks a payout as failed with its failure reason', async () => {
    const mockData = {
      id: 'payout-001',
      status: 'FAILED',
      failureReason: 'Bank transfer rejected',
    };

    apiClient.post.mockResolvedValueOnce({ data: mockData });

    const result = await markPayoutFailed(
      'payout-001',
      'Bank transfer rejected',
    );

    expect(apiClient.post).toHaveBeenCalledWith(
      '/admin/payouts/payout-001/failed',
      { reason: 'Bank transfer rejected' },
    );
    expect(result).toEqual(mockData);
  });

  // --------------------------------------------------
  // Pagination defaults
  // --------------------------------------------------

  it('uses default zero-based provider payout pagination', async () => {
    apiClient.get.mockResolvedValueOnce({ data: {} });

    await getProviderPayoutHistory();

    expect(apiClient.get).toHaveBeenCalledWith(
      '/provider/payouts/me',
      { params: { page: 0, size: 10 } },
    );
  });

  it('uses default zero-based admin payout pagination', async () => {
    apiClient.get.mockResolvedValueOnce({ data: {} });

    await getAdminPayoutHistory();

    expect(apiClient.get).toHaveBeenCalledWith(
      '/admin/payouts',
      { params: { page: 0, size: 10 } },
    );
  });

  // --------------------------------------------------
  // URL encoding and request key preservation
  // --------------------------------------------------

  it('encodes payout IDs in provider and admin endpoint paths', async () => {
    const payoutId = 'payout/001?ref=2';
    const encodedId = encodeURIComponent(payoutId);

    apiClient.get.mockResolvedValue({ data: {} });
    apiClient.post.mockResolvedValue({ data: {} });

    await getProviderPayoutDetails(payoutId);
    await getAdminPayoutDetails(payoutId);
    await markPayoutProcessing(payoutId);
    await markPayoutPaid(payoutId, 'TRANSFER-001');
    await markPayoutFailed(payoutId, 'Transfer rejected');

    expect(apiClient.get).toHaveBeenNthCalledWith(
      1,
      `/provider/payouts/${encodedId}`,
    );

    expect(apiClient.get).toHaveBeenNthCalledWith(
      2,
      `/admin/payouts/${encodedId}`,
    );

    expect(apiClient.post).toHaveBeenNthCalledWith(
      1,
      `/admin/payouts/${encodedId}/process`,
    );

    expect(apiClient.post).toHaveBeenNthCalledWith(
      2,
      `/admin/payouts/${encodedId}/paid`,
      { transferReference: 'TRANSFER-001' },
    );

    expect(apiClient.post).toHaveBeenNthCalledWith(
      3,
      `/admin/payouts/${encodedId}/failed`,
      { reason: 'Transfer rejected' },
    );
  });

  it('preserves the supplied request key when retrying a payout request', async () => {
    const payload = {
      requestKey: 'stable-request-key',
      amount: 7500,
    };

    const networkError = new Error('Network timeout');

    apiClient.post
      .mockRejectedValueOnce(networkError)
      .mockResolvedValueOnce({
        data: {
          id: 'payout-002',
          status: 'PENDING',
        },
      });

    await expect(
      requestProviderPayout(payload),
    ).rejects.toBe(networkError);

    const result = await requestProviderPayout(payload);

    expect(apiClient.post).toHaveBeenCalledTimes(2);

    expect(apiClient.post).toHaveBeenNthCalledWith(
      1,
      '/provider/payouts',
      payload,
    );

    expect(apiClient.post).toHaveBeenNthCalledWith(
      2,
      '/provider/payouts',
      payload,
    );

    expect(result.id).toBe('payout-002');
  });

  // --------------------------------------------------
  // Error propagation
  // --------------------------------------------------

  it('propagates provider API errors without replacing them', async () => {
    const error = Object.assign(
      new Error('Authentication required'),
      { status: 401 },
    );

    apiClient.get.mockRejectedValueOnce(error);

    await expect(
      getProviderEarningsSummary(),
    ).rejects.toBe(error);
  });

  it('propagates forbidden admin operation errors', async () => {
    const error = Object.assign(
      new Error('Access denied'),
      { status: 403 },
    );

    apiClient.post.mockRejectedValueOnce(error);

    await expect(
      markPayoutProcessing('payout-001'),
    ).rejects.toBe(error);
  });
});


describe("getProviderItemizedEarnings", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("requests itemized earnings with default pagination", async () => {
    apiClient.get.mockResolvedValueOnce({ data: { content: [] } });

    await getProviderItemizedEarnings();

    expect(apiClient.get).toHaveBeenCalledTimes(1);
    expect(apiClient.get).toHaveBeenCalledWith(
      "/provider/earnings/itemized",
      { params: { page: 0, size: 10 } },
    );
  });

  it("supports custom pagination", async () => {
    apiClient.get.mockResolvedValueOnce({ data: { content: [] } });

    await getProviderItemizedEarnings(2, 20);

    expect(apiClient.get).toHaveBeenCalledWith(
      "/provider/earnings/itemized",
      { params: { page: 2, size: 20 } },
    );
  });

  it("returns the backend response without modifying financial values", async () => {
    const response = {
      content: [
        {
          paymentId: "payment-001",
          bookingId: "booking-001",
          grossAmount: 10000,
          commissionRate: 0.1,
          commissionAmount: 1000,
          refundedAmount: 2000,
          refundAdjustmentAmount: 1800,
          netEarnings: 7200,
          currency: "LKR",
          recognizedAt: "2026-11-26T10:00:00Z",
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    };

    apiClient.get.mockResolvedValueOnce({ data: response });

    expect(await getProviderItemizedEarnings()).toBe(response);
  });

  it("propagates API errors without replacing them", async () => {
    const error = new Error("Unable to retrieve itemized earnings");

    apiClient.get.mockRejectedValueOnce(error);

    await expect(getProviderItemizedEarnings()).rejects.toBe(error);
  });
});
