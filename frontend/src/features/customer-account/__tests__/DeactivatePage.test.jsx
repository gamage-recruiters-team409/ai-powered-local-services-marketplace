// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DeactivatePage } from '../pages/DeactivatePage';
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

describe('DeactivatePage', () => {
  beforeEach(() => {
    customerAccountApi.deactivateAccount.mockResolvedValue();
  });

  it('renders default danger zone warning card and open modal button', () => {
    render(
      <MemoryRouter>
        <DeactivatePage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { level: 1, name: /deactivate account/i })).toBeTruthy();
    expect(screen.getByRole('heading', { level: 2, name: /deactivate account/i })).toBeTruthy();
    expect(screen.getByText(/you will be logged out and will not be able to log in again/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: /deactivate my account/i })).toBeTruthy();
  });

  it('opens confirmation modal with password field and submits deactivation', async () => {
    render(
      <MemoryRouter>
        <DeactivatePage />
      </MemoryRouter>
    );

    const openBtn = screen.getByRole('button', { name: /deactivate my account/i });
    fireEvent.click(openBtn);

    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText(/are you sure you want to deactivate your account\?/i)).toBeTruthy();
    expect(screen.getByPlaceholderText(/account password/i)).toBeTruthy();

    const pwInput = screen.getByPlaceholderText(/account password/i);
    const confirmBtn = screen.getByRole('button', { name: /yes, deactivate/i });

    // Submit with empty password -> client validation
    fireEvent.click(confirmBtn);
    expect(screen.getByText(/password is required to deactivate your account/i)).toBeTruthy();

    // Type password and submit
    fireEvent.change(pwInput, { target: { value: 'SecretPassword1!' } });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(customerAccountApi.deactivateAccount).toHaveBeenCalledWith({ password: 'SecretPassword1!' });
      expect(mockLogout).toHaveBeenCalled();
      expect(mockToast.success).toHaveBeenCalledWith(expect.stringContaining('deactivated'));
      expect(mockNavigate).toHaveBeenCalledWith('/', expect.any(Object));
    });
  });

  it('handles 409 OPEN_BOOKINGS_EXIST and transitions into blocked state', async () => {
    const error409 = new Error('Open bookings exist');
    error409.status = 409;
    error409.code = 'OPEN_BOOKINGS_EXIST';
    customerAccountApi.deactivateAccount.mockRejectedValueOnce(error409);

    render(
      <MemoryRouter>
        <DeactivatePage />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /deactivate my account/i }));
    fireEvent.change(screen.getByPlaceholderText(/account password/i), { target: { value: 'SecretPassword1!' } });
    fireEvent.click(screen.getByRole('button', { name: /yes, deactivate/i }));

    await waitFor(() => {
      expect(screen.getByText(/you have open bookings\. please complete or cancel them first\./i)).toBeTruthy();
      expect(screen.getByRole('link', { name: /go to my bookings →/i })).toBeTruthy();
      expect(screen.getByRole('button', { name: /deactivate my account/i }).hasAttribute('disabled')).toBe(true);
    });
  });

  it('handles 503 DEACTIVATION_UNAVAILABLE error inside modal', async () => {
    const error503 = new Error('Unavailable');
    error503.status = 503;
    error503.code = 'DEACTIVATION_UNAVAILABLE';
    customerAccountApi.deactivateAccount.mockRejectedValueOnce(error503);

    render(
      <MemoryRouter>
        <DeactivatePage />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /deactivate my account/i }));
    fireEvent.change(screen.getByPlaceholderText(/account password/i), { target: { value: 'SecretPassword1!' } });
    fireEvent.click(screen.getByRole('button', { name: /yes, deactivate/i }));

    await waitFor(() => {
      expect(screen.getByText(/account deactivation is temporarily unavailable/i)).toBeTruthy();
    });
  });
});
