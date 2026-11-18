// @vitest-environment jsdom

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import {
  MemoryRouter,
} from 'react-router-dom'
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest'

import {
  ProviderLayout,
} from './ProviderLayout'

function installMobileMatchMedia() {
  const mediaQuery = {
    matches: true,
    media: '(max-width: 767px)',
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }

  Object.defineProperty(
    window,
    'matchMedia',
    {
      configurable: true,
      writable: true,
      value: vi
        .fn()
        .mockReturnValue(
          mediaQuery,
        ),
    },
  )
}

function renderMobileLayout() {
  return render(
    <MemoryRouter
      initialEntries={['/provider']}
    >
      <ProviderLayout
        user={{
          name: 'Nimal Perera',
          email:
            'nimal@example.com',
        }}
        notificationCount={2}
        messageCount={3}
        onNotificationsClick={
          vi.fn()
        }
        onLogout={vi.fn()}
      >
        <button type="button">
          Page action
        </button>
      </ProviderLayout>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  installMobileMatchMedia()
})

afterEach(() => {
  cleanup()
})

describe(
  'ProviderLayout mobile drawer',
  () => {
    it(
      'moves focus into the drawer, traps Tab, and restores focus on Escape',
      async () => {
        renderMobileLayout()

        const menuButton =
          screen.getByRole(
            'button',
            {
              name:
                'Open navigation menu',
            },
          )

        fireEvent.click(
          menuButton,
        )

        const drawer =
          await screen.findByRole(
            'dialog',
            {
              name:
                'Provider navigation menu',
            },
          )

        await waitFor(() => {
          expect(
            drawer.contains(
              document.activeElement,
            ),
          ).toBe(true)
        })

        const first =
          within(drawer).getByRole(
            'link',
            {
              name:
                'Dashboard',
            },
          )

        const last =
          within(drawer).getByRole(
            'button',
            {
              name:
                'Log out',
            },
          )

        last.focus()

        fireEvent.keyDown(
          document,
          {
            key: 'Tab',
          },
        )

        expect(
          document.activeElement,
        ).toBe(first)

        first.focus()

        fireEvent.keyDown(
          document,
          {
            key: 'Tab',
            shiftKey: true,
          },
        )

        expect(
          document.activeElement,
        ).toBe(last)

        fireEvent.keyDown(
          document,
          {
            key: 'Escape',
          },
        )

        await waitFor(() => {
          expect(
            screen.queryByRole(
              'dialog',
              {
                name:
                  'Provider navigation menu',
              },
            ),
          ).toBeNull()
        })

        const restoredMenuButton =
          screen.getByRole(
            'button',
            {
              name:
                'Open navigation menu',
            },
          )

        await waitFor(() => {
          expect(
            document.activeElement,
          ).toBe(
            restoredMenuButton,
          )
        })
      },
    )

    it(
      'makes background controls inert while the drawer is open',
      async () => {
        const { container } =
          renderMobileLayout()

        fireEvent.click(
          screen.getByRole(
            'button',
            {
              name:
                'Open navigation menu',
            },
          ),
        )

        await screen.findByRole(
          'dialog',
          {
            name:
              'Provider navigation menu',
          },
        )

        const header =
          container.querySelector(
            'header',
          )

        const main =
          container.querySelector(
            'main',
          )

        const footer =
          container.querySelector(
            'footer',
          )

        const bottomNav =
          container.querySelector(
            'nav[aria-label="Provider mobile navigation"]',
          )

        expect(
          header.hasAttribute(
            'inert',
          ),
        ).toBe(true)

        expect(
          main.hasAttribute(
            'inert',
          ),
        ).toBe(true)

        expect(
          footer.hasAttribute(
            'inert',
          ),
        ).toBe(true)

        expect(
          bottomNav.hasAttribute(
            'inert',
          ),
        ).toBe(true)
      },
    )

    it(
      'moves focus to main content after mobile navigation',
      async () => {
        const { container } =
          renderMobileLayout()

        fireEvent.click(
          screen.getByRole(
            'button',
            {
              name:
                'Open navigation menu',
            },
          ),
        )

        const drawer =
          await screen.findByRole(
            'dialog',
            {
              name:
                'Provider navigation menu',
            },
          )

        fireEvent.click(
          within(drawer).getByRole(
            'link',
            {
              name:
                'My Profile',
            },
          ),
        )

        await waitFor(() => {
          expect(
            screen.queryByRole(
              'dialog',
              {
                name:
                  'Provider navigation menu',
              },
            ),
          ).toBeNull()
        })

        const main =
          container.querySelector(
            'main',
          )

        await waitFor(() => {
          expect(
            document.activeElement,
          ).toBe(main)
        })
      },
    )

    it(
      'closes on backdrop click and restores focus to the menu button',
      async () => {
        renderMobileLayout()

        const menuButton =
          screen.getByRole(
            'button',
            {
              name:
                'Open navigation menu',
            },
          )

        fireEvent.click(
          menuButton,
        )

        await screen.findByRole(
          'dialog',
          {
            name:
              'Provider navigation menu',
          },
        )

        const backdrop =
          screen.getByTestId(
            'provider-drawer-backdrop',
          )

        fireEvent.mouseDown(
          backdrop,
        )

        fireEvent.click(
          backdrop,
        )

        await waitFor(() => {
          expect(
            screen.queryByRole(
              'dialog',
              {
                name:
                  'Provider navigation menu',
              },
            ),
          ).toBeNull()
        })

        await waitFor(() => {
          expect(
            document.activeElement,
          ).toBe(menuButton)
        })
      },
    )
  },
)