const REFRESH_TOKEN_KEY = 'auth_refresh_token';

let memoryAccessToken = null;

function safeGetItem(key) {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      return window.localStorage.getItem(key);
    }
  } catch (err) {
    // Ignore localStorage access failures (e.g. private mode / security restrictions)
  }
  return null;
}

function safeSetItem(key, value) {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      if (value) {
        window.localStorage.setItem(key, value);
      } else {
        window.localStorage.removeItem(key);
      }
    }
  } catch (err) {
    // Ignore localStorage access failures
  }
}

function safeRemoveItem(key) {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.removeItem(key);
    }
  } catch (err) {
    // Ignore localStorage access failures
  }
}

export const tokenStore = {
  getAccessToken() {
    return memoryAccessToken;
  },

  setAccessToken(token) {
    memoryAccessToken = token || null;
  },

  getRefreshToken() {
    return safeGetItem(REFRESH_TOKEN_KEY);
  },

  setRefreshToken(token) {
    safeSetItem(REFRESH_TOKEN_KEY, token);
  },

  setTokens({ accessToken, refreshToken } = {}) {
    if (accessToken !== undefined) {
      this.setAccessToken(accessToken);
    }
    if (refreshToken !== undefined) {
      this.setRefreshToken(refreshToken);
    }
  },

  hasAccessToken() {
    return Boolean(memoryAccessToken);
  },

  hasRefreshToken() {
    return Boolean(this.getRefreshToken());
  },

  clear() {
    memoryAccessToken = null;
    safeRemoveItem(REFRESH_TOKEN_KEY);
  },
};

export default tokenStore;
