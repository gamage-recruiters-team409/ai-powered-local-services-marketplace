// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CustomerLayout } from './CustomerLayout'
import { CustomerHeaderIconLink } from './CustomerHeaderIconLink'
import { CustomerCountBadge } from './CustomerCountBadge'

const user = { name: 'Nimal Perera', email: 'nimal@example.com' }

afterEach(() => {
  cleanup()
})

function renderLayout({ path = '/customer/bookings', ...props } = {}) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<CustomerLayout user={user} {...props} />}>
          <Route path="*" element={<p>Page content</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('CustomerLayout', () => {
  it('renders the brand, header links, page content through Outlet, and the footer', () => {
    renderLayout()

    expect(screen.getByRole('link', { name: 'LocalServe home' })).toBeTruthy()

    const mainNav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(mainNav).getByRole('link', { name: 'Home' })).toBeTruthy()
    expect(within(mainNav).getByRole('link', { name: 'Find Services' }).getAttribute('href')).toBe('/search')
    expect(within(mainNav).getByRole('link', { name: 'My Bookings' })).toBeTruthy()

    expect(screen.getByRole('main').textContent).toContain('Page content')
    expect(screen.getByRole('navigation', { name: 'Footer' })).toBeTruthy()
  })

  it('marks the current header link as active, also on sub-pages', () => {
    renderLayout({ path: '/customer/bookings/abc123' })

    const mainNav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(mainNav).getByRole('link', { name: 'My Bookings' }).getAttribute('aria-current')).toBe('page')
    expect(within(mainNav).getByRole('link', { name: 'Home' }).getAttribute('aria-current')).toBeNull()
  })

  it('does not show the account sidebar by default', () => {
    renderLayout()

    expect(screen.queryByRole('complementary', { name: 'Account sidebar' })).toBeNull()
  })

  it('shows the account sidebar when showAccountNav is set', () => {
    renderLayout({ showAccountNav: true })

    const sidebar = screen.getByRole('complementary', { name: 'Account sidebar' })
    expect(within(sidebar).getByText('Nimal Perera')).toBeTruthy()
    expect(within(sidebar).getByText('nimal@example.com')).toBeTruthy()
  })

  it('renders the mobile bottom navigation from the config', () => {
    renderLayout()

    const bottomNav = screen.getByRole('navigation', { name: 'Customer mobile navigation' })
    const labels = within(bottomNav).getAllByRole('link').map((link) => link.textContent)

    expect(labels).toEqual(['Home', 'Search', 'Bookings', 'Account'])
  })

  it('renders the location pill only when a location is given', () => {
    const onClick = vi.fn()
    renderLayout({ location: { label: 'Colombo', onClick } })

    fireEvent.click(screen.getByRole('button', { name: 'Location: Colombo. Change location' }))
    expect(onClick).toHaveBeenCalledTimes(1)

    cleanup()
    renderLayout()
    expect(screen.queryByRole('button', { name: /Location:/ })).toBeNull()
  })

  it('renders messages and notifications actions passed in by the route', () => {
    renderLayout({
      messagesAction: <CustomerHeaderIconLink to="/customer/messages" icon="messages" label="Messages" count={2} />,
      notificationsAction: <CustomerHeaderIconLink to="/customer/notifications" icon="notifications" label="Notifications" count={0} />,
    })

    expect(screen.getByRole('link', { name: 'Messages, 2 unread' }).getAttribute('href')).toBe('/customer/messages')
    expect(screen.getByRole('link', { name: 'Notifications' })).toBeTruthy()
  })

  it('renders children instead of the Outlet when given', () => {
    render(
      <MemoryRouter>
        <CustomerLayout user={user}>
          <p>Direct child</p>
        </CustomerLayout>
      </MemoryRouter>,
    )

    expect(screen.getByRole('main').textContent).toContain('Direct child')
  })
})

describe('CustomerCountBadge', () => {
  it('renders nothing for zero or invalid counts', () => {
    const { container } = render(
      <>
        <CustomerCountBadge count={0} />
        <CustomerCountBadge count={-3} />
        <CustomerCountBadge count="abc" />
      </>,
    )

    expect(container.textContent).toBe('')
  })

  it('caps large counts at 99+', () => {
    render(<CustomerCountBadge count={120} />)

    expect(screen.getByText('99+')).toBeTruthy()
  })
})
