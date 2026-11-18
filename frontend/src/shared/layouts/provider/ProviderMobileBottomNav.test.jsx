// @vitest-environment jsdom

import {
  cleanup,
  render,
  screen,
  within,
} from '@testing-library/react'
import {
  MemoryRouter,
} from 'react-router-dom'
import {
  afterEach,
  describe,
  expect,
  it,
} from 'vitest'

import {
  ProviderMobileBottomNav,
} from './ProviderMobileBottomNav'

const items = [
  {
    id: 'dashboard',
    label: 'Dashboard',
    to: '/provider',
    icon: 'dashboard',
    end: true,
  },
  {
    id: 'listings',
    label: 'Listings',
    to: '/provider/listings',
    icon: 'listings',
  },
  {
    id: 'bookings',
    label: 'Bookings',
    to: '/provider/bookings',
    icon: 'bookings',
  },
  {
    id: 'messages',
    label: 'Messages',
    to: '/provider/messages',
    icon: 'messages',
  },
  {
    id: 'account',
    label: 'Account',
    to: '/provider/profile',
    icon: 'account',
  },
]

afterEach(() => {
  cleanup()
})

function renderNavigation(
  messageCount = 0,
) {
  return render(
    <MemoryRouter
      initialEntries={['/provider']}
    >
      <ProviderMobileBottomNav
        items={items}
        messageCount={messageCount}
      />
    </MemoryRouter>,
  )
}

describe(
  'ProviderMobileBottomNav',
  () => {
    it(
      'renders the provider mobile navigation items',
      () => {
        renderNavigation()

        expect(
          screen.getByRole(
            'link',
            { name: 'Dashboard' },
          ),
        ).toBeTruthy()

        expect(
          screen.getByRole(
            'link',
            { name: 'Listings' },
          ),
        ).toBeTruthy()

        expect(
          screen.getByRole(
            'link',
            { name: 'Bookings' },
          ),
        ).toBeTruthy()

        expect(
          screen.getByRole(
            'link',
            { name: 'Messages' },
          ),
        ).toBeTruthy()

        expect(
          screen.getByRole(
            'link',
            { name: 'Account' },
          ),
        ).toBeTruthy()
      },
    )

    it(
      'does not display a message badge when the count is zero',
      () => {
        renderNavigation(0)

        const messages =
          screen.getByRole(
            'link',
            { name: 'Messages' },
          )

        expect(
          within(messages)
            .queryByText('0'),
        ).toBeNull()
      },
    )

    it(
      'displays the supplied unread message count',
      () => {
        renderNavigation(4)

        const messages =
          screen.getByRole(
            'link',
            { name: 'Messages' },
          )

        expect(
          within(messages)
            .getByText('4'),
        ).toBeTruthy()
      },
    )

    it(
      'caps large unread counts at 99+',
      () => {
        renderNavigation(120)

        const messages =
          screen.getByRole(
            'link',
            { name: 'Messages' },
          )

        expect(
          within(messages)
            .getByText('99+'),
        ).toBeTruthy()
      },
    )
  },
)