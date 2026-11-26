// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { PublicNavbar } from './PublicNavbar'

function renderNavbar(props = {}) {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <PublicNavbar {...props} />
    </MemoryRouter>
  )
}

describe('PublicNavbar (guest & auth-aware navigation)', () => {
  it('renders guest actions (Become a Provider, Log in, Sign up) when unauthenticated', () => {
    renderNavbar()

    expect(screen.getByRole('link', { name: 'Become a Provider' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Log in' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Sign up' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Account menu:/i })).toBeNull()
  })

  it('renders user account dropdown with role links and working Log out when authenticated', async () => {
    const user = userEvent.setup()
    const onLogout = vi.fn().mockResolvedValue(undefined)

    renderNavbar({
      user: {
        name: 'Nimal Perera',
        email: 'nimal@localserve.lk',
        role: 'CUSTOMER',
      },
      accountMenuItems: [
        { id: 'profile', label: 'Profile', to: '/customer/account/profile' },
        { id: 'favorites', label: 'Favorites', to: '/customer/account/favorites' },
      ],
      showBecomeProvider: true,
      onLogout,
    })

    expect(screen.queryByRole('link', { name: 'Log in' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Sign up' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Become a Provider' })).toBeTruthy()

    const accountBtn = screen.getByRole('button', { name: 'Account menu: Nimal Perera' })
    expect(accountBtn.getAttribute('aria-expanded')).toBe('false')
    expect(within(accountBtn).getByText('NP')).toBeTruthy()

    await user.click(accountBtn)
    expect(accountBtn.getAttribute('aria-expanded')).toBe('true')

    const menu = screen.getByRole('menu', { name: 'Account navigation' })
    expect(within(menu).getByText('nimal@localserve.lk')).toBeTruthy()
    expect(within(menu).getByText('Customer')).toBeTruthy()
    expect(
      within(menu).getByRole('menuitem', { name: 'Profile' }).getAttribute('href')
    ).toBe('/customer/account/profile')
    expect(
      within(menu).getByRole('menuitem', { name: 'Favorites' }).getAttribute('href')
    ).toBe('/customer/account/favorites')

    await user.click(within(menu).getByRole('menuitem', { name: 'Log out' }))
    expect(onLogout).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu', { name: 'Account navigation' })).toBeNull()
  })

  it('closes the account dropdown on Escape and keeps city & account menus mutually exclusive', async () => {
    const user = userEvent.setup()
    renderNavbar({
      user: { fullName: 'Kamal Silva', email: 'kamal@localserve.lk', role: 'PROVIDER' },
      accountMenuItems: [{ id: 'provider-profile', label: 'Provider Profile', to: '/provider/profile' }],
      showBecomeProvider: false,
      onLogout: vi.fn(),
    })

    expect(screen.queryByRole('link', { name: 'Become a Provider' })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Account menu: Kamal Silva' }))
    expect(screen.getByRole('menu', { name: 'Account navigation' })).toBeTruthy()

    // Opening City menu closes Account menu
    await user.click(screen.getByRole('button', { name: /Selected city: Colombo/i }))
    expect(screen.queryByRole('menu', { name: 'Account navigation' })).toBeNull()
    expect(screen.getByRole('listbox', { name: 'Select city' })).toBeTruthy()

    // Opening Account menu closes City menu, and Escape closes Account menu
    await user.click(screen.getByRole('button', { name: 'Account menu: Kamal Silva' }))
    expect(screen.queryByRole('listbox', { name: 'Select city' })).toBeNull()
    expect(screen.getByRole('menu', { name: 'Account navigation' })).toBeTruthy()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu', { name: 'Account navigation' })).toBeNull()
  })

  it('renders mobile drawer account card, role links, and Log out when authenticated', async () => {
    const user = userEvent.setup()
    const onLogout = vi.fn().mockResolvedValue(undefined)

    renderNavbar({
      user: { name: 'Admin User', email: 'admin@localserve.lk', role: 'ADMIN' },
      accountMenuItems: [{ id: 'admin-dashboard', label: 'Admin Dashboard', to: '/admin' }],
      showBecomeProvider: false,
      onLogout,
    })

    await user.click(screen.getByRole('button', { name: 'Open navigation menu' }))

    expect(screen.queryByRole('link', { name: 'Become a Provider' })).toBeNull()
    expect(screen.getByText('admin@localserve.lk')).toBeTruthy()
    expect(
      screen.getByRole('link', { name: 'Admin Dashboard' }).getAttribute('href')
    ).toBe('/admin')

    await user.click(screen.getByRole('button', { name: 'Log out' }))
    expect(onLogout).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['SUPER_ADMIN', 'Super Admin'],
    ['SUPPORT_ADMIN', 'Support Admin'],
    ['MODERATOR', 'Moderator'],
    ['FINANCE_ADMIN', 'Finance Admin'],
  ])('displays accurate badge label for backend admin role %s', async (role, expectedLabel) => {
    const user = userEvent.setup()
    renderNavbar({
      user: { name: 'Ops User', email: 'ops@localserve.lk', role },
      accountMenuItems: [{ id: 'admin-dashboard', label: 'Admin Dashboard', to: '/admin' }],
      showBecomeProvider: false,
      onLogout: vi.fn(),
    })

    await user.click(screen.getByRole('button', { name: 'Account menu: Ops User' }))
    const menu = screen.getByRole('menu', { name: 'Account navigation' })
    expect(within(menu).getByText(expectedLabel)).toBeTruthy()
  })
})
