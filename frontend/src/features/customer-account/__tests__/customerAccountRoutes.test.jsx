import { describe, it, expect } from 'vitest';
import { customerAccountRoutes } from '../routes/customerAccountRoutes';
import {
  CUSTOMER_PROFILE_PATH,
  CUSTOMER_NAV_SECTIONS,
  CUSTOMER_NAV_FOOTER_ITEMS,
  CUSTOMER_PROFILE_MENU_ITEMS,
} from '../../../shared/layouts/customer/customerNavConfig';

describe('customerAccountRoutes', () => {
  it('exports child routes with correct relative paths and index redirect', () => {
    expect(customerAccountRoutes).toBeInstanceOf(Array);

    const indexRoute = customerAccountRoutes.find((r) => r.index);
    expect(indexRoute).toBeTruthy();

    const paths = customerAccountRoutes.filter((r) => r.path).map((r) => r.path);
    expect(paths).toContain('profile');
    expect(paths).toContain('addresses');
    expect(paths).toContain('settings');
    expect(paths).toContain('deactivate');
    expect(paths).toContain('payments');
    expect(paths).toContain('favorites');
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
