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
 * Retrieves the paginated payout history for authorized admin users.
 */
export async function getAdminPayoutHistory(page = 0, size = 10) {
  const { data } = await apiClient.get(ADMIN_PAYOUTS_PATH, {
    params: { page, size },
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