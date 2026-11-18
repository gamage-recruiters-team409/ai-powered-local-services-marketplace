import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import tokenStore from '../shared/services/tokenStore';
import { __resetForTests } from '../shared/services/apiClient';

afterEach(() => {
  cleanup();
  tokenStore.clear();
  __resetForTests();

  if (typeof window !== 'undefined') {
    try {
      if (window.localStorage) {
        window.localStorage.clear();
      }
    } catch {
      // Ignore
    }

    try {
      if (window.sessionStorage) {
        window.sessionStorage.clear();
      }
    } catch {
      // Ignore
    }

    try {
      window.history.replaceState({}, '', '/');
    } catch {
      // Ignore
    }

    try {
      const meta = document.querySelector('meta[name="referrer"]');
      if (meta && meta.parentNode) {
        meta.parentNode.removeChild(meta);
      }
    } catch {
      // Ignore
    }
  }

  vi.useRealTimers();
  vi.clearAllMocks();
});
