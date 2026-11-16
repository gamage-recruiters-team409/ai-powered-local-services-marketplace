// @vitest-environment jsdom

import { createRef } from 'react'
import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react'
import {
  afterEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest'

import { ProviderHeader } from './ProviderHeader'

const brand = {
  name: 'LocalServe',
  logoSrc: null,
}

const user = {
  name: 'Nimal Perera',
  email: 'nimal@example.com',
}

afterEach(() => {
  cleanup()
})

function renderHeader({
  notificationCount = 0,
  onNotificationsClick = vi.fn(),
  onLogout = vi.fn(),
} = {}) {
  return render(
    <ProviderHeader
      brand={brand}
      user={user}
      sidebarId="provider-sidebar"
      drawerOpen={false}
      menuButtonRef={createRef()}
      onMenuClick={vi.fn()}
      notificationCount={notificationCount}
      onNotificationsClick={onNotificationsClick}
      onLogout={onLogout}
    />,
  )
}

describe('ProviderHeader', () => {
  it('renders the provider identity and brand', () => {
    renderHeader()

    expect(screen.getByText('LocalServe')).toBeTruthy()
    expect(screen.getByText('Nimal Perera')).toBeTruthy()
    expect(screen.getByText('NP')).toBeTruthy()
  })

  it('does not show a notification badge when the count is zero', () => {
    renderHeader({
      notificationCount: 0,
    })

    const button = screen.getByRole(
      'button',
      { name: 'Notifications' },
    )

    expect(button).toBeTruthy()
    expect(
      within(button).queryByText('0'),
    ).toBeNull()
  })

  it('shows the real notification count when it is greater than zero', () => {
    renderHeader({
      notificationCount: 3,
    })

    const button = screen.getByRole(
      'button',
      {
        name: 'Notifications, 3 unread',
      },
    )

    expect(
      within(button).getByText('3'),
    ).toBeTruthy()
  })

  it('opens the profile menu and calls logout', () => {
    const onLogout = vi.fn()

    renderHeader({
      onLogout,
    })

    fireEvent.click(
      screen.getByRole(
        'button',
        {
          name: 'Account menu for Nimal Perera',
        },
      ),
    )

    const logoutButton = screen.getByRole(
      'menuitem',
      { name: 'Log out' },
    )

    expect(logoutButton).toBeTruthy()

    fireEvent.click(logoutButton)

    expect(onLogout).toHaveBeenCalledTimes(1)
  })
})