// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/authApi';
import ForgotPasswordPage from '../pages/ForgotPasswordPage';

vi.mock('../api/authApi');

describe('ForgotPasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders email input and send reset link button', () => {
    render(
      <MemoryRouter>
        <ForgotPasswordPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /forgot your password\?/i })).toBeTruthy();
    expect(screen.getByPlaceholderText(/e\.g\. kasun@example\.lk/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: /send reset link/i })).toBeTruthy();
  });

  it('validates email on empty submit', async () => {
    render(
      <MemoryRouter>
        <ForgotPasswordPage />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /send reset link/i }));

    await waitFor(() => {
      expect(screen.getByText(/please enter your email address/i)).toBeTruthy();
    });
    expect(authApi.forgotPassword).not.toHaveBeenCalled();
  });

  it('submits email and switches to check email success screen', async () => {
    authApi.forgotPassword.mockResolvedValueOnce({ message: 'Success' });

    render(
      <MemoryRouter>
        <ForgotPasswordPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByPlaceholderText(/e\.g\. kasun@example\.lk/i), {
      target: { value: 'kasun@example.lk' },
    });
    fireEvent.click(screen.getByRole('button', { name: /send reset link/i }));

    await waitFor(() => {
      expect(authApi.forgotPassword).toHaveBeenCalledWith('kasun@example.lk');
      expect(screen.getByRole('heading', { name: /check your email/i })).toBeTruthy();
      expect(screen.getByText(/if this email is registered, we have sent a reset link/i)).toBeTruthy();
    });
  });
});
