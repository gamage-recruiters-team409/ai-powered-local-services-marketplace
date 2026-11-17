// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as useAuthModule from '../hooks/useAuth';
import GuestOnly from '../guards/GuestOnly';
import RequireAuth from '../guards/RequireAuth';
import RequireRole from '../guards/RequireRole';
import RequirePasswordChanged from '../guards/RequirePasswordChanged';

vi.mock('../hooks/useAuth');

describe('Auth Guards', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  describe('RequireAuth', () => {
    it('redirects unauthenticated users to /login', () => {
      useAuthModule.useAuth.mockReturnValue({
        isAuthenticated: false,
        isLoading: false,
      });

      render(
        <MemoryRouter initialEntries={['/protected']}>
          <Routes>
            <Route
              path="/protected"
              element={
                <RequireAuth>
                  <div>Protected Content</div>
                </RequireAuth>
              }
            />
            <Route path="/login" element={<div>Login Page</div>} />
          </Routes>
        </MemoryRouter>
      );

      expect(screen.getByText('Login Page')).toBeTruthy();
      expect(screen.queryByText('Protected Content')).toBeNull();
    });

    it('renders children for authenticated users', () => {
      useAuthModule.useAuth.mockReturnValue({
        isAuthenticated: true,
        isLoading: false,
      });

      render(
        <MemoryRouter initialEntries={['/protected']}>
          <Routes>
            <Route
              path="/protected"
              element={
                <RequireAuth>
                  <div>Protected Content</div>
                </RequireAuth>
              }
            />
          </Routes>
        </MemoryRouter>
      );

      expect(screen.getByText('Protected Content')).toBeTruthy();
    });
  });

  describe('GuestOnly', () => {
    it('redirects authenticated customer to role home (/)', () => {
      useAuthModule.useAuth.mockReturnValue({
        isAuthenticated: true,
        isLoading: false,
        user: { role: 'CUSTOMER' },
      });

      render(
        <MemoryRouter initialEntries={['/login']}>
          <Routes>
            <Route
              path="/login"
              element={
                <GuestOnly>
                  <div>Login Form</div>
                </GuestOnly>
              }
            />
            <Route path="/" element={<div>Customer Home</div>} />
          </Routes>
        </MemoryRouter>
      );

      expect(screen.getByText('Customer Home')).toBeTruthy();
    });

    it('renders login form for unauthenticated users', () => {
      useAuthModule.useAuth.mockReturnValue({
        isAuthenticated: false,
        isLoading: false,
      });

      render(
        <MemoryRouter initialEntries={['/login']}>
          <Routes>
            <Route
              path="/login"
              element={
                <GuestOnly>
                  <div>Login Form</div>
                </GuestOnly>
              }
            />
          </Routes>
        </MemoryRouter>
      );

      expect(screen.getByText('Login Form')).toBeTruthy();
    });
  });

  describe('RequireRole', () => {
    it('denies access if user does not have required role', () => {
      useAuthModule.useAuth.mockReturnValue({
        isAuthenticated: true,
        isLoading: false,
        hasRole: vi.fn().mockReturnValue(false),
      });

      render(
        <MemoryRouter initialEntries={['/admin-secret']}>
          <Routes>
            <Route
              path="/admin-secret"
              element={
                <RequireRole roles={['ADMIN']}>
                  <div>Admin Secret</div>
                </RequireRole>
              }
            />
            <Route path="/" element={<div>Home</div>} />
          </Routes>
        </MemoryRouter>
      );

      expect(screen.getByText('Home')).toBeTruthy();
      expect(screen.queryByText('Admin Secret')).toBeNull();
    });
  });

  describe('RequirePasswordChanged', () => {
    it('redirects to /change-password when mustChangePassword is true', () => {
      useAuthModule.useAuth.mockReturnValue({
        isAuthenticated: true,
        isLoading: false,
        mustChangePassword: true,
      });

      render(
        <MemoryRouter initialEntries={['/dashboard']}>
          <Routes>
            <Route
              path="/dashboard"
              element={
                <RequirePasswordChanged>
                  <div>Dashboard</div>
                </RequirePasswordChanged>
              }
            />
            <Route path="/change-password" element={<div>Change Password Page</div>} />
          </Routes>
        </MemoryRouter>
      );

      expect(screen.getByText('Change Password Page')).toBeTruthy();
    });
  });
});
