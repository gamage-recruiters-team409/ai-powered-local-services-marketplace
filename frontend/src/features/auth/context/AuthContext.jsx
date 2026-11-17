import { createContext, useCallback, useEffect, useMemo, useRef, useState } from 'react';

import tokenStore from '../../../shared/services/tokenStore';
import { onSessionExpired } from '../../../shared/services/apiClient';
import * as authApi from '../api/authApi';

export const AuthContext = createContext(null);

const AUTH_STATUS = {
  LOADING: 'loading',
  AUTHENTICATED: 'authenticated',
  UNAUTHENTICATED: 'unauthenticated',
};

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [status, setStatus] = useState(AUTH_STATUS.LOADING);
  const [mustChangePassword, setMustChangePassword] = useState(false);
  const channelRef = useRef(null);

  // Session restore on mount
  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      if (!tokenStore.hasRefreshToken()) {
        setStatus(AUTH_STATUS.UNAUTHENTICATED);
        return;
      }

      try {
        const refreshTokenVal = tokenStore.getRefreshToken();
        const tokens = await authApi.refreshToken(refreshTokenVal);
        tokenStore.setTokens({
          accessToken: tokens.accessToken,
          refreshToken: tokens.refreshToken,
        });

        if (tokens.mustChangePassword) {
          if (!cancelled) {
            setMustChangePassword(true);
            setUser({ mustChangePassword: true });
            setStatus(AUTH_STATUS.AUTHENTICATED);
          }
          return;
        }

        const userData = await authApi.getCurrentUser();
        if (!cancelled) {
          setUser(userData);
          setStatus(AUTH_STATUS.AUTHENTICATED);
        }
      } catch (err) {
        tokenStore.clear();
        if (!cancelled) {
          setStatus(AUTH_STATUS.UNAUTHENTICATED);
        }
      }
    }

    restoreSession();
    return () => {
      cancelled = true;
    };
  }, []);

  // Listen for session expiration from apiClient interceptor
  useEffect(() => {
    const unsubscribe = onSessionExpired(() => {
      setUser(null);
      setMustChangePassword(false);
      setStatus(AUTH_STATUS.UNAUTHENTICATED);
    });
    return unsubscribe;
  }, []);

  // Cross-tab BroadcastChannel sync
  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return;

    let channel;
    try {
      channel = new BroadcastChannel('auth');
      channelRef.current = channel;
    } catch (err) {
      return;
    }

    channel.onmessage = (event) => {
      const { type, payload } = event.data || {};
      if (type === 'logout') {
        tokenStore.clear();
        setUser(null);
        setMustChangePassword(false);
        setStatus(AUTH_STATUS.UNAUTHENTICATED);
      } else if (type === 'login' && payload) {
        tokenStore.setTokens({
          accessToken: payload.accessToken,
          refreshToken: payload.refreshToken,
        });
        if (payload.user) {
          setUser(payload.user);
          setMustChangePassword(false);
          setStatus(AUTH_STATUS.AUTHENTICATED);
        }
      } else if (type === 'token-updated' && payload) {
        tokenStore.setTokens({
          accessToken: payload.accessToken,
          refreshToken: payload.refreshToken,
        });
      }
    };

    return () => {
      try {
        channel.close();
      } catch (err) {
        // Ignore close error
      }
      channelRef.current = null;
    };
  }, []);

  const broadcastEvent = useCallback((type, payload) => {
    try {
      if (channelRef.current) {
        channelRef.current.postMessage({ type, payload });
      }
    } catch (err) {
      // Ignore broadcast error
    }
  }, []);

  const login = useCallback(async (credentials) => {
    const response = await authApi.login(credentials);

    tokenStore.setTokens({
      accessToken: response.accessToken,
      refreshToken: response.refreshToken,
    });

    if (response.mustChangePassword) {
      setMustChangePassword(true);
      setUser({ mustChangePassword: true });
      setStatus(AUTH_STATUS.AUTHENTICATED);
      broadcastEvent('login', {
        accessToken: response.accessToken,
        refreshToken: response.refreshToken,
      });
      return response;
    }

    const userData = await authApi.getCurrentUser();
    setUser(userData);
    setMustChangePassword(false);
    setStatus(AUTH_STATUS.AUTHENTICATED);

    broadcastEvent('login', {
      accessToken: response.accessToken,
      refreshToken: response.refreshToken,
      user: userData,
    });

    return { ...response, user: userData };
  }, [broadcastEvent]);

  const register = useCallback(async (data) => {
    const response = await authApi.register(data);
    return response;
  }, []);

  const logout = useCallback(async () => {
    try {
      const refreshTokenVal = tokenStore.getRefreshToken();
      if (refreshTokenVal) {
        await authApi.logout(refreshTokenVal);
      }
    } catch (err) {
      // Logout failure should not block the client-side cleanup
    }

    tokenStore.clear();
    setUser(null);
    setMustChangePassword(false);
    setStatus(AUTH_STATUS.UNAUTHENTICATED);
    broadcastEvent('logout');
  }, [broadcastEvent]);

  const refreshUser = useCallback(async () => {
    try {
      const userData = await authApi.getCurrentUser();
      setUser(userData);
      setMustChangePassword(false);
      return userData;
    } catch (err) {
      return null;
    }
  }, []);

  const hasRole = useCallback(
    (role) => {
      if (!user || !user.role) return false;
      if (Array.isArray(role)) return role.includes(user.role);
      return user.role === role;
    },
    [user]
  );

  const value = useMemo(
    () => ({
      user,
      status,
      mustChangePassword,
      isLoading: status === AUTH_STATUS.LOADING,
      isAuthenticated: status === AUTH_STATUS.AUTHENTICATED,
      login,
      register,
      logout,
      refreshUser,
      hasRole,
    }),
    [user, status, mustChangePassword, login, register, logout, refreshUser, hasRole]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export default AuthContext;
