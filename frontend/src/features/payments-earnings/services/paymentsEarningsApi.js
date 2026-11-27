import apiClient from '../../../shared/services/apiClient';

const EARNINGS_PATH = '/provider/earnings';
const PROVIDER_PAYOUTS_PATH = '/provider/payouts';
const ADMIN_PAYOUTS_PATH = '/admin/payouts';

/**
 * Retrieves the authenticated provider's earnings summary.
 */
export async function getProviderEarningsSummary() {
  const { data } = await apiClient.get(`${EARNINGS_PATH}/me`);
  return data;
}

/**
 * Retrieves the authenticated provider's paginated ledger history.
 *
 * Amounts represent individual ledger movements, not aggregated
 * booking-level earnings. Pagination uses zero-based page indexes.
 */
export async function getProviderEarningsTransactions(page = 0, size = 10) {
  const { data } = await apiClient.get(`${EARNINGS_PATH}/transactions`, {
    params: { page, size },
  });

  return data;
}

/**
 * Creates a payout request for the authenticated provider.
 *
 * The caller must preserve the same request key when retrying
 * the same logical payout request.
 */
export async function requestProviderPayout({ requestKey, amount }) {
  const { data } = await apiClient.post(PROVIDER_PAYOUTS_PATH, {
    requestKey,
    amount,
  });

  return data;
}

/**
 * Retrieves the authenticated provider's paginated payout history.
 * Backend pagination uses zero-based page indexes.
 */
export async function getProviderPayoutHistory(page = 0, size = 10) {
  const { data } = await apiClient.get(`${PROVIDER_PAYOUTS_PATH}/me`, {
    params: { page, size },
  });

  return data;
}

/**
 * Retrieves a payout owned by the authenticated provider.
 * Ownership is enforced by the backend.
 */
export async function getProviderPayoutDetails(payoutId) {
  const { data } = await apiClient.get(
    `${PROVIDER_PAYOUTS_PATH}/${encodeURIComponent(payoutId)}`,
  );

  return data;
}

/**
 * Retrieves global payout counts independently of list filters
 * and pagination.
 */
export async function getAdminPayoutSummary() {
  const { data } = await apiClient.get(
    `${ADMIN_PAYOUTS_PATH}/summary`,
  );

  return data;
}

/**
 * Retrieves paginated payout history for authorized admin users.
 *
 * Optional filters are applied by the backend across all payouts.
 * Search matches a payout ID or provider ID.
 * Date boundaries use an inclusive from and exclusive to timestamp.
 *
 * Existing callers may continue supplying only page and size.
 */
export async function getAdminPayoutHistory(
  page = 0,
  size = 10,
  filters = {},
) {
  const params = { page, size };

  const search = filters.search?.trim();

  if (search) {
    params.search = search;
  }

  if (filters.status) {
    params.status = filters.status;
  }

  if (filters.from) {
    params.from = filters.from;
  }

  if (filters.to) {
    params.to = filters.to;
  }

  const { data } = await apiClient.get(ADMIN_PAYOUTS_PATH, {
    params,
  });

  return data;
}

/**
 * Retrieves detailed payout information for authorized admin users.
 */
export async function getAdminPayoutDetails(payoutId) {
  const { data } = await apiClient.get(
    `${ADMIN_PAYOUTS_PATH}/${encodeURIComponent(payoutId)}`,
  );

  return data;
}

/**
 * Marks a payout as processing.
 * The backend enforces finance-area edit permissions and state rules.
 */
export async function markPayoutProcessing(payoutId) {
  const { data } = await apiClient.post(
    `${ADMIN_PAYOUTS_PATH}/${encodeURIComponent(payoutId)}/process`,
  );

  return data;
}

/**
 * Confirms that an external payout transfer has been completed.
 */
export async function markPayoutPaid(payoutId, transferReference) {
  const { data } = await apiClient.post(
    `${ADMIN_PAYOUTS_PATH}/${encodeURIComponent(payoutId)}/paid`,
    { transferReference },
  );

  return data;
}

/**
 * Marks a payout as failed with the supplied failure reason.
 */
export async function markPayoutFailed(payoutId, reason) {
  const { data } = await apiClient.post(
    `${ADMIN_PAYOUTS_PATH}/${encodeURIComponent(payoutId)}/failed`,
    { reason },
  );

  return data;
}

/**
 * Retrieves paginated booking-level earnings for the authenticated provider.
 * Net earnings represent financial entitlement, not withdrawable balance.
 *
 * @param {number} page Zero-based page number.
 * @param {number} size Number of records per page.
 * @returns {Promise<Object>} Paginated itemized earnings response.
 */
export async function getProviderItemizedEarnings(page = 0, size = 10) {
  const { data } = await apiClient.get(`${EARNINGS_PATH}/itemized`, {
    params: { page, size },
  });
  return data;
}
