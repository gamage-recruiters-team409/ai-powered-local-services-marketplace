// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SettingsPage } from '../pages/SettingsPage';
import * as customerAccountApi from '../services/customerAccountApi';

vi.mock('../services/customerAccountApi');

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  };
});

const mockLogout = vi.fn().mockResolvedValue();
vi.mock('../../auth/hooks/useAuth', () => ({
  useAuth: () => ({
    logout: mockLogout,
    user: { id: 'cust-1' },
  }),
}));

const mockToast = {
  success: vi.fn(),
  error: vi.fn(),
};
vi.mock('../../../shared/components/feedback/useToast', () => ({
  useToast: () => mockToast,
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('SettingsPage', () => {
  beforeEach(() => {
    customerAccountApi.changePassword.mockResolvedValue();
  });

  it('renders change password card and form', () => {
    render(
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /change password/i })).toBeTruthy();
    expect(screen.getByLabelText(/current password/i)).toBeTruthy();
    expect(screen.getByLabelText(/^new password/i)).toBeTruthy();
    expect(screen.getByLabelText(/confirm new password/i)).toBeTruthy();
  });

  it('validates empty fields and mismatched passwords before submission', async () => {
    render(
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>
    );

    const submitBtn = screen.getByRole('button', { name: /update password/i });
    fireEvent.click(submitBtn);

    expect(screen.getByText(/current password is required/i)).toBeTruthy();
    expect(screen.getByText(/new password is required/i)).toBeTruthy();
    expect(screen.getByText(/confirm password is required/i)).toBeTruthy();

    expect(customerAccountApi.changePassword).not.toHaveBeenCalled();
  });

  it('submits valid password change, calls logout, shows toast, and navigates to /login', async () => {
    render(
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByLabelText(/current password/i), { target: { value: 'OldPassword123' } });
    fireEvent.change(screen.getByLabelText(/^new password/i), { target: { value: 'NewPassword123' } });
    fireEvent.change(screen.getByLabelText(/confirm new password/i), { target: { value: 'NewPassword123' } });

    fireEvent.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => {
      expect(customerAccountApi.changePassword).toHaveBeenCalledWith({
        currentPassword: 'OldPassword123',
        newPassword: 'NewPassword123',
      });
      expect(mockLogout).toHaveBeenCalled();
      expect(mockToast.success).toHaveBeenCalledWith(expect.stringContaining('Password changed successfully'));
      expect(mockNavigate).toHaveBeenCalledWith('/login', expect.any(Object));
    });
  });

  it('handles INVALID_CURRENT_PASSWORD and SAME_PASSWORD backend errors', async () => {
    const errorSame = new Error('Same password');
    errorSame.code = 'SAME_PASSWORD';
    customerAccountApi.changePassword.mockRejectedValueOnce(errorSame);

    render(
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByLabelText(/current password/i), { target: { value: 'OldPassword123' } });
    fireEvent.change(screen.getByLabelText(/^new password/i), { target: { value: 'NewPassword123' } });
    fireEvent.change(screen.getByLabelText(/confirm new password/i), { target: { value: 'NewPassword123' } });

    fireEvent.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => {
      expect(screen.getByText(/new password cannot be the same as the current password/i)).toBeTruthy();
    });
  });

  it('handles 429 rate limit with Retry-After', async () => {
    const error429 = new Error('Rate limit exceeded');
    error429.status = 429;
    error429.retryAfterSeconds = 60;
    customerAccountApi.changePassword.mockRejectedValueOnce(error429);

    render(
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByLabelText(/current password/i), { target: { value: 'OldPassword123' } });
    fireEvent.change(screen.getByLabelText(/^new password/i), { target: { value: 'NewPassword123' } });
    fireEvent.change(screen.getByLabelText(/confirm new password/i), { target: { value: 'NewPassword123' } });

    fireEvent.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => {
      expect(screen.getByText(/too many attempts\. please try again in 60 seconds\./i)).toBeTruthy();
    });
  });
});
