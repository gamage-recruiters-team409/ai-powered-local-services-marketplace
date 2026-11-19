import { ADMIN_ROLES } from '../constants/roles';

export const DEFAULT_CUSTOMER_HOME = '/';
export const DEFAULT_ADMIN_HOME = '/admin';

export function getRoleHomePath(role) {
  if (role && ADMIN_ROLES.includes(role)) {
    return DEFAULT_ADMIN_HOME;
  }
  return DEFAULT_CUSTOMER_HOME;
}

export function resolvePostLoginRedirect(user, stateFrom) {
  if (stateFrom && typeof stateFrom === 'string' && stateFrom.startsWith('/')) {
    // Prevent redirecting back to guest-only auth pages
    const guestOnlyPrefixes = [
      '/login',
      '/register',
      '/forgot-password',
      '/reset-password',
      '/verify-email',
      '/check-email',
    ];
    const isGuestPage = guestOnlyPrefixes.some((prefix) => stateFrom.startsWith(prefix));
    if (!isGuestPage) {
      return stateFrom;
    }
  }

  return getRoleHomePath(user?.role);
}
