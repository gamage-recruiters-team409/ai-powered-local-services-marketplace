// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import axios from 'axios';
import tokenStore from '../tokenStore';
import {
  isPublicAuthEndpoint,
  normalizeError,
  notifySessionExpired,
  onSessionExpired,
  refreshSession,
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

  describe('refreshSession coordination', () => {
    it('shares the same in-flight refresh across multiple calls (only one network call)', async () => {
      tokenStore.setRefreshToken('stored-refresh');
      const axiosSpy = vi.spyOn(axios, 'post').mockImplementation(() =>
        new Promise((resolve) =>
          setTimeout(
            () =>
              resolve({
                data: {
                  accessToken: 'acc-new',
                  refreshToken: 'ref-new',
                  mustChangePassword: false,
                },
              }),
            20
          )
        )
      );

      const [res1, res2] = await Promise.all([
        refreshSession(),
        refreshSession(),
      ]);

      expect(axiosSpy).toHaveBeenCalledTimes(1);
      expect(res1.accessToken).toBe('acc-new');
      expect(res2.accessToken).toBe('acc-new');
      expect(tokenStore.getRefreshToken()).toBe('ref-new');
    });

    it('network error during refresh does not clear tokens', async () => {
      tokenStore.setRefreshToken('stored-refresh');
      vi.spyOn(axios, 'post').mockRejectedValueOnce(new Error('Network Error'));

      await expect(refreshSession()).rejects.toThrow('Network Error');

      expect(tokenStore.getRefreshToken()).toBe('stored-refresh');
    });
  });
});
