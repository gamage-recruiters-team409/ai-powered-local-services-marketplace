// @vitest-environment jsdom
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import tokenStore from '../../../shared/services/tokenStore';
import { refreshSession } from '../../../shared/services/apiClient';
import * as authApi from '../api/authApi';
import { AuthProvider } from '../context/AuthContext';
import { useAuth } from '../hooks/useAuth';

vi.mock('../api/authApi');
vi.mock('../../../shared/services/apiClient', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    refreshSession: vi.fn(),
  };
});

describe('AuthContext', () => {
  beforeEach(() => {
    tokenStore.clear();
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
    tokenStore.clear();
  });

  it('restores session when refresh token is present in storage', async () => {
    tokenStore.setRefreshToken('saved-refresh-token');
    refreshSession.mockImplementationOnce(async () => {
      tokenStore.setTokens({
        accessToken: 'new-acc',
        refreshToken: 'new-ref',
      });
      return {
        accessToken: 'new-acc',
        refreshToken: 'new-ref',
        mustChangePassword: false,
      };
    });
    authApi.getCurrentUser.mockResolvedValueOnce({
      id: 'u1',
      name: 'Kasun',
      email: 'kasun@example.lk',
      role: 'CUSTOMER',
    });

    const { result } = renderHook(() => useAuth(), {
      wrapper: AuthProvider,
    });

    expect(result.current.isLoading).toBe(true);

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.user?.name).toBe('Kasun');
    expect(tokenStore.getAccessToken()).toBe('new-acc');
    expect(tokenStore.getRefreshToken()).toBe('new-ref');
  });

  it('preserves newer rotated token when a concurrent/competing call fails with 401', async () => {
    // Tab 1 starts with 'old-refresh'
    tokenStore.setRefreshToken('old-refresh');

    // Tab 1 restoration succeeds and rotates token to 'rotated-token'
    refreshSession.mockImplementationOnce(async () => {
      tokenStore.setTokens({
        accessToken: 'tab1-acc',
        refreshToken: 'rotated-token',
      });
      return {
        accessToken: 'tab1-acc',
        refreshToken: 'rotated-token',
        user: { id: 'u1', name: 'Kasun', role: 'CUSTOMER' },
        mustChangePassword: false,
      };
    });

    const { result } = renderHook(() => useAuth(), {
      wrapper: AuthProvider,
    });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(tokenStore.getRefreshToken()).toBe('rotated-token');

    // Tab 2 failed with 401 using 'old-refresh'
    tokenStore.clearIfRefreshTokenUnchanged('old-refresh');

    // Newer token must remain untouched in storage!
    expect(tokenStore.getRefreshToken()).toBe('rotated-token');
  });

  it('network error during restoration does not clear tokens', async () => {
    tokenStore.setRefreshToken('stored-network-token');

    const networkErr = new Error('Network Error');
    networkErr.status = 0;
    refreshSession.mockRejectedValueOnce(networkErr);

    const { result } = renderHook(() => useAuth(), {
      wrapper: AuthProvider,
    });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.isAuthenticated).toBe(false);
    // Token must NOT be cleared on network error
    expect(tokenStore.getRefreshToken()).toBe('stored-network-token');
  });

  it('becomes unauthenticated if no refresh token is present', async () => {
    const { result } = renderHook(() => useAuth(), {
      wrapper: AuthProvider,
    });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
  });

  it('logs in successfully and sets user state', async () => {
    authApi.login.mockResolvedValueOnce({
      accessToken: 'login-acc',
      refreshToken: 'login-ref',
    });
    authApi.getCurrentUser.mockResolvedValueOnce({
      id: 'u1',
      name: 'Kasun',
      email: 'kasun@example.lk',
      role: 'CUSTOMER',
    });

    const { result } = renderHook(() => useAuth(), {
      wrapper: AuthProvider,
    });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    await act(async () => {
      await result.current.login({ email: 'kasun@example.lk', password: 'Password123!' });
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.user?.name).toBe('Kasun');
    expect(tokenStore.getAccessToken()).toBe('login-acc');
  });

  it('clears state on logout', async () => {
    tokenStore.setTokens({ accessToken: 'a', refreshToken: 'r' });
    authApi.logout.mockResolvedValueOnce({});

    const { result } = renderHook(() => useAuth(), {
      wrapper: AuthProvider,
    });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    await act(async () => {
      await result.current.logout();
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
    expect(tokenStore.getAccessToken()).toBeNull();
    expect(tokenStore.getRefreshToken()).toBeNull();
  });
});
