// @vitest-environment jsdom

import { cleanup, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const auth = vi.hoisted(() => ({ current: null }));

vi.mock('../../auth/hooks/useAuth', () => ({
  useAuth: () => auth.current,
  default: () => auth.current,
}));

vi.mock('../services/customerAccountApi', () => ({
  getProfile: vi.fn().mockResolvedValue({
    fullName: 'Nimal Perera',
    email: 'nimal@example.com',
    emailVerified: true,
    phoneNumber: '+94 77 123 4567',
  }),
  updateProfile: vi.fn().mockResolvedValue({}),
  getAddresses: vi.fn().mockResolvedValue([]),
  getDistricts: vi.fn().mockResolvedValue(['Colombo', 'Gampaha', 'Kandy']),
  createAddress: vi.fn().mockResolvedValue({}),
  updateAddress: vi.fn().mockResolvedValue({}),
  deleteAddress: vi.fn().mockResolvedValue({}),
  setDefaultAddress: vi.fn().mockResolvedValue({}),
  changePassword: vi.fn().mockResolvedValue({}),
  deactivateAccount: vi.fn().mockResolvedValue({}),
}));

vi.mock('../services/paymentsApi', () => ({
  getMyPayments: vi.fn().mockResolvedValue({
    content: [],
    page: 0,
    size: 10,
    totalElements: 0,
    totalPages: 0,
  }),
}));

vi.mock('../services/favouritesApi', () => ({
  getFavourites: vi.fn().mockResolvedValue({
    content: [],
    page: 0,
    size: 6,
    totalElements: 0,
    totalPages: 0,
  }),
  removeFavourite: vi.fn().mockResolvedValue({}),
}));

vi.mock('../../../shared/components/feedback/useToast', () => ({
  useToast: () => ({
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
  }),
}));

import { customerAccountRoutes } from '../routes/customerAccountRoutes';
import { customerRoutes } from '../../../routes/customer/customerRoutes';
import {
  CUSTOMER_PROFILE_PATH,
  CUSTOMER_NAV_SECTIONS,
  CUSTOMER_NAV_FOOTER_ITEMS,
  CUSTOMER_PROFILE_MENU_ITEMS,
} from '../../../shared/layouts/customer/customerNavConfig';

function signedIn({ role = 'CUSTOMER', mustChangePassword = false } = {}) {
  return {
    user: { id: 'u-1', name: 'Nimal Perera', email: 'nimal@example.com', role },
    isLoading: false,
    isAuthenticated: true,
    mustChangePassword,
    hasRole: (roles) => (Array.isArray(roles) ? roles.includes(role) : roles === role),
    logout: vi.fn().mockResolvedValue(undefined),
  };
}

function renderCustomerRoute(path) {
  const router = createMemoryRouter(
    [
      customerRoutes,
      { path: '/login', element: <p>Login page</p> },
      { path: '/change-password', element: <p>Change password page</p> },
      { path: '/', element: <p>Home page</p> },
    ],
    { initialEntries: [path] }
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe('customerAccountRoutes and nav configuration', () => {
  it('exports customer account routes matching CUSTOMER_ACCOUNT_ROUTES', () => {
    expect(customerAccountRoutes).toBeInstanceOf(Array);

    const paths = customerAccountRoutes.map((r) => r.path);
    expect(paths).toContain('/customer/account');
    expect(paths).toContain('/customer/account/profile');
    expect(paths).toContain('/customer/account/addresses');
    expect(paths).toContain('/customer/account/settings');
    expect(paths).toContain('/customer/account/deactivate');
    expect(paths).toContain('/customer/account/payments');
    expect(paths).toContain('/customer/account/favorites');
  });

  it('customerNavConfig has CUSTOMER_PROFILE_PATH set to /customer/account/profile', () => {
    expect(CUSTOMER_PROFILE_PATH).toBe('/customer/account/profile');
  });

  it('customerNavConfig has Payments in My Activity section', () => {
    const activitySection = CUSTOMER_NAV_SECTIONS.find((s) => s.id === 'activity');
    expect(activitySection).toBeTruthy();
    const paymentsItem = activitySection.items.find((i) => i.id === 'payments');
    expect(paymentsItem).toEqual({
      id: 'payments',
      label: 'Payments',
      to: '/customer/account/payments',
      icon: 'payments',
    });
  });

  it('customerNavConfig has Profile, Saved Addresses, and Favorites in My Account section', () => {
    const accountSection = CUSTOMER_NAV_SECTIONS.find((s) => s.id === 'account');
    expect(accountSection).toBeTruthy();
    const ids = accountSection.items.map((i) => i.id);
    expect(ids).toContain('profile');
    expect(ids).toContain('addresses');
    expect(ids).toContain('favorites');
  });

  it('customerNavConfig has Deactivate Account in footer items with danger tone', () => {
    const deactivateItem = CUSTOMER_NAV_FOOTER_ITEMS.find((i) => i.id === 'deactivate');
    expect(deactivateItem).toEqual({
      id: 'deactivate',
      label: 'Deactivate Account',
      to: '/customer/account/deactivate',
      icon: 'alert',
      tone: 'danger',
    });
  });

  it('customerNavConfig has Settings in profile menu items', () => {
    const settingsItem = CUSTOMER_PROFILE_MENU_ITEMS.find((i) => i.id === 'settings');
    expect(settingsItem).toEqual({
      id: 'settings',
      label: 'Settings',
      to: '/customer/account/settings',
      icon: 'settings',
    });
  });
});

describe('customer account routing integration', () => {
  beforeEach(() => {
    auth.current = signedIn();
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('redirects /customer/account to /customer/account/profile inside customer layout', () => {
    renderCustomerRoute('/customer/account');

    expect(screen.getByRole('heading', { level: 1, name: /profile/i })).toBeTruthy();
    expect(screen.getByRole('complementary', { name: 'Account sidebar' })).toBeTruthy();
  });

  it('renders ProfilePage at /customer/account/profile inside customer layout', () => {
    renderCustomerRoute('/customer/account/profile');

    expect(screen.getByRole('heading', { level: 1, name: /profile/i })).toBeTruthy();
    expect(screen.getByRole('complementary', { name: 'Account sidebar' })).toBeTruthy();
  });

  it('renders SavedAddressesPage at /customer/account/addresses inside customer layout', () => {
    renderCustomerRoute('/customer/account/addresses');

    expect(screen.getByRole('heading', { level: 1, name: /saved addresses/i })).toBeTruthy();
    expect(screen.getByRole('complementary', { name: 'Account sidebar' })).toBeTruthy();
  });

  it('renders SettingsPage at /customer/account/settings inside customer layout', () => {
    renderCustomerRoute('/customer/account/settings');

    expect(screen.getByRole('heading', { level: 1, name: /settings/i })).toBeTruthy();
    expect(screen.getByRole('complementary', { name: 'Account sidebar' })).toBeTruthy();
  });

  it('renders DeactivatePage at /customer/account/deactivate inside customer layout', () => {
    renderCustomerRoute('/customer/account/deactivate');

    expect(screen.getByRole('heading', { level: 1, name: /deactivate account/i })).toBeTruthy();
    expect(screen.getByRole('complementary', { name: 'Account sidebar' })).toBeTruthy();
  });

  it('renders PaymentsPage at /customer/account/payments inside customer layout', () => {
    renderCustomerRoute('/customer/account/payments');

    expect(screen.getByRole('heading', { level: 1, name: /payments/i })).toBeTruthy();
    expect(screen.getByRole('complementary', { name: 'Account sidebar' })).toBeTruthy();
  });

  it('renders FavoritesPage at /customer/account/favorites inside customer layout', () => {
    renderCustomerRoute('/customer/account/favorites');

    expect(screen.getByRole('heading', { level: 1, name: /favorites/i })).toBeTruthy();
    expect(screen.getByRole('complementary', { name: 'Account sidebar' })).toBeTruthy();
  });

  it('redirects an unauthenticated visitor to /login via CustomerGuards', () => {
    auth.current = {
      user: null,
      isLoading: false,
      isAuthenticated: false,
      mustChangePassword: false,
      hasRole: () => false,
      logout: vi.fn(),
    };

    renderCustomerRoute('/customer/account/profile');

    expect(screen.getByText('Login page')).toBeTruthy();
  });
});
