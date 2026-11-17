// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup } from '@testing-library/react';
import tokenStore from '../tokenStore';

describe('tokenStore', () => {
  beforeEach(() => {
    tokenStore.clear();
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    tokenStore.clear();
    localStorage.clear();
  });

  it('stores and retrieves access token in memory', () => {
    expect(tokenStore.getAccessToken()).toBeNull();
    expect(tokenStore.hasAccessToken()).toBe(false);

    tokenStore.setAccessToken('test-access-token');
    expect(tokenStore.getAccessToken()).toBe('test-access-token');
    expect(tokenStore.hasAccessToken()).toBe(true);

    // Verify it is NOT in localStorage
    expect(localStorage.getItem('auth_refresh_token')).toBeNull();
  });

  it('stores and retrieves refresh token in localStorage', () => {
    expect(tokenStore.getRefreshToken()).toBeNull();
    expect(tokenStore.hasRefreshToken()).toBe(false);

    tokenStore.setRefreshToken('test-refresh-token');
    expect(tokenStore.getRefreshToken()).toBe('test-refresh-token');
    expect(tokenStore.hasRefreshToken()).toBe(true);
    expect(localStorage.getItem('auth_refresh_token')).toBe('test-refresh-token');
  });

  it('sets both tokens via setTokens helper', () => {
    tokenStore.setTokens({
      accessToken: 'access-123',
      refreshToken: 'refresh-123',
    });

    expect(tokenStore.getAccessToken()).toBe('access-123');
    expect(tokenStore.getRefreshToken()).toBe('refresh-123');
  });

  it('clears both tokens on clear()', () => {
    tokenStore.setTokens({
      accessToken: 'access-123',
      refreshToken: 'refresh-123',
    });

    tokenStore.clear();

    expect(tokenStore.getAccessToken()).toBeNull();
    expect(tokenStore.getRefreshToken()).toBeNull();
    expect(tokenStore.hasAccessToken()).toBe(false);
    expect(tokenStore.hasRefreshToken()).toBe(false);
  });
});
