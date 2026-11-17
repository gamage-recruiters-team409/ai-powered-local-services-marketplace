import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import tokenStore from '../../../shared/services/tokenStore';
import {
  endSession,
  getAccessToken,
  hasSession,
  LOGIN_PATH,
} from '../../disputes-admin/services/authBridge';

describe('authBridge delegation to tokenStore', () => {
  beforeEach(() => {
    tokenStore.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    tokenStore.clear();
    sessionStorage.clear();
  });

  it('exports LOGIN_PATH as /login', () => {
    expect(LOGIN_PATH).toBe('/login');
  });

  it('delegates getAccessToken to tokenStore', () => {
    expect(getAccessToken()).toBeNull();
    tokenStore.setAccessToken('bridge-access-token');
    expect(getAccessToken()).toBe('bridge-access-token');
  });

  it('reflects session state in hasSession', () => {
    expect(hasSession()).toBe(false);
    tokenStore.setAccessToken('bridge-token');
    expect(hasSession()).toBe(true);

    tokenStore.clear();
    tokenStore.setRefreshToken('bridge-refresh');
    expect(hasSession()).toBe(true);
  });

  it('clears session on endSession', () => {
    tokenStore.setTokens({ accessToken: 'a', refreshToken: 'r' });
    expect(hasSession()).toBe(true);

    endSession();
    expect(hasSession()).toBe(false);
    expect(tokenStore.getAccessToken()).toBeNull();
  });
});
