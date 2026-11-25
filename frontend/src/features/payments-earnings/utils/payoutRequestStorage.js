
/**
 * WP07 - Provider Payout Request Persistence
 *
 * Preserves unresolved payout requests across page navigation,
 * browser refreshes and browser restarts.
 *
 * The backend remains responsible for payout authorization,
 * financial validation and idempotency enforcement.
 *
 * Only the request key and requested amount are persisted.
 * Authentication tokens and bank details must never be stored here.
 */

const STORAGE_PREFIX = 'localserve.wp07.payout.pending.v1';

const MINIMUM_PAYOUT_AMOUNT = 5000;

const MAXIMUM_PAYOUT_AMOUNT = 999999999999.99;

/**
 * Builds an account-specific storage key.
 *
 * @param {string} providerId Authenticated provider's user ID.
 * @returns {string} Provider-specific storage key.
 * @throws {Error} If the authenticated provider ID is unavailable.
 */
function getStorageKey(providerId) {
  if (
    typeof providerId !== 'string' ||
    providerId.trim().length === 0
  ) {
    throw new Error(
      'Authenticated provider identity is required.',
    );
  }

  return `${STORAGE_PREFIX}:${encodeURIComponent(providerId)}`;
}

/**
 * Returns browser storage without silently ignoring access failures.
 *
 * Financial requests must not proceed when their recovery information
 * cannot be persisted reliably.
 *
 * @returns {Storage} Browser local storage.
 */
function getStorage() {
  if (typeof window === 'undefined') {
    throw new Error('Browser storage is unavailable.');
  }

  return window.localStorage;
}

/**
 * Validates a persisted payout payload against the backend contract.
 *
 * @param {object} payload Payout request payload.
 * @returns {boolean} Whether the payload is structurally valid.
 */
function isValidPayload(payload) {
  if (!payload || typeof payload !== 'object') {
    return false;
  }

  const { requestKey, amount } = payload;

  const validRequestKey =
    typeof requestKey === 'string' &&
    requestKey.trim().length > 0 &&
    requestKey.length <= 128;

  const validAmount =
    typeof amount === 'number' &&
    Number.isFinite(amount) &&
    amount >= MINIMUM_PAYOUT_AMOUNT &&
    amount <= MAXIMUM_PAYOUT_AMOUNT &&
    /^\d{1,12}(\.\d{1,2})?$/.test(String(amount));

  return validRequestKey && validAmount;
}

/**
 * Restores an unresolved payout request for the authenticated provider.
 *
 * Invalid or corrupted records are not automatically deleted because
 * the original financial request may already have reached the server.
 *
 * @param {string} providerId Authenticated provider's user ID.
 * @returns {{requestKey: string, amount: number} | null}
 * Stored payout request, or null when no request exists.
 */
export function loadPendingPayoutRequest(providerId) {
  const storageKey = getStorageKey(providerId);
  const rawValue = getStorage().getItem(storageKey);

  if (rawValue === null) {
    return null;
  }

  let payload;

  try {
    payload = JSON.parse(rawValue);
  } catch {
    throw new Error(
      'Stored payout recovery data is corrupted. ' +
        'Review your payout history and contact support before submitting another request.',
    );
  }

  if (!isValidPayload(payload)) {
    throw new Error(
      'Stored payout recovery data is invalid. ' +
        'Review your payout history and contact support before submitting another request.',
    );
  }

  // Return only the fields required by the payout API.
  return {
    requestKey: payload.requestKey,
    amount: payload.amount,
  };
}

/**
 * Persists a payout request before sending it to the backend.
 *
 * An existing unresolved request must never be replaced with a
 * different request key or amount.
 *
 * @param {string} providerId Authenticated provider's user ID.
 * @param {{requestKey: string, amount: number}} payload
 * Original payout request payload.
 */
export function savePendingPayoutRequest(providerId, payload) {
  if (!isValidPayload(payload)) {
    throw new Error('Invalid payout request recovery data.');
  }

  const storageKey = getStorageKey(providerId);
  const storage = getStorage();

  const existing = loadPendingPayoutRequest(providerId);

  if (existing) {
    const sameRequest =
      existing.requestKey === payload.requestKey &&
      existing.amount === payload.amount;

    if (!sameRequest) {
      throw new Error(
        'An unresolved payout request already exists. ' +
          'Retry the original request before creating another.',
      );
    }

    // The original request is already safely persisted.
    return;
  }

  const record = {
    requestKey: payload.requestKey,
    amount: payload.amount,
  };

  storage.setItem(storageKey, JSON.stringify(record));

  // Verify that the original recovery information can be restored.
  const persisted = loadPendingPayoutRequest(providerId);

  if (
    !persisted ||
    persisted.requestKey !== record.requestKey ||
    persisted.amount !== record.amount
  ) {
    throw new Error(
      'Unable to verify payout request recovery data.',
    );
  }
}

/**
 * Clears a resolved payout request only when its request key matches.
 *
 * Matching the key prevents an outdated response from intentionally
 * clearing a different request's recovery information.
 *
 * This operation must only be called after a confirmed successful
 * response or a verified definitive rejection.
 *
 * @param {string} providerId Authenticated provider's user ID.
 * @param {string} requestKey Original payout request key.
 * @returns {boolean} Whether a matching record was removed.
 */
export function clearPendingPayoutRequest(
  providerId,
  requestKey,
) {
  const storageKey = getStorageKey(providerId);
  const existing = loadPendingPayoutRequest(providerId);

  if (!existing || existing.requestKey !== requestKey) {
    return false;
  }

  getStorage().removeItem(storageKey);

  return true;
}
