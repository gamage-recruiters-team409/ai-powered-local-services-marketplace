// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CustomerAccountMenu } from './CustomerAccountMenu'
import { CustomerPage } from './CustomerPage'

const user = { name: 'Nimal Perera', email: 'nimal@example.com' }

const sections = [
  { id: 'activity', title: 'My Activity', items: [{ id: 'bookings', label: 'My Bookings', to: '/customer/bookings', icon: 'bookings' }] },
  { id: 'account', title: 'My Account', items: [{ id: 'favorites', label: 'Favorites', to: '/customer/account/favorites', icon: 'favorites' }] },
]

const footerItems = [
  { id: 'help', label: 'Help & Support', to: '/help', icon: 'help' },
  { id: 'deactivate', label: 'Deactivate Account', to: '/customer/account/deactivate', icon: 'alert', tone: 'danger' },
]

afterEach(() => {
  cleanup()
})

describe('CustomerAccountMenu (mobile Account tab)', () => {
  function renderMenu(props = {}) {
    return render(
      <MemoryRouter>
        <CustomerAccountMenu user={user} sections={sections} footerItems={footerItems} onLogout={vi.fn()} {...props} />
      </MemoryRouter>,
    )
  }

  it('lists all section items and regular footer items in one menu', () => {
    renderMenu()

    const nav = screen.getByRole('navigation', { name: 'Account menu' })
    const labels = within(nav).getAllByRole('link').map((link) => link.textContent)

    expect(labels).toEqual(['My Bookings', 'Favorites', 'Help & Support'])
  })

  it('shows Log out and danger items in a separate group', () => {
    const onLogout = vi.fn()
    renderMenu({ onLogout })

    fireEvent.click(screen.getByRole('button', { name: 'Log out' }))
    expect(onLogout).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('link', { name: 'Deactivate Account' }).getAttribute('href')).toBe('/customer/account/deactivate')
  })

  it('links the identity card to the profile page only when a profile path is set', () => {
    renderMenu({ profileTo: null })
    expect(screen.queryByText('View profile')).toBeNull()

    cleanup()
    renderMenu({ profileTo: '/customer/account/profile' })
    expect(screen.getByRole('link', { name: /Nimal Perera/ }).getAttribute('href')).toBe('/customer/account/profile')
  })
})

describe('CustomerPage', () => {
  it('renders breadcrumbs, title, actions and content', () => {
    render(
      <MemoryRouter>
        <CustomerPage
          title="My Bookings"
          breadcrumbs={[{ label: 'Home', to: '/' }, { label: 'My Bookings' }]}
          actions={<button type="button">Book a service</button>}
        >
          <p>List</p>
        </CustomerPage>
      </MemoryRouter>,
    )

    const breadcrumb = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(breadcrumb).getByRole('link', { name: 'Home' }).getAttribute('href')).toBe('/')
    expect(within(breadcrumb).getByText('My Bookings').getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('heading', { level: 1, name: 'My Bookings' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Book a service' })).toBeTruthy()
    expect(screen.getByText('List')).toBeTruthy()
  })

  it('uses onBack for the mobile back button when given', () => {
    const onBack = vi.fn()
    render(
      <MemoryRouter>
        <CustomerPage title="Details" onBack={onBack} />
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Go back' }))
    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('hides the back button when showBack is false', () => {
    render(
      <MemoryRouter>
        <CustomerPage title="Home" showBack={false} />
      </MemoryRouter>,
    )

    expect(screen.queryByRole('button', { name: 'Go back' })).toBeNull()
  })

  it('renders avatar image in account identity when avatarUrl is provided', () => {
    const userWithAvatar = { ...user, avatarUrl: 'https://cdn.example.com/account-avatar.jpg' }
    const { container } = render(
      <MemoryRouter>
        <CustomerAccountMenu user={userWithAvatar} sections={sections} footerItems={footerItems} onLogout={vi.fn()} />
      </MemoryRouter>
    )

    const img = container.querySelector('img')
    expect(img).toBeTruthy()
    expect(img.getAttribute('src')).toBe('https://cdn.example.com/account-avatar.jpg')
    expect(screen.queryByText('NP')).toBeNull()
  })

  it('falls back to initials in account menu when avatar image fails to load', () => {
    const userWithAvatar = { ...user, avatarUrl: 'https://broken.example.com/account-avatar.jpg' }
    const { container } = render(
      <MemoryRouter>
        <CustomerAccountMenu user={userWithAvatar} sections={sections} footerItems={footerItems} onLogout={vi.fn()} />
      </MemoryRouter>
    )

    const img = container.querySelector('img')
    expect(img).toBeTruthy()
    fireEvent.error(img)

    expect(screen.getByText('NP')).toBeTruthy()
    expect(container.querySelector('img')).toBeNull()
  })
})
