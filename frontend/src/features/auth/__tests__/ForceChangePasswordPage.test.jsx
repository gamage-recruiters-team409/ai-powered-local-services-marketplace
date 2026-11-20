// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as useAuthModule from '../hooks/useAuth';
import * as authApi from '../api/authApi';
import ForceChangePasswordPage from '../pages/ForceChangePasswordPage';

vi.mock('../hooks/useAuth');
vi.mock('../api/authApi');

describe('ForceChangePasswordPage', () => {
  const mockRefreshUser = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    useAuthModule.useAuth.mockReturnValue({
      user: { role: 'CUSTOMER' },
      refreshUser: mockRefreshUser,
    });
  });

  afterEach(() => {
    cleanup();
  });

  it('renders change password inputs', () => {
    render(
      <MemoryRouter>
        <ForceChangePasswordPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /change your password/i })).toBeTruthy();
    expect(screen.getByPlaceholderText(/^enter current password$/i)).toBeTruthy();
    expect(screen.getByPlaceholderText(/^enter new password$/i)).toBeTruthy();
    expect(screen.getByPlaceholderText(/^re-enter new password$/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: /update password/i })).toBeTruthy();
  });

  it('validates password match and strength', async () => {
    render(
      <MemoryRouter>
        <ForceChangePasswordPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByPlaceholderText(/^enter current password$/i), {
      target: { value: 'OldPass123!' },
    });
    fireEvent.change(screen.getByPlaceholderText(/^enter new password$/i), {
      target: { value: 'NewPass123!' },
    });
    fireEvent.change(screen.getByPlaceholderText(/^re-enter new password$/i), {
      target: { value: 'MismatchPass123!' },
    });

    fireEvent.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => {
      expect(screen.getByText(/passwords do not match/i)).toBeTruthy();
    });
  });

  it('submits valid password change and redirects', async () => {
    authApi.changePassword.mockResolvedValueOnce({});
    mockRefreshUser.mockResolvedValueOnce({ role: 'CUSTOMER', mustChangePassword: false });

    render(
      <MemoryRouter>
        <ForceChangePasswordPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByPlaceholderText(/^enter current password$/i), {
      target: { value: 'OldPass123!' },
    });
    fireEvent.change(screen.getByPlaceholderText(/^enter new password$/i), {
      target: { value: 'NewPass123!' },
    });
    fireEvent.change(screen.getByPlaceholderText(/^re-enter new password$/i), {
      target: { value: 'NewPass123!' },
    });

    fireEvent.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => {
      expect(authApi.changePassword).toHaveBeenCalledWith('OldPass123!', 'NewPass123!');
      expect(mockRefreshUser).toHaveBeenCalled();
    });
  });
});
