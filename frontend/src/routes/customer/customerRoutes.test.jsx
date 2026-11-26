// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const auth = vi.hoisted(() => ({ current: null }))

vi.mock('../../features/auth/hooks/useAuth', () => ({
  useAuth: () => auth.current,
  default: () => auth.current,
}))

vi.mock('../../features/customer-account/services/customerAccountApi', () => ({
  getProfile: vi.fn().mockResolvedValue({
    fullName: 'Nimal Perera',
    email: 'nimal@example.com',
    emailVerified: true,
    phoneNumber: '+94 77 123 4567',
  }),
  updateProfile: vi.fn().mockResolvedValue({}),
  getAddresses: vi.fn().mockResolvedValue([]),
  getDistricts: vi.fn().mockResolvedValue([]),
}))

import { ToastProvider } from '../../shared/components/feedback'
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
  render(
    <ToastProvider>
      <RouterProvider router={router} />
    </ToastProvider>,
  )
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

    expect(screen.getByRole('heading', { level: 1, name: 'Profile' })).toBeTruthy()
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

  it('passes avatarUrl to CustomerLayout and updates header when mocked user changes', () => {
    auth.current = {
      ...signedIn(),
      user: {
        id: 'u-1',
        name: 'Initial Name',
        email: 'initial@example.com',
        role: 'CUSTOMER',
        profileImageUrl: 'https://cdn.example.com/initial.jpg',
      },
    }

    renderAt('/customer/account')
    const button = screen.getByRole('button', { name: /Account menu for Initial Name/i })
    expect(within(button).getByText('Initial N.')).toBeTruthy()
    const img = button.querySelector('img')
    expect(img).toBeTruthy()
    expect(img.getAttribute('src')).toBe('https://cdn.example.com/initial.jpg')

    cleanup()

    auth.current = {
      ...signedIn(),
      user: {
        id: 'u-1',
        name: 'Updated Name',
        email: 'updated@example.com',
        role: 'CUSTOMER',
        profileImageUrl: 'https://cdn.example.com/updated.jpg',
      },
    }

    renderAt('/customer/account')
    const updatedButton = screen.getByRole('button', { name: /Account menu for Updated Name/i })
    expect(within(updatedButton).getByText('Updated N.')).toBeTruthy()
    const updatedImg = updatedButton.querySelector('img')
    expect(updatedImg).toBeTruthy()
    expect(updatedImg.getAttribute('src')).toBe('https://cdn.example.com/updated.jpg')
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
