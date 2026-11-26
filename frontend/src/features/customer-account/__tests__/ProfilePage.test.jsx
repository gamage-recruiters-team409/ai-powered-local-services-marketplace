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
    if (!window.URL.createObjectURL) {
      window.URL.createObjectURL = vi.fn(() => 'blob:http://localhost/test-blob');
    } else {
      vi.spyOn(window.URL, 'createObjectURL').mockReturnValue('blob:http://localhost/test-blob');
    }
    if (!window.URL.revokeObjectURL) {
      window.URL.revokeObjectURL = vi.fn();
    } else {
      vi.spyOn(window.URL, 'revokeObjectURL').mockImplementation(() => {});
    }

    customerAccountApi.getProfile.mockResolvedValue({
      id: 'cust-1',
      fullName: 'Nimal Perera',
      email: 'nimal@email.com',
      phone: '+94 77 123 4567',
      emailVerified: true,
      status: 'ACTIVE',
      profileImageUrl: null,
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

  it('saves updated profile successfully, updates input from server response, and shows success toast', async () => {
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

    // Name and phone fields show the updated fullName and phone from the server response
    expect(screen.getByDisplayValue('Nimal P. Updated')).toBeTruthy();
    expect(screen.getByDisplayValue('+94779999999')).toBeTruthy();
  });

  it('restores newly saved profile values when Cancel is clicked after a successful save', async () => {
    customerAccountApi.updateProfile.mockResolvedValueOnce({
      id: 'cust-1',
      fullName: 'Saved Name Two',
      email: 'nimal@email.com',
      phone: '+94778888888',
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

    // Save new values
    fireEvent.change(nameInput, { target: { value: 'Saved Name Two' } });
    fireEvent.change(phoneInput, { target: { value: '0778888888' } });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(screen.getByDisplayValue('Saved Name Two')).toBeTruthy();
    });

    // Now edit fields again without saving
    fireEvent.change(nameInput, { target: { value: 'Unsaved Draft Name' } });
    fireEvent.change(phoneInput, { target: { value: '0771111111' } });
    expect(screen.getByDisplayValue('Unsaved Draft Name')).toBeTruthy();

    // Click cancel
    const cancelBtn = screen.getByRole('button', { name: /cancel/i });
    fireEvent.click(cancelBtn);

    // Should restore the newly saved values, not the initial load values
    expect(screen.getByDisplayValue('Saved Name Two')).toBeTruthy();
    expect(screen.getByDisplayValue('+94778888888')).toBeTruthy();
  });

  it('restores loaded values when Cancel is clicked before any save', async () => {
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

  it('shows initials when user has no profile photo and hides Remove photo button', async () => {
    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('NP')).toBeTruthy();
    });

    expect(screen.queryByRole('button', { name: /remove photo/i })).toBeNull();
    expect(screen.getByRole('button', { name: /change photo/i })).toBeTruthy();
  });

  it('shows profile image and Remove photo button when user has profile photo', async () => {
    customerAccountApi.getProfile.mockResolvedValueOnce({
      id: 'cust-1',
      fullName: 'Nimal Perera',
      email: 'nimal@email.com',
      phone: '+94 77 123 4567',
      emailVerified: true,
      status: 'ACTIVE',
      profileImageUrl: 'https://res.cloudinary.com/test/photo.jpg',
    });

    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      const img = screen.getByAltText('Profile photo');
      expect(img.getAttribute('src')).toBe('https://res.cloudinary.com/test/photo.jpg');
    });

    expect(screen.getByRole('button', { name: /remove photo/i })).toBeTruthy();
  });

  it('rejects unsupported file type on client without calling upload API', async () => {
    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('NP')).toBeTruthy();
    });

    const fileInput = screen.getByTestId('file-input-photo');
    const invalidFile = new File(['fake content'], 'document.pdf', { type: 'application/pdf' });

    fireEvent.change(fileInput, { target: { files: [invalidFile] } });

    expect(screen.getByText(/please select a jpeg or png image/i)).toBeTruthy();
    expect(customerAccountApi.uploadProfilePhoto).not.toHaveBeenCalled();
  });

  it('rejects file larger than 5 MB on client without calling upload API', async () => {
    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('NP')).toBeTruthy();
    });

    const fileInput = screen.getByTestId('file-input-photo');
    // Create oversized file (> 5 MB)
    const largeFile = new File([new ArrayBuffer(5 * 1024 * 1024 + 10)], 'huge.jpg', { type: 'image/jpeg' });

    fireEvent.change(fileInput, { target: { files: [largeFile] } });

    expect(screen.getByText(/image size must not exceed 5 mb/i)).toBeTruthy();
    expect(customerAccountApi.uploadProfilePhoto).not.toHaveBeenCalled();
  });

  it('uploads valid photo successfully: updates image, shows success toast, and revokes preview', async () => {
    customerAccountApi.uploadProfilePhoto.mockResolvedValueOnce({
      id: 'cust-1',
      profileImageUrl: 'https://res.cloudinary.com/test/new-photo.jpg',
    });

    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('NP')).toBeTruthy();
    });

    const fileInput = screen.getByTestId('file-input-photo');
    const validFile = new File(['image-bits'], 'avatar.png', { type: 'image/png' });

    fireEvent.change(fileInput, { target: { files: [validFile] } });

    await waitFor(() => {
      expect(customerAccountApi.uploadProfilePhoto).toHaveBeenCalledWith(validFile);
      expect(mockToast.success).toHaveBeenCalledWith('Profile photo updated successfully');
    });

    const img = screen.getByAltText('Profile photo');
    expect(img.getAttribute('src')).toBe('https://res.cloudinary.com/test/new-photo.jpg');
    expect(window.URL.revokeObjectURL).toHaveBeenCalled();
  });

  it('handles photo upload failure: displays error message and does not update photo', async () => {
    customerAccountApi.uploadProfilePhoto.mockRejectedValueOnce(new Error('Network error uploading photo'));

    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('NP')).toBeTruthy();
    });

    const fileInput = screen.getByTestId('file-input-photo');
    const validFile = new File(['image-bits'], 'avatar.jpg', { type: 'image/jpeg' });

    fireEvent.change(fileInput, { target: { files: [validFile] } });

    await waitFor(() => {
      expect(screen.getByText('Network error uploading photo')).toBeTruthy();
    });

    expect(screen.getByText('NP')).toBeTruthy();
    expect(window.URL.revokeObjectURL).toHaveBeenCalled();
  });

  it('removes profile photo: calls API, unsets photo, and shows success toast', async () => {
    customerAccountApi.getProfile.mockResolvedValueOnce({
      id: 'cust-1',
      fullName: 'Nimal Perera',
      email: 'nimal@email.com',
      phone: '+94 77 123 4567',
      emailVerified: true,
      status: 'ACTIVE',
      profileImageUrl: 'https://res.cloudinary.com/test/current.jpg',
    });
    customerAccountApi.removeProfilePhoto.mockResolvedValueOnce({});

    render(
      <MemoryRouter>
        <ProfilePage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByAltText('Profile photo')).toBeTruthy();
    });

    const removeBtn = screen.getByRole('button', { name: /remove photo/i });
    fireEvent.click(removeBtn);

    await waitFor(() => {
      expect(customerAccountApi.removeProfilePhoto).toHaveBeenCalled();
      expect(mockToast.success).toHaveBeenCalledWith('Profile photo removed');
    });

    expect(screen.getByText('NP')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /remove photo/i })).toBeNull();
  });
});
