// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CustomerSidebar } from './CustomerSidebar'
import { CustomerCountBadge } from './CustomerCountBadge'

const user = { name: 'Nimal Perera', email: 'nimal@example.com' }

function MessagesCount() {
  return <CustomerCountBadge count={2} />
}

const sections = [
  {
    id: 'activity',
    title: 'My Activity',
    items: [
      { id: 'bookings', label: 'My Bookings', to: '/customer/bookings', icon: 'bookings' },
      { id: 'messages', label: 'Messages', to: '/customer/messages', icon: 'messages', badge: MessagesCount },
      { id: 'reviews', label: 'My Reviews', to: '/customer/reviews', icon: 'reviews' },
    ],
  },
  { id: 'account', title: 'My Account', items: [] },
]

const footerItems = [
  { id: 'help', label: 'Help & Support', to: '/help', icon: 'help' },
  { id: 'deactivate', label: 'Deactivate Account', to: '/customer/account/deactivate', icon: 'alert', tone: 'danger' },
]

afterEach(() => {
  cleanup()
})

function renderSidebar({ path = '/customer/bookings', onLogout = vi.fn() } = {}) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <CustomerSidebar user={user} sections={sections} footerItems={footerItems} onLogout={onLogout} />
    </MemoryRouter>,
  )
}

describe('CustomerSidebar', () => {
  it('renders sections with items and hides empty sections', () => {
    renderSidebar()

    expect(screen.getByRole('heading', { name: 'My Activity' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'My Account' })).toBeNull()
  })

  it('keeps an item active on its sub-pages', () => {
    renderSidebar({ path: '/customer/reviews/rev-1/edit' })

    expect(screen.getByRole('link', { name: 'My Reviews' }).getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('link', { name: 'My Bookings' }).getAttribute('aria-current')).toBeNull()
  })

  it('renders a badge component supplied by the nav config', () => {
    renderSidebar()

    const messages = screen.getByRole('link', { name: /Messages/ })
    expect(within(messages).getByText('2')).toBeTruthy()
  })

  it('orders footer items: regular, then Log out, then danger items', () => {
    renderSidebar()

    const nav = screen.getByRole('navigation', { name: 'Account navigation' })
    const labels = within(nav)
      .getAllByRole('listitem')
      .map((item) => item.textContent)
      .slice(-3)

    expect(labels).toEqual(['Help & Support', 'Log out', 'Deactivate Account'])
  })

  it('calls onLogout from the Log out button', () => {
    const onLogout = vi.fn()
    renderSidebar({ onLogout })

    fireEvent.click(screen.getByRole('button', { name: 'Log out' }))
    expect(onLogout).toHaveBeenCalledTimes(1)
  })
})
