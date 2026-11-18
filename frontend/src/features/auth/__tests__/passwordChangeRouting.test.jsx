// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import tokenStore from '../../../shared/services/tokenStore';
import { refreshSession } from '../../../shared/services/apiClient';
import { AuthProvider } from '../context/AuthContext';
import * as useAuthModule from '../hooks/useAuth';
import RequireAuth from '../guards/RequireAuth';
import RequirePasswordChanged from '../guards/RequirePasswordChanged';
import RequireRole from '../guards/RequireRole';
import ForceChangePasswordPage from '../pages/ForceChangePasswordPage';

vi.mock('../hooks/useAuth');
vi.mock('../../../shared/services/apiClient', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    refreshSession: vi.fn(),
  };
});
vi.mock('../api/authApi', () => ({
  changePassword: vi.fn(),
  getCurrentUser: vi.fn(),
}));

// Reusable protected route layout: RequireAuth -> RequirePasswordChanged -> (RequireRole) -> page
function ProtectedCustomerLayout() {
  return (
    <RequireAuth>
      <RequirePasswordChanged>
        <RequireRole roles={['CUSTOMER']}>
          <Outlet />
        </RequireRole>
      </RequirePasswordChanged>
    </RequireAuth>
  );
}

function ProtectedAdminLayout() {
  return (
    <RequireAuth>
      <RequirePasswordChanged>
        <RequireRole roles={['ADMIN']}>
          <Outlet />
        </RequireRole>
      </RequirePasswordChanged>
    </RequireAuth>
  );
}

describe('Mandatory Password Change Routing Enforcement', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    tokenStore.clear();
  });

  afterEach(() => {
    cleanup();
    tokenStore.clear();
  });

  it('mustChangePassword=true + direct navigation to a customer route and admin route lands on /change-password', () => {
    useAuthModule.useAuth.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      mustChangePassword: true,
      hasRole: () => true,
    });

    // 1. Direct navigation to customer route
    const { unmount } = render(
      <MemoryRouter initialEntries={['/customer/dashboard']}>
        <Routes>
          <Route element={<ProtectedCustomerLayout />}>
            <Route path="/customer/dashboard" element={<div>Customer Dashboard</div>} />
          </Route>
          <Route path="/change-password" element={<div>Change Password Screen</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('Change Password Screen')).toBeTruthy();
    expect(screen.queryByText('Customer Dashboard')).toBeNull();
    unmount();

    // 2. Direct navigation to admin route
    render(
      <MemoryRouter initialEntries={['/admin/disputes']}>
        <Routes>
          <Route element={<ProtectedAdminLayout />}>
            <Route path="/admin/disputes" element={<div>Admin Disputes</div>} />
          </Route>
          <Route path="/change-password" element={<div>Change Password Screen</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('Change Password Screen')).toBeTruthy();
    expect(screen.queryByText('Admin Disputes')).toBeNull();
  });

  it('mustChangePassword=true + session restoration (refresh) lands on /change-password', async () => {
    // Unmock useAuth to test full integration with AuthProvider session restoration
    useAuthModule.useAuth.mockImplementation(
      await vi.importActual('../hooks/useAuth').then((m) => m.useAuth)
    );

    tokenStore.setRefreshToken('valid-refresh-token');
    refreshSession.mockResolvedValueOnce({
      accessToken: 'temp-access',
      refreshToken: 'temp-refresh',
      mustChangePassword: true,
      user: { id: 'u1', role: 'CUSTOMER', mustChangePassword: true },
    });

    render(
      <AuthProvider>
        <MemoryRouter initialEntries={['/customer/dashboard']}>
          <Routes>
            <Route element={<ProtectedCustomerLayout />}>
              <Route path="/customer/dashboard" element={<div>Customer Dashboard</div>} />
            </Route>
            <Route path="/change-password" element={<div>Change Password Screen</div>} />
            <Route path="/login" element={<div>Login Screen</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Change Password Screen')).toBeTruthy();
    });
    expect(screen.queryByText('Customer Dashboard')).toBeNull();
  });

  it('/change-password renders for that user with no redirect loop', () => {
    useAuthModule.useAuth.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      mustChangePassword: true,
      user: { id: 'u1', role: 'CUSTOMER' },
    });

    render(
      <MemoryRouter initialEntries={['/change-password']}>
        <Routes>
          <Route
            path="/change-password"
            element={
              <RequireAuth>
                <ForceChangePasswordPage />
              </RequireAuth>
            }
          />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /change your password/i })).toBeTruthy();
  });

  it('mustChangePassword=false renders protected routes normally', () => {
    useAuthModule.useAuth.mockReturnValue({
      isAuthenticated: true,
      isLoading: false,
      mustChangePassword: false,
      hasRole: (roles) => roles.includes('CUSTOMER'),
    });

    render(
      <MemoryRouter initialEntries={['/customer/dashboard']}>
        <Routes>
          <Route element={<ProtectedCustomerLayout />}>
            <Route path="/customer/dashboard" element={<div>Customer Dashboard Content</div>} />
          </Route>
          <Route path="/change-password" element={<div>Change Password Screen</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('Customer Dashboard Content')).toBeTruthy();
    expect(screen.queryByText('Change Password Screen')).toBeNull();
  });

  it('unauthenticated redirects to /login as before', () => {
    useAuthModule.useAuth.mockReturnValue({
      isAuthenticated: false,
      isLoading: false,
      mustChangePassword: false,
      hasRole: () => false,
    });

    render(
      <MemoryRouter initialEntries={['/customer/dashboard']}>
        <Routes>
          <Route element={<ProtectedCustomerLayout />}>
            <Route path="/customer/dashboard" element={<div>Customer Dashboard Content</div>} />
          </Route>
          <Route path="/login" element={<div>Login Screen</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('Login Screen')).toBeTruthy();
    expect(screen.queryByText('Customer Dashboard Content')).toBeNull();
  });
});
