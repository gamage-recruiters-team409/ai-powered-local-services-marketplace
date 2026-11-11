/**
 * TEMPORARY auth bridge until WP01 (Kalana) frontend auth is merged.
 *
 * The admin feature reads auth ONLY through this file. When the real login/auth
 * state lands, replace the bodies below with calls to that API and nothing else changes.
 *
 * DEV ONLY: the access token is read from sessionStorage key "dev.accessToken",
 * set by hand in the browser console after logging in with Postman. Production
 * builds never read it, so they always redirect to login.
 * This file never stores passwords and never calls the login endpoint.
 */

export const LOGIN_PATH = '/login'

const DEV_TOKEN_KEY = 'dev.accessToken'

export function getAccessToken() {
  if (!import.meta.env.DEV) {
    return null
  }
  try {
    return window.sessionStorage.getItem(DEV_TOKEN_KEY)
  } catch {
    return null
  }
}

export function hasSession() {
  return Boolean(getAccessToken())
}

/** Clears the local session. Server-side token revocation belongs to WP01 logout. */
export function endSession() {
  try {
    window.sessionStorage.removeItem(DEV_TOKEN_KEY)
  } catch {
    // Storage unavailable: nothing to clear.
  }
}
