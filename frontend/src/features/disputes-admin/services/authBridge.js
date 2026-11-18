import tokenStore from '../../../shared/services/tokenStore';

/**
 * Auth bridge for the admin feature.
 * Delegates to the shared WP01 tokenStore, with backwards-compatible DEV fallback.
 */

export const LOGIN_PATH = '/login';

const DEV_TOKEN_KEY = 'dev.accessToken';

export function getAccessToken() {
  const token = tokenStore.getAccessToken();
  if (token) {
    return token;
  }
  if (!import.meta.env.DEV) {
    return null;
  }
  try {
    return window.sessionStorage.getItem(DEV_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function hasSession() {
  return Boolean(getAccessToken() || tokenStore.hasRefreshToken());
}

/** Clears the local session. Server-side token revocation belongs to WP01 logout. */
export function endSession() {
  tokenStore.clear();
  try {
    window.sessionStorage.removeItem(DEV_TOKEN_KEY);
  } catch {
    // Storage unavailable: nothing to clear.
  }
}
