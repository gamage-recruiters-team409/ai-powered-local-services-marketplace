// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/authApi';
import VerifyEmailPage from '../pages/VerifyEmailPage';

vi.mock('../api/authApi');

describe('VerifyEmailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.history.replaceState({}, '', '/');
  });

  afterEach(() => {
    cleanup();
    window.history.replaceState({}, '', '/');
  });

  it('shows error if no token is provided in URL', async () => {
    render(
      <MemoryRouter initialEntries={['/verify-email']}>
        <VerifyEmailPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/no verification token found/i)).toBeTruthy();
      expect(screen.getByRole('heading', { name: /this link has expired or is not valid/i })).toBeTruthy();
    });
    expect(authApi.verifyEmail).not.toHaveBeenCalled();
  });

  it('verifies token exactly once, shows success screen, and strips token from URL', async () => {
    authApi.verifyEmail.mockResolvedValueOnce({
      message: 'Email verified successfully!',
    });

    window.history.replaceState({}, '', '/verify-email?token=valid-tok');

    render(
      <MemoryRouter initialEntries={['/verify-email?token=valid-tok']}>
        <VerifyEmailPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(authApi.verifyEmail).toHaveBeenCalledTimes(1);
      expect(authApi.verifyEmail).toHaveBeenCalledWith('valid-tok');
      expect(screen.getByRole('heading', { name: /^email verified$/i })).toBeTruthy();
      expect(screen.getByRole('link', { name: /^log in$/i })).toBeTruthy();
    });

    expect(window.location.search).not.toContain('token=');
  });

  it('shows failure screen when token is expired or invalid', async () => {
    authApi.verifyEmail.mockRejectedValueOnce({
      code: 'INVALID_OR_EXPIRED_TOKEN',
      message: 'This link is invalid or has expired.',
    });

    render(
      <MemoryRouter initialEntries={['/verify-email?token=expired-tok']}>
        <VerifyEmailPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /this link has expired or is not valid/i })).toBeTruthy();
      expect(screen.getByText(/request a new verification link/i)).toBeTruthy();
      const resendLink = screen.getByRole('link', { name: /resend verification email/i });
      expect(resendLink).toBeTruthy();
      expect(resendLink.getAttribute('href')).toBe('/check-email');
      expect(screen.getByRole('link', { name: /back to login/i })).toBeTruthy();
    });
  });
});
