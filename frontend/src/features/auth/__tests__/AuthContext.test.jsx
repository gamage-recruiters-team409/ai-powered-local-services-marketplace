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
      profileImageUrl: 'https://cdn.example.com/kasun.jpg',
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
    expect(result.current.user?.profileImageUrl).toBe('https://cdn.example.com/kasun.jpg');
    expect(tokenStore.getAccessToken()).toBe('new-acc');
    expect(tokenStore.getRefreshToken()).toBe('new-ref');
  });

  it('restores session falling back to refresh user when getCurrentUser fails', async () => {
    tokenStore.setRefreshToken('saved-refresh-token');
    refreshSession.mockResolvedValueOnce({
      accessToken: 'new-acc',
      refreshToken: 'new-ref',
      user: { id: 'u1', name: 'Kasun-Fallback', role: 'CUSTOMER' },
      mustChangePassword: false,
    });
    authApi.getCurrentUser.mockRejectedValueOnce(new Error('Network error on me'));

    const { result } = renderHook(() => useAuth(), {
      wrapper: AuthProvider,
    });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.user?.name).toBe('Kasun-Fallback');
    expect(result.current.user?.profileImageUrl).toBeUndefined();
  });

  it('restores session with mustChangePassword true leaving mustChangePassword branch unchanged', async () => {
    tokenStore.setRefreshToken('saved-refresh-token');
    refreshSession.mockResolvedValueOnce({
      accessToken: 'new-acc',
      refreshToken: 'new-ref',
      user: { id: 'u1', name: 'MustChangeUser', role: 'CUSTOMER' },
      mustChangePassword: true,
    });

    const { result } = renderHook(() => useAuth(), {
      wrapper: AuthProvider,
    });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.mustChangePassword).toBe(true);
    expect(result.current.user?.mustChangePassword).toBe(true);
    expect(authApi.getCurrentUser).not.toHaveBeenCalled();
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
    authApi.getCurrentUser.mockResolvedValueOnce({
      id: 'u1',
      name: 'Kasun',
      role: 'CUSTOMER',
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

  describe('updateCurrentUser', () => {
    it('merges name, phone, and profileImageUrl into the current user', async () => {
      tokenStore.setRefreshToken('saved-refresh-token');
      refreshSession.mockResolvedValueOnce({
        accessToken: 'new-acc',
        refreshToken: 'new-ref',
        user: {
          id: 'u-1',
          name: 'Original Name',
          email: 'orig@example.lk',
          phone: '+94770000000',
          role: 'CUSTOMER',
          status: 'ACTIVE',
          profileImageUrl: 'https://cdn.example.com/orig.jpg',
        },
        mustChangePassword: false,
      });
      authApi.getCurrentUser.mockRejectedValueOnce(new Error('fail restore'));

      const { result } = renderHook(() => useAuth(), {
        wrapper: AuthProvider,
      });

      await waitFor(() => {
        expect(result.current.isLoading).toBe(false);
      });

      expect(result.current.user?.name).toBe('Original Name');

      act(() => {
        result.current.updateCurrentUser({
          name: 'Updated Name',
          phone: '+94771111111',
          profileImageUrl: 'https://cdn.example.com/updated.jpg',
        });
      });

      expect(result.current.user).toEqual({
        id: 'u-1',
        name: 'Updated Name',
        email: 'orig@example.lk',
        phone: '+94771111111',
        role: 'CUSTOMER',
        status: 'ACTIVE',
        profileImageUrl: 'https://cdn.example.com/updated.jpg',
      });
      // Does not trigger any new API call
      expect(authApi.getCurrentUser).toHaveBeenCalledTimes(1);
    });

    it('ignores id, role, status, mustChangePassword, email and unknown keys', async () => {
      tokenStore.setRefreshToken('saved-refresh-token');
      refreshSession.mockResolvedValueOnce({
        accessToken: 'new-acc',
        refreshToken: 'new-ref',
        user: {
          id: 'u-1',
          name: 'Original Name',
          email: 'orig@example.lk',
          phone: '+94770000000',
          role: 'CUSTOMER',
          status: 'ACTIVE',
          profileImageUrl: null,
          emailVerified: true,
        },
        mustChangePassword: false,
      });
      authApi.getCurrentUser.mockRejectedValueOnce(new Error('fail restore'));

      const { result } = renderHook(() => useAuth(), {
        wrapper: AuthProvider,
      });

      await waitFor(() => {
        expect(result.current.isLoading).toBe(false);
      });

      act(() => {
        result.current.updateCurrentUser({
          id: 'malicious-id',
          role: 'ADMIN',
          status: 'SUSPENDED',
          mustChangePassword: true,
          email: 'hacked@example.lk',
          emailVerified: false,
          randomField: 'forbidden',
          name: 'New Allowed Name',
        });
      });

      expect(result.current.user?.name).toBe('New Allowed Name');
      expect(result.current.user?.id).toBe('u-1');
      expect(result.current.user?.role).toBe('CUSTOMER');
      expect(result.current.user?.status).toBe('ACTIVE');
      expect(result.current.user?.email).toBe('orig@example.lk');
      expect(result.current.user?.emailVerified).toBe(true);
      expect(result.current.mustChangePassword).toBe(false);
      expect(result.current.user?.randomField).toBeUndefined();
      expect(authApi.getCurrentUser).toHaveBeenCalledTimes(1);
    });

    it('does nothing when there is no user and does not call the API', async () => {
      const { result } = renderHook(() => useAuth(), {
        wrapper: AuthProvider,
      });

      await waitFor(() => {
        expect(result.current.isLoading).toBe(false);
      });

      expect(result.current.user).toBeNull();
      expect(result.current.isAuthenticated).toBe(false);

      act(() => {
        result.current.updateCurrentUser({
          name: 'Ghost Name',
          phone: '+94779999999',
          profileImageUrl: 'https://cdn.example.com/ghost.jpg',
        });
      });

      expect(result.current.user).toBeNull();
      expect(authApi.getCurrentUser).not.toHaveBeenCalled();
      expect(authApi.login).not.toHaveBeenCalled();
    });
  });
});
