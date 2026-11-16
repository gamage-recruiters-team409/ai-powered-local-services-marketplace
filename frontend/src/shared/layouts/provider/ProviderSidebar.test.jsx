// @vitest-environment jsdom

import { createRef } from 'react'
import {
  cleanup,
  render,
} from '@testing-library/react'
import {
  MemoryRouter,
} from 'react-router-dom'
import {
  afterEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest'

import {
  ProviderSidebar,
} from './ProviderSidebar'

const user = {
  name: 'Nimal Perera',
  email: 'nimal@example.com',
}

const sections = [
  {
    id: 'provider',
    title: 'MY PROVIDER',
    items: [
      {
        id: 'dashboard',
        label: 'Dashboard',
        to: '/provider',
        icon: 'dashboard',
        end: true,
      },
      {
        id: 'profile',
        label: 'My Profile',
        to: '/provider/profile',
        icon: 'user',
      },
    ],
  },
]

function renderSidebar({
  mobile = false,
  open = false,
  collapsed = false,
} = {}) {
  return render(
    <MemoryRouter
      initialEntries={['/provider']}
    >
      <ProviderSidebar
        id="provider-sidebar"
        mobile={mobile}
        open={open}
        collapsed={collapsed}
        user={user}
        sections={sections}
        drawerRef={createRef()}
        onNavigate={vi.fn()}
        onLogout={vi.fn()}
      />
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
})

describe('ProviderSidebar', () => {
  it(
    'removes collapsed desktop navigation from the tab order',
    () => {
      const { container } =
        renderSidebar({
          mobile: false,
          collapsed: true,
        })

      const sidebar =
        container.querySelector(
          'aside',
        )

      const dashboard =
        container.querySelector(
          'a[href="/provider"]',
        )

      const logout =
        container.querySelector(
          'button',
        )

      expect(
        sidebar.hasAttribute(
          'inert',
        ),
      ).toBe(true)

      expect(
        sidebar.getAttribute(
          'aria-hidden',
        ),
      ).toBe('true')

      expect(
        dashboard.tabIndex,
      ).toBe(-1)

      expect(
        logout.tabIndex,
      ).toBe(-1)
    },
  )

  it(
    'restores desktop navigation accessibility after expansion',
    () => {
      const { container } =
        renderSidebar({
          mobile: false,
          collapsed: false,
        })

      const sidebar =
        container.querySelector(
          'aside',
        )

      const dashboard =
        container.querySelector(
          'a[href="/provider"]',
        )

      const logout =
        container.querySelector(
          'button',
        )

      expect(
        sidebar.hasAttribute(
          'inert',
        ),
      ).toBe(false)

      expect(
        dashboard.tabIndex,
      ).toBe(0)

      expect(
        logout.tabIndex,
      ).toBe(0)
    },
  )

  it(
    'exposes an opened mobile drawer as an interactive modal dialog',
    () => {
      const { container } =
        renderSidebar({
          mobile: true,
          open: true,
        })

      const sidebar =
        container.querySelector(
          'aside',
        )

      expect(
        sidebar.getAttribute(
          'role',
        ),
      ).toBe('dialog')

      expect(
        sidebar.getAttribute(
          'aria-modal',
        ),
      ).toBe('true')

      expect(
        sidebar.hasAttribute(
          'inert',
        ),
      ).toBe(false)
    },
  )
})