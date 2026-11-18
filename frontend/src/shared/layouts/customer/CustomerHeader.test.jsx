// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CustomerHeader } from './CustomerHeader'
import { CUSTOMER_BRAND } from './customerBrand'
import { CUSTOMER_HEADER_LINKS, CUSTOMER_PROFILE_MENU_ITEMS } from './customerNavConfig'

const user = { name: 'Nimal Perera', email: 'nimal@example.com' }

afterEach(() => {
  cleanup()
})

function renderHeader(props = {}) {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <CustomerHeader
        brand={CUSTOMER_BRAND}
        user={user}
        links={CUSTOMER_HEADER_LINKS}
        profileMenuItems={CUSTOMER_PROFILE_MENU_ITEMS}
        onLogout={vi.fn()}
        {...props}
      />
    </MemoryRouter>,
  )
}

describe('CustomerHeader profile menu', () => {
  it('shows the short name and initials on the profile button', () => {
    renderHeader()

    const button = screen.getByRole('button', { name: 'Account menu for Nimal Perera' })
    expect(within(button).getByText('Nimal P.')).toBeTruthy()
    expect(within(button).getByText('NP')).toBeTruthy()
    expect(button.getAttribute('aria-expanded')).toBe('false')
  })

  it('opens the menu with identity, config items and Log out', () => {
    renderHeader()

    fireEvent.click(screen.getByRole('button', { name: 'Account menu for Nimal Perera' }))

    const menu = screen.getByRole('menu', { name: 'Customer account' })
    expect(within(menu).getByText('nimal@example.com')).toBeTruthy()

    const items = within(menu).getAllByRole('menuitem').map((item) => item.textContent)
    expect(items).toEqual(['My Bookings', 'Settings', 'Help & Support', 'Log out'])
  })

  it('calls onLogout and closes the menu', () => {
    const onLogout = vi.fn()
    renderHeader({ onLogout })

    fireEvent.click(screen.getByRole('button', { name: 'Account menu for Nimal Perera' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Log out' }))

    expect(onLogout).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('closes on Escape and returns focus to the profile button', () => {
    renderHeader()

    const button = screen.getByRole('button', { name: 'Account menu for Nimal Perera' })
    fireEvent.click(button)
    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement).toBe(button)
  })

  it('closes when clicking outside', () => {
    renderHeader()

    fireEvent.click(screen.getByRole('button', { name: 'Account menu for Nimal Perera' }))
    fireEvent.pointerDown(document.body)

    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('moves focus between menu items with the arrow keys', () => {
    renderHeader()

    fireEvent.click(screen.getByRole('button', { name: 'Account menu for Nimal Perera' }))
    const menu = screen.getByRole('menu')
    const items = within(menu).getAllByRole('menuitem')

    expect(document.activeElement).toBe(items[0])
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(items[1])
    fireEvent.keyDown(menu, { key: 'ArrowUp' })
    fireEvent.keyDown(menu, { key: 'ArrowUp' })
    expect(document.activeElement).toBe(items[items.length - 1])
  })
})
