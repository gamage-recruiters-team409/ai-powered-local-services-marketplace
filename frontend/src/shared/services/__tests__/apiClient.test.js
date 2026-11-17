// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import tokenStore from '../tokenStore';
import {
  apiClient,
  isPublicAuthEndpoint,
  normalizeError,
  notifySessionExpired,
  onSessionExpired,
  __resetForTests,
} from '../apiClient';

describe('apiClient', () => {
  beforeEach(() => {
    tokenStore.clear();
    __resetForTests();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    cleanup();
    tokenStore.clear();
    __resetForTests();
  });

  describe('isPublicAuthEndpoint', () => {
    it('identifies public auth endpoints', () => {
      expect(isPublicAuthEndpoint('/auth/login')).toBe(true);
      expect(isPublicAuthEndpoint('/auth/register')).toBe(true);
      expect(isPublicAuthEndpoint('/auth/refresh')).toBe(true);
      expect(isPublicAuthEndpoint('/auth/verify-email')).toBe(true);
      expect(isPublicAuthEndpoint('/auth/resend-verification')).toBe(true);
      expect(isPublicAuthEndpoint('/auth/forgot-password')).toBe(true);
      expect(isPublicAuthEndpoint('/auth/reset-password')).toBe(true);
    });

    it('returns false for protected endpoints or null/empty', () => {
      expect(isPublicAuthEndpoint('/users/me')).toBe(false);
      expect(isPublicAuthEndpoint('/services')).toBe(false);
      expect(isPublicAuthEndpoint('')).toBe(false);
      expect(isPublicAuthEndpoint(null)).toBe(false);
    });
  });

  describe('normalizeError', () => {
    it('normalizes error response with code, message, and fieldErrors', () => {
      const axiosError = {
        message: 'Request failed with status code 400',
        response: {
          status: 400,
          headers: {},
          data: {
            error: 'VALIDATION_FAILED',
            message: 'Invalid input provided',
            fieldErrors: { email: 'Invalid email' },
          },
        },
      };

      const normalized = normalizeError(axiosError);
      expect(normalized.status).toBe(400);
      expect(normalized.code).toBe('VALIDATION_FAILED');
      expect(normalized.message).toBe('Invalid input provided');
      expect(normalized.fieldErrors).toEqual({ email: 'Invalid email' });
      expect(normalized.retryAfterSeconds).toBeNull();
    });

    it('parses Retry-After header on 429 RATE_LIMITED', () => {
      const axiosError = {
        response: {
          status: 429,
          headers: { 'retry-after': '60' },
          data: {
            error: 'RATE_LIMITED',
            message: 'Too many requests',
          },
        },
      };

      const normalized = normalizeError(axiosError);
      expect(normalized.status).toBe(429);
      expect(normalized.code).toBe('RATE_LIMITED');
      expect(normalized.retryAfterSeconds).toBe(60);
    });

    it('handles network error when response is missing', () => {
      const axiosError = {
        message: 'Network Error',
        request: {},
      };

      const normalized = normalizeError(axiosError);
      expect(normalized.status).toBe(0);
      expect(normalized.code).toBe('NETWORK_ERROR');
      expect(normalized.message).toBe('Network Error');
    });
  });

  describe('session expiration listener', () => {
    it('notifies listeners on notifySessionExpired and supports unsubscribing', () => {
      const listener = vi.fn();
      const unsubscribe = onSessionExpired(listener);

      notifySessionExpired();
      expect(listener).toHaveBeenCalledTimes(1);

      unsubscribe();
      notifySessionExpired();
      expect(listener).toHaveBeenCalledTimes(1);
    });
  });
});
