// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const auth = vi.hoisted(() => ({ current: null }))

vi.mock('../../features/auth/hooks/useAuth', () => ({
  useAuth: () => auth.current,
  default: () => auth.current,
}))

import { customerRoutes, CUSTOMER_ACCOUNT_ROUTES, CUSTOMER_PAGE_ROUTES } from './customerRoutes'

function signedIn({ role = 'CUSTOMER', mustChangePassword = false } = {}) {
  return {
    user: { id: 'u-1', name: 'Nimal Perera', email: 'nimal@example.com', role },
    isLoading: false,
    isAuthenticated: true,
    mustChangePassword,
    hasRole: (roles) => (Array.isArray(roles) ? roles.includes(role) : roles === role),
    logout: vi.fn().mockResolvedValue(undefined),
  }
}

function renderAt(path) {
  const router = createMemoryRouter(
    [
      customerRoutes,
      { path: '/login', element: <p>Login page</p> },
      { path: '/change-password', element: <p>Change password page</p> },
      { path: '/', element: <p>Home page</p> },
    ],
    { initialEntries: [path] },
  )
  render(<RouterProvider router={router} />)
  return router
}

beforeEach(() => {
  auth.current = signedIn()
})

afterEach(() => {
  cleanup()
})

describe('customerRoutes guards (RequireAuth → RequirePasswordChanged → RequireRole(CUSTOMER))', () => {
  it('sends a signed-out visitor to /login', () => {
    auth.current = {
      user: null,
      isLoading: false,
      isAuthenticated: false,
      mustChangePassword: false,
      hasRole: () => false,
      logout: vi.fn(),
    }

    renderAt('/customer/account')

    expect(screen.getByText('Login page')).toBeTruthy()
  })

  it('sends a customer who must change the password to /change-password', () => {
    auth.current = signedIn({ mustChangePassword: true })

    renderAt('/customer/account')

    expect(screen.getByText('Change password page')).toBeTruthy()
  })

  it.each(['PROVIDER', 'ADMIN', 'SUPER_ADMIN'])('redirects a %s away from the customer area', (role) => {
    auth.current = signedIn({ role })

    renderAt('/customer/account')

    expect(screen.getByText('Home page')).toBeTruthy()
  })

  it('shows a loading state while the session is being checked', () => {
    auth.current = { ...signedIn(), isLoading: true, isAuthenticated: false }

    renderAt('/customer/account')

    expect(screen.queryByText('Login page')).toBeNull()
    expect(screen.queryByRole('main')).toBeNull()
  })
})

describe('customerRoutes for a signed-in customer', () => {
  it('renders the account page inside the layout with the account sidebar', () => {
    renderAt('/customer/account')

    expect(screen.getByRole('heading', { level: 1, name: 'Account' })).toBeTruthy()
    const sidebar = screen.getByRole('complementary', { name: 'Account sidebar' })
    expect(within(sidebar).getByText('Nimal Perera')).toBeTruthy()
    expect(within(sidebar).getByText('nimal@example.com')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Account menu for Nimal Perera' })).toBeTruthy()
  })

  it('calls logout from the layout', () => {
    renderAt('/customer/account')

    const sidebar = screen.getByRole('complementary', { name: 'Account sidebar' })
    fireEvent.click(within(sidebar).getByRole('button', { name: 'Log out' }))

    expect(auth.current.logout).toHaveBeenCalledTimes(1)
  })
})

describe('customerRoutes structure', () => {
  it('registers the account page and keeps the page groups available for module owners', () => {
    expect(CUSTOMER_ACCOUNT_ROUTES.map((route) => route.path)).toContain('/customer/account')
    expect(Array.isArray(CUSTOMER_PAGE_ROUTES)).toBe(true)
  })

  it('does not define the root path', () => {
    const paths = []
    const collect = (routes) => routes.forEach((route) => {
      if (route.path) paths.push(route.path)
      if (route.children) collect(route.children)
    })
    collect([customerRoutes])

    expect(paths).not.toContain('/')
    expect(paths.every((path) => path.startsWith('/customer'))).toBe(true)
  })
})
