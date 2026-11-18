// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfilePage } from '../pages/ProfilePage';
import * as customerAccountApi from '../services/customerAccountApi';

vi.mock('../services/customerAccountApi');

const mockToast = {
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
};

vi.mock('../../../shared/components/feedback/useToast', () => ({
  useToast: () => mockToast,
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ProfilePage', () => {
  beforeEach(() => {
    customerAccountApi.getProfile.mockResolvedValue({
      id: 'cust-1',
      fullName: 'Nimal Perera',
      email: 'nimal@email.com',
      phone: '+94 77 123 4567',
      emailVerified: true,
      status: 'ACTIVE',
    });
  });

  it('renders loading state initially and then shows profile details with VERIFIED badge', async () => {
    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    expect(screen.getByText(/loading profile details/i)).toBeTruthy();

    await waitFor(() => {
      expect(screen.getByDisplayValue('Nimal Perera')).toBeTruthy();
      expect(screen.getByDisplayValue('nimal@email.com')).toBeTruthy();
      expect(screen.getByDisplayValue('+94 77 123 4567')).toBeTruthy();
    });

    // Check VERIFIED badge exists for emailVerified: true
    expect(screen.getByText(/verified/i)).toBeTruthy();
  });

  it('hides VERIFIED badge when email is not verified', async () => {
    customerAccountApi.getProfile.mockResolvedValueOnce({
      id: 'cust-2',
      fullName: 'Sunil Shantha',
      email: 'sunil@email.com',
      phone: '+94 71 987 6543',
      emailVerified: false,
      status: 'ACTIVE',
    });

    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByDisplayValue('Sunil Shantha')).toBeTruthy();
    });

    expect(screen.queryByText(/verified/i)).toBeNull();
  });

  it('validates empty name and invalid Sri Lankan phone format', async () => {
    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByDisplayValue('Nimal Perera')).toBeTruthy();
    });

    const nameInput = screen.getByDisplayValue('Nimal Perera');
    const phoneInput = screen.getByDisplayValue('+94 77 123 4567');
    const saveBtn = screen.getByRole('button', { name: /save changes/i });

    // Clear name
    fireEvent.change(nameInput, { target: { value: '   ' } });
    fireEvent.click(saveBtn);
    expect(screen.getByText(/full name is required/i)).toBeTruthy();

    // Fix name, set invalid phone
    fireEvent.change(nameInput, { target: { value: 'Nimal Perera' } });
    fireEvent.change(phoneInput, { target: { value: '12345' } });
    fireEvent.click(saveBtn);
    expect(screen.getByText(/invalid sri lankan phone number format/i)).toBeTruthy();

    expect(customerAccountApi.updateProfile).not.toHaveBeenCalled();
  });

  it('saves updated profile successfully and shows success toast', async () => {
    customerAccountApi.updateProfile.mockResolvedValueOnce({
      id: 'cust-1',
      fullName: 'Nimal P. Updated',
      email: 'nimal@email.com',
      phone: '+94779999999',
      emailVerified: true,
    });

    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByDisplayValue('Nimal Perera')).toBeTruthy();
    });

    const nameInput = screen.getByDisplayValue('Nimal Perera');
    const phoneInput = screen.getByDisplayValue('+94 77 123 4567');
    const saveBtn = screen.getByRole('button', { name: /save changes/i });

    fireEvent.change(nameInput, { target: { value: 'Nimal P. Updated' } });
    fireEvent.change(phoneInput, { target: { value: '0779999999' } });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(customerAccountApi.updateProfile).toHaveBeenCalledWith({
        fullName: 'Nimal P. Updated',
        phone: '0779999999',
      });
      expect(mockToast.success).toHaveBeenCalledWith('Profile updated successfully');
    });
  });

  it('resets form when Cancel button is clicked', async () => {
    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByDisplayValue('Nimal Perera')).toBeTruthy();
    });

    const nameInput = screen.getByDisplayValue('Nimal Perera');
    fireEvent.change(nameInput, { target: { value: 'Temporary Name' } });
    expect(screen.getByDisplayValue('Temporary Name')).toBeTruthy();

    const cancelBtn = screen.getByRole('button', { name: /cancel/i });
    fireEvent.click(cancelBtn);

    expect(screen.getByDisplayValue('Nimal Perera')).toBeTruthy();
  });
});
