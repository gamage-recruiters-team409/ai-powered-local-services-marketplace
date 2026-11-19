// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as useAuthModule from '../hooks/useAuth';
import LoginPage from '../pages/LoginPage';

vi.mock('../hooks/useAuth');
vi.mock('../api/authApi');

describe('LoginPage', () => {
  const mockLogin = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    useAuthModule.useAuth.mockReturnValue({
      login: mockLogin,
      isAuthenticated: false,
      isLoading: false,
    });
  });

  afterEach(() => {
    cleanup();
  });

  it('renders login form with email, password fields and buttons', () => {
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /log in/i })).toBeTruthy();
    expect(screen.getByPlaceholderText(/e\.g\. kasun@example\.lk/i)).toBeTruthy();
    expect(screen.getByPlaceholderText(/enter your password/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: /log in/i })).toBeTruthy();
    expect(screen.getByRole('link', { name: /forgot password\?/i })).toBeTruthy();
    expect(screen.getByRole('link', { name: /create an account/i })).toBeTruthy();
  });

  it('validates email on submit', async () => {
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/please enter your email address/i)).toBeTruthy();
    });
    expect(mockLogin).not.toHaveBeenCalled();
  });

  it('shows bad credentials error alert on failed login', async () => {
    mockLogin.mockRejectedValueOnce({
      code: 'BAD_CREDENTIALS',
      message: 'Bad credentials',
    });

    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByPlaceholderText(/e\.g\. kasun@example\.lk/i), {
      target: { value: 'kasun@example.lk' },
    });
    fireEvent.change(screen.getByPlaceholderText(/enter your password/i), {
      target: { value: 'WrongPass123!' },
    });

    fireEvent.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/incorrect email or password/i)).toBeTruthy();
    });
  });

  it('shows account locked alert on ACCOUNT_LOCKED code', async () => {
    mockLogin.mockRejectedValueOnce({
      code: 'ACCOUNT_LOCKED',
      message: 'Account locked',
    });

    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByPlaceholderText(/e\.g\. kasun@example\.lk/i), {
      target: { value: 'kasun@example.lk' },
    });
    fireEvent.change(screen.getByPlaceholderText(/enter your password/i), {
      target: { value: 'WrongPass123!' },
    });

    fireEvent.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/account temporarily locked/i)).toBeTruthy();
    });
  });

  it('shows unverified warning with resend option on EMAIL_NOT_VERIFIED', async () => {
    mockLogin.mockRejectedValueOnce({
      code: 'EMAIL_NOT_VERIFIED',
      message: 'Email not verified',
    });

    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByPlaceholderText(/e\.g\. kasun@example\.lk/i), {
      target: { value: 'kasun@example.lk' },
    });
    fireEvent.change(screen.getByPlaceholderText(/enter your password/i), {
      target: { value: 'Pass123!' },
    });

    fireEvent.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/your email is not verified yet/i)).toBeTruthy();
      expect(screen.getByRole('button', { name: /resend verification email/i })).toBeTruthy();
    });
  });
});
