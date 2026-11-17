// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/authApi';
import ResetPasswordPage from '../pages/ResetPasswordPage';

vi.mock('../api/authApi');

describe('ResetPasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.history.replaceState({}, '', '/');
  });

  afterEach(() => {
    cleanup();
    window.history.replaceState({}, '', '/');
  });

  it('renders expired view if no token is provided', () => {
    render(
      <MemoryRouter initialEntries={['/reset-password']}>
        <ResetPasswordPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /link expired/i })).toBeTruthy();
    expect(screen.getByRole('link', { name: /request new link/i })).toBeTruthy();
    expect(authApi.resetPassword).not.toHaveBeenCalled();
  });

  it('renders password reset form without making API calls on mount, and strips token from URL', () => {
    window.history.replaceState({}, '', '/reset-password?token=valid-tok');

    render(
      <MemoryRouter initialEntries={['/reset-password?token=valid-tok']}>
        <ResetPasswordPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /set a new password/i })).toBeTruthy();
    expect(screen.getByText(/0\/4 criteria/i)).toBeTruthy();
    expect(screen.getByText(/at least 8 characters/i)).toBeTruthy();
    expect(screen.getByText(/an uppercase letter/i)).toBeTruthy();
    expect(screen.getByText(/a lowercase letter/i)).toBeTruthy();
    expect(screen.getByText(/a digit/i)).toBeTruthy();

    // Verify NO API calls were made on mount
    expect(authApi.resetPassword).not.toHaveBeenCalled();

    // Verify token was stripped from URL
    expect(window.location.search).not.toContain('token=');
  });

  it('submits valid password and shows success screen', async () => {
    authApi.resetPassword.mockResolvedValueOnce({ message: 'Success' });

    render(
      <MemoryRouter initialEntries={['/reset-password?token=valid-tok']}>
        <ResetPasswordPage />
      </MemoryRouter>
    );

    const input = screen.getByPlaceholderText(/^enter new password$/i);
    fireEvent.change(input, { target: { value: 'NewValidPassword123' } });

    expect(screen.getByText(/4\/4 criteria/i)).toBeTruthy();

    const submitBtn = screen.getByRole('button', { name: /reset password/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(authApi.resetPassword).toHaveBeenCalledWith('valid-tok', 'NewValidPassword123');
      expect(screen.getByRole('heading', { name: /password reset successful/i })).toBeTruthy();
      expect(screen.getByRole('link', { name: /continue to login/i })).toBeTruthy();
    });
  });
});
