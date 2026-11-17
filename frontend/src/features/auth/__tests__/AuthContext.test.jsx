// @vitest-environment jsdom
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import tokenStore from '../../../shared/services/tokenStore';
import * as authApi from '../api/authApi';
import { AuthProvider } from '../context/AuthContext';
import { useAuth } from '../hooks/useAuth';

vi.mock('../api/authApi');

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
    authApi.refreshToken.mockResolvedValueOnce({
      accessToken: 'new-acc',
      refreshToken: 'new-ref',
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
