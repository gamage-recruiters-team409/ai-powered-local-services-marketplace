// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/authApi';
import ResetPasswordPage from '../pages/ResetPasswordPage';

vi.mock('../api/authApi');

describe('ResetPasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders expired view if no token is provided', () => {
    render(
      <MemoryRouter initialEntries={['/reset-password']}>
        <ResetPasswordPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /link expired/i })).toBeTruthy();
    expect(screen.getByRole('link', { name: /request new link/i })).toBeTruthy();
  });

  it('renders password reset form and live requirements when token is present', () => {
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
  });

  it('submits valid password and shows success screen', async () => {
    authApi.resetPassword.mockResolvedValueOnce({ message: 'Success' });

    render(
      <MemoryRouter initialEntries={['/reset-password?token=valid-tok']}>
        <ResetPasswordPage />
      </MemoryRouter>
    );

    const input = screen.getByPlaceholderText(/enter new password/i);
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
