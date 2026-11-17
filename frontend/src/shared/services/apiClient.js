import axios from 'axios';
import tokenStore from './tokenStore';

const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

const PUBLIC_AUTH_PATHS = [
  '/auth/login',
  '/auth/register',
  '/auth/refresh',
  '/auth/verify-email',
  '/auth/resend-verification',
  '/auth/forgot-password',
  '/auth/reset-password',
];

export function isPublicAuthEndpoint(url = '') {
  if (!url) return false;
  return PUBLIC_AUTH_PATHS.some((path) => url.includes(path));
}

export const apiClient = axios.create({
  baseURL: BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 15000,
});

// Session expired listeners
const sessionExpiredListeners = new Set();

export function onSessionExpired(listener) {
  sessionExpiredListeners.add(listener);
  return () => sessionExpiredListeners.delete(listener);
}

export function notifySessionExpired() {
  sessionExpiredListeners.forEach((listener) => {
    try {
      listener();
    } catch (err) {
      // Ignore listener error
    }
  });
}

// Single-flight refresh coordination
let refreshPromise = null;

async function executeTokenRefresh() {
  const currentRefreshToken = tokenStore.getRefreshToken();
  if (!currentRefreshToken) {
    throw new Error('No refresh token available');
  }

  const response = await axios.post(
    `${BASE_URL}/auth/refresh`,
    { refreshToken: currentRefreshToken },
    {
      headers: { 'Content-Type': 'application/json' },
      timeout: 15000,
    }
  );

  const { accessToken, refreshToken } = response.data;
  tokenStore.setTokens({ accessToken, refreshToken });
  return accessToken;
}

async function performTokenRefreshWithLock() {
  // Use Web Locks API if available to coordinate token refresh across browser tabs
  if (typeof navigator !== 'undefined' && navigator.locks && navigator.locks.request) {
    try {
      return await navigator.locks.request('auth_token_refresh', async () => {
        // Double check if another tab has already refreshed while we waited for the lock
        const currentAccessToken = tokenStore.getAccessToken();
        return await executeTokenRefresh();
      });
    } catch (lockError) {
      // If Web Locks fails, fall through to direct execution
    }
  }
  return await executeTokenRefresh();
}

async function performTokenRefresh() {
  try {
    return await performTokenRefreshWithLock();
  } catch (error) {
    // If backend returns 409 REFRESH_IN_PROGRESS, backoff 400ms and retry once
    if (error.response && error.response.status === 409) {
      await new Promise((resolve) => setTimeout(resolve, 400));
      return await performTokenRefreshWithLock();
    }
    throw error;
  }
}

export function normalizeError(error) {
  const normalized = new Error(
    error.response?.data?.message ||
    error.message ||
    'An unexpected error occurred'
  );

  normalized.status = error.response?.status || (error.request ? 0 : null);
  normalized.code =
    error.response?.data?.error ||
    (error.response ? 'HTTP_ERROR' : 'NETWORK_ERROR');
  normalized.fieldErrors = error.response?.data?.fieldErrors || null;
  normalized.timestamp = error.response?.data?.timestamp || null;
  normalized.path = error.response?.data?.path || null;

  const retryAfter = error.response?.headers?.['retry-after'];
  if (retryAfter) {
    const parsed = parseInt(retryAfter, 10);
    normalized.retryAfterSeconds = isNaN(parsed) ? null : parsed;
  } else {
    normalized.retryAfterSeconds = null;
  }

  normalized.originalError = error;
  return normalized;
}

// Request interceptor: attach Bearer token
apiClient.interceptors.request.use(
  (config) => {
    if (!config._skipAuth && !isPublicAuthEndpoint(config.url)) {
      const token = tokenStore.getAccessToken();
      if (token && !config.headers.Authorization) {
        config.headers.Authorization = `Bearer ${token}`;
      }
    }
    return config;
  },
  (error) => Promise.reject(normalizeError(error))
);

// Response interceptor: handle 401 & single-flight refresh
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    // Do not attempt refresh on cancelled requests, public auth endpoints, or already retried requests
    if (
      !originalRequest ||
      originalRequest._retry ||
      originalRequest._skipAuth ||
      isPublicAuthEndpoint(originalRequest.url) ||
      error.response?.status !== 401
    ) {
      return Promise.reject(normalizeError(error));
    }

    // If there is no refresh token, session is expired
    if (!tokenStore.hasRefreshToken()) {
      tokenStore.clear();
      notifySessionExpired();
      return Promise.reject(normalizeError(error));
    }

    originalRequest._retry = true;

    try {
      if (!refreshPromise) {
        refreshPromise = performTokenRefresh().finally(() => {
          refreshPromise = null;
        });
      }

      const newAccessToken = await refreshPromise;
      originalRequest.headers = originalRequest.headers || {};
      originalRequest.headers.Authorization = `Bearer ${newAccessToken}`;
      return apiClient(originalRequest);
    } catch (refreshError) {
      tokenStore.clear();
      notifySessionExpired();
      return Promise.reject(normalizeError(refreshError));
    }
  }
);

export default apiClient;
