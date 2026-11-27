// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as useAuthModule from '../hooks/useAuth';
import RegisterPage from '../pages/RegisterPage';

vi.mock('../hooks/useAuth');

describe('RegisterPage', () => {
  const mockRegister = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    useAuthModule.useAuth.mockReturnValue({
      register: mockRegister,
    });
  });

  afterEach(() => {
    cleanup();
  });

  it('renders registration form fields and allows role selection', () => {
    render(
      <MemoryRouter>
        <RegisterPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /create your account/i })).toBeTruthy();
    expect(screen.getByPlaceholderText(/enter your full name/i)).toBeTruthy();
    expect(screen.getByPlaceholderText(/name@example\.lk/i)).toBeTruthy();
    expect(screen.getByPlaceholderText(/77 123 4567/i)).toBeTruthy();
    expect(screen.getByPlaceholderText(/create a password/i)).toBeTruthy();
    expect(screen.getByRole('checkbox')).toBeTruthy();

    const providerCard = screen.getByRole('button', { name: /provider/i });
    fireEvent.click(providerCard);
    expect(providerCard.getAttribute('aria-pressed')).toBe('true');
  });

  it('validates client-side constraints when submitted empty', async () => {
    render(
      <MemoryRouter>
        <RegisterPage />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => {
      expect(screen.getByText(/please enter your full name/i)).toBeTruthy();
      expect(screen.getByText(/please enter your email address/i)).toBeTruthy();
      expect(screen.getByText(/please enter your phone number/i)).toBeTruthy();
    });
    expect(mockRegister).not.toHaveBeenCalled();
  });

  it('submits valid registration data', async () => {
    mockRegister.mockResolvedValueOnce({ id: 'u1' });

    render(
      <MemoryRouter>
        <RegisterPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByPlaceholderText(/enter your full name/i), {
      target: { value: 'Kasun Perera' },
    });
    fireEvent.change(screen.getByPlaceholderText(/name@example\.lk/i), {
      target: { value: 'kasun@example.lk' },
    });
    fireEvent.change(screen.getByPlaceholderText(/77 123 4567/i), {
      target: { value: '771234567' },
    });
    fireEvent.change(screen.getByPlaceholderText(/create a password/i), {
      target: { value: 'ValidPass123!' },
    });
    fireEvent.click(screen.getByRole('checkbox'));

    fireEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => {
      expect(mockRegister).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Kasun Perera',
          email: 'kasun@example.lk',
          phone: '+94771234567',
          role: 'CUSTOMER',
          acceptedTerms: true,
          policyVersion: '1.0',
        })
      );
    });
  });

  it('validates name format on blur and rejects digits', async () => {
    render(
      <MemoryRouter>
        <RegisterPage />
      </MemoryRouter>
    );

    const nameInput = screen.getByPlaceholderText(/enter your full name/i);
    fireEvent.change(nameInput, { target: { value: 'kalana test 14444' } });
    fireEvent.blur(nameInput);

    await waitFor(() => {
      expect(
        screen.getByText(/full name can only contain letters, spaces, apostrophes, hyphens and dots/i)
      ).toBeTruthy();
    });
  });

  it('normalizes multiple spaces in name when submitting valid form', async () => {
    mockRegister.mockResolvedValueOnce({ id: 'u2' });

    render(
      <MemoryRouter>
        <RegisterPage />
      </MemoryRouter>
    );

    fireEvent.change(screen.getByPlaceholderText(/enter your full name/i), {
      target: { value: '  Kasun    Marie   Perera  ' },
    });
    fireEvent.change(screen.getByPlaceholderText(/name@example\.lk/i), {
      target: { value: 'kasun@example.lk' },
    });
    fireEvent.change(screen.getByPlaceholderText(/77 123 4567/i), {
      target: { value: '771234567' },
    });
    fireEvent.change(screen.getByPlaceholderText(/create a password/i), {
      target: { value: 'ValidPass123!' },
    });
    fireEvent.click(screen.getByRole('checkbox'));

    fireEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => {
      expect(mockRegister).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Kasun Marie Perera',
        })
      );
    });
  });
});
