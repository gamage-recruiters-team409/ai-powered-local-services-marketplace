// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup } from '@testing-library/react';
import {
  DEFAULT_ADMIN_HOME,
  DEFAULT_CUSTOMER_HOME,
  getRoleHomePath,
  resolvePostLoginRedirect,
} from '../utils/roleRedirect';

describe('roleRedirect', () => {
  afterEach(() => {
    cleanup();
  });

  it('directs every admin role to /admin', () => {
    expect(getRoleHomePath('ADMIN')).toBe(DEFAULT_ADMIN_HOME);
    expect(getRoleHomePath('SUPER_ADMIN')).toBe(DEFAULT_ADMIN_HOME);
    expect(getRoleHomePath('SUPPORT_ADMIN')).toBe(DEFAULT_ADMIN_HOME);
    expect(getRoleHomePath('MODERATOR')).toBe(DEFAULT_ADMIN_HOME);
    expect(getRoleHomePath('FINANCE_ADMIN')).toBe(DEFAULT_ADMIN_HOME);
  });

  it('directs CUSTOMER and PROVIDER to /', () => {
    expect(getRoleHomePath('CUSTOMER')).toBe(DEFAULT_CUSTOMER_HOME);
    expect(getRoleHomePath('PROVIDER')).toBe(DEFAULT_CUSTOMER_HOME);
    expect(getRoleHomePath(null)).toBe(DEFAULT_CUSTOMER_HOME);
  });

  it('resolves state.from when safe and not an auth page', () => {
    const user = { role: 'CUSTOMER' };
    expect(resolvePostLoginRedirect(user, '/services/plumbing')).toBe('/services/plumbing');
    expect(resolvePostLoginRedirect(user, '/admin/disputes')).toBe('/admin/disputes');
  });

  it('falls back to role home when state.from points to an auth page', () => {
    const user = { role: 'CUSTOMER' };
    expect(resolvePostLoginRedirect(user, '/login')).toBe(DEFAULT_CUSTOMER_HOME);
    expect(resolvePostLoginRedirect(user, '/register')).toBe(DEFAULT_CUSTOMER_HOME);
    expect(resolvePostLoginRedirect(user, '/forgot-password')).toBe(DEFAULT_CUSTOMER_HOME);
  });
});
