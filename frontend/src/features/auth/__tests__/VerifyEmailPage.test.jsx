// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/authApi';
import VerifyEmailPage from '../pages/VerifyEmailPage';

vi.mock('../api/authApi');

describe('VerifyEmailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows error if no token is provided in URL', async () => {
    render(
      <MemoryRouter initialEntries={['/verify-email']}>
        <VerifyEmailPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/no verification token found/i)).toBeTruthy();
      expect(screen.getByRole('heading', { name: /verification failed/i })).toBeTruthy();
    });
  });

  it('verifies token and shows success screen', async () => {
    authApi.verifyEmail.mockResolvedValueOnce({
      message: 'Email verified successfully!',
    });

    render(
      <MemoryRouter initialEntries={['/verify-email?token=valid-tok']}>
        <VerifyEmailPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(authApi.verifyEmail).toHaveBeenCalledWith('valid-tok');
      expect(screen.getByRole('heading', { name: /email verified!/i })).toBeTruthy();
      expect(screen.getByRole('link', { name: /continue to login/i })).toBeTruthy();
    });
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
      expect(screen.getByRole('heading', { name: /verification failed/i })).toBeTruthy();
      expect(screen.getByText(/this link is invalid or has expired/i)).toBeTruthy();
      expect(screen.getByRole('link', { name: /back to login/i })).toBeTruthy();
    });
  });
});
