// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SavedAddressesPage } from '../pages/SavedAddressesPage';
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

const sampleAddresses = [
  {
    id: 'addr-1',
    label: 'Home',
    addressLine1: 'No. 45/2, Alfred House Gardens',
    city: 'Colombo',
    district: 'Colombo',
    isDefault: true,
  },
  {
    id: 'addr-2',
    label: 'Work',
    addressLine1: '128 Kandy Road, Dalugama',
    addressLine2: 'Apt 4',
    city: 'Kelaniya',
    district: 'Gampaha',
    postalCode: '11600',
    isDefault: false,
  },
];

const sampleDistricts = {
  provinces: [
    { name: 'Western', districts: ['Colombo', 'Gampaha', 'Kalutara'] },
    { name: 'Central', districts: ['Kandy', 'Matale', 'Nuwara Eliya'] },
  ],
};

describe('SavedAddressesPage', () => {
  beforeEach(() => {
    customerAccountApi.getAddresses.mockResolvedValue(sampleAddresses);
    customerAccountApi.getDistricts.mockResolvedValue(sampleDistricts);
  });

  it('renders list of addresses with Default badge and Set as default button', async () => {
    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/No. 45\/2, Alfred House Gardens/i)).toBeTruthy();
      expect(screen.getByText(/128 Kandy Road, Dalugama/i)).toBeTruthy();
    });

    expect(screen.getByText('Default')).toBeTruthy();
    expect(screen.getByRole('button', { name: /set as default/i })).toBeTruthy();

    // Check city and district rendering: shows "Colombo" when matching, "Kelaniya, Gampaha" when differing
    expect(screen.getByText('Colombo')).toBeTruthy();
    expect(screen.getByText('Kelaniya, Gampaha')).toBeTruthy();
  });

  it('renders empty state when there are no addresses', async () => {
    customerAccountApi.getAddresses.mockResolvedValueOnce([]);

    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/no saved addresses/i)).toBeTruthy();
    });
  });

  it('opens add modal, validates inputs (including city requirement and min-length), and creates a new address', async () => {
    customerAccountApi.createAddress.mockResolvedValueOnce({
      id: 'addr-3',
      label: 'Office',
      addressLine1: '74 Temple Road',
      city: 'Dehiwala-Mount Lavinia',
      district: 'Colombo',
      isDefault: false,
    });

    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/No. 45\/2, Alfred House Gardens/i)).toBeTruthy();
    });

    const addBtn = screen.getByRole('button', { name: /\+ add address/i });
    fireEvent.click(addBtn);

    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByRole('heading', { name: /add address/i })).toBeTruthy();

    const labelInput = screen.getByLabelText(/^label/i);
    const lineInput = screen.getByLabelText(/^address line/i);
    const cityInput = screen.getByLabelText(/^city/i);
    const districtSelect = screen.getByLabelText(/^district/i);
    const saveBtn = screen.getByRole('button', { name: /^save$/i });

    // Submit without input -> label required
    fireEvent.click(saveBtn);
    expect(screen.getByText(/label is required/i)).toBeTruthy();

    // Fill label, submit -> address line required
    fireEvent.change(labelInput, { target: { value: 'Office' } });
    fireEvent.click(saveBtn);
    expect(screen.getByText(/address line is required/i)).toBeTruthy();

    // Fill address line, submit -> city required
    fireEvent.change(lineInput, { target: { value: '74 Temple Road' } });
    fireEvent.click(saveBtn);
    expect(screen.getByText(/city is required/i)).toBeTruthy();

    // City too short (< 2 chars) -> city min length validation
    fireEvent.change(cityInput, { target: { value: 'D' } });
    fireEvent.click(saveBtn);
    expect(screen.getByText(/city must be at least 2 characters/i)).toBeTruthy();

    // Fill valid city, submit -> district required
    fireEvent.change(cityInput, { target: { value: 'Dehiwala-Mount Lavinia' } });
    fireEvent.click(saveBtn);
    expect(screen.getByText(/please select a district/i)).toBeTruthy();

    // Select district and submit
    fireEvent.change(districtSelect, { target: { value: 'Colombo' } });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(customerAccountApi.createAddress).toHaveBeenCalledWith({
        label: 'Office',
        addressLine1: '74 Temple Road',
        city: 'Dehiwala-Mount Lavinia',
        district: 'Colombo',
      });
      expect(mockToast.success).toHaveBeenCalledWith('Address added successfully');
    });
  });

  it('edits address with city and district and preserves original fields when only label changes', async () => {
    customerAccountApi.updateAddress.mockResolvedValueOnce({
      id: 'addr-2',
      label: 'Work Office',
      addressLine1: '128 Kandy Road, Dalugama',
      addressLine2: 'Apt 4',
      city: 'Kelaniya',
      district: 'Gampaha',
      postalCode: '11600',
      isDefault: false,
    });

    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/128 Kandy Road, Dalugama/i)).toBeTruthy();
    });

    // Click edit on addr-2 (Work)
    const editWorkBtn = screen.getByRole('button', { name: /edit work/i });
    fireEvent.click(editWorkBtn);

    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByRole('heading', { name: /edit address/i })).toBeTruthy();

    const labelInput = screen.getByLabelText(/^label/i);
    const cityInput = screen.getByLabelText(/^city/i);
    const districtSelect = screen.getByLabelText(/^district/i);
    expect(cityInput).toBeTruthy();
    expect(districtSelect.value).toBe('Gampaha');

    // Change ONLY the label
    fireEvent.change(labelInput, { target: { value: 'Work Office' } });

    const saveBtn = screen.getByRole('button', { name: /^save$/i });
    fireEvent.click(saveBtn);

    await waitFor(() => {
      expect(customerAccountApi.updateAddress).toHaveBeenCalledWith('addr-2', {
        label: 'Work Office',
        addressLine1: '128 Kandy Road, Dalugama',
        city: 'Kelaniya',
        district: 'Gampaha',
        addressLine2: 'Apt 4',
        postalCode: '11600',
        isDefault: false,
      });
      expect(mockToast.success).toHaveBeenCalledWith('Address updated successfully');
    });
  });

  it('edits address changing city only, preserving district and original fields', async () => {
    customerAccountApi.updateAddress.mockResolvedValueOnce({
      id: 'addr-2',
      label: 'Work',
      addressLine1: '128 Kandy Road, Dalugama',
      addressLine2: 'Apt 4',
      city: 'Peliyagoda',
      district: 'Gampaha',
      postalCode: '11600',
      isDefault: false,
    });

    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/128 Kandy Road, Dalugama/i)).toBeTruthy();
    });

    fireEvent.click(screen.getByRole('button', { name: /edit work/i }));

    const cityInput = screen.getByLabelText(/^city/i);
    fireEvent.change(cityInput, { target: { value: 'Peliyagoda' } });

    fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => {
      expect(customerAccountApi.updateAddress).toHaveBeenCalledWith('addr-2', {
        label: 'Work',
        addressLine1: '128 Kandy Road, Dalugama',
        city: 'Peliyagoda',
        district: 'Gampaha',
        addressLine2: 'Apt 4',
        postalCode: '11600',
        isDefault: false,
      });
    });
  });

  it('edits address changing district only, preserving city and original fields', async () => {
    customerAccountApi.updateAddress.mockResolvedValueOnce({
      id: 'addr-2',
      label: 'Work',
      addressLine1: '128 Kandy Road, Dalugama',
      addressLine2: 'Apt 4',
      city: 'Kelaniya',
      district: 'Colombo',
      postalCode: '11600',
      isDefault: false,
    });

    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/128 Kandy Road, Dalugama/i)).toBeTruthy();
    });

    fireEvent.click(screen.getByRole('button', { name: /edit work/i }));

    const districtSelect = screen.getByLabelText(/^district/i);
    fireEvent.change(districtSelect, { target: { value: 'Colombo' } });

    fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => {
      expect(customerAccountApi.updateAddress).toHaveBeenCalledWith('addr-2', {
        label: 'Work',
        addressLine1: '128 Kandy Road, Dalugama',
        city: 'Kelaniya',
        district: 'Colombo',
        addressLine2: 'Apt 4',
        postalCode: '11600',
        isDefault: false,
      });
    });
  });

  it('sets an address as default', async () => {
    customerAccountApi.setDefaultAddress.mockResolvedValueOnce({
      id: 'addr-2',
      isDefault: true,
    });

    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/128 Kandy Road, Dalugama/i)).toBeTruthy();
    });

    const setDefaultBtn = screen.getByRole('button', { name: /set as default/i });
    fireEvent.click(setDefaultBtn);

    await waitFor(() => {
      expect(customerAccountApi.setDefaultAddress).toHaveBeenCalledWith('addr-2');
      expect(mockToast.success).toHaveBeenCalledWith('Default address updated');
    });
  });

  it('opens delete confirmation and deletes address', async () => {
    customerAccountApi.deleteAddress.mockResolvedValueOnce();

    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/No. 45\/2, Alfred House Gardens/i)).toBeTruthy();
    });

    const deleteBtns = screen.getAllByRole('button', { name: /^delete/i });
    fireEvent.click(deleteBtns[0]);

    // Confirm dialog should be open
    expect(screen.getByText(/delete this address\?/i)).toBeTruthy();

    const confirmBtn = screen.getByRole('button', { name: /^delete$/i });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(customerAccountApi.deleteAddress).toHaveBeenCalledWith('addr-1');
      expect(mockToast.success).toHaveBeenCalledWith('Address deleted successfully');
    });
  });

  it('handles 409 MAX_ADDRESSES_REACHED error', async () => {
    const error = new Error('Maximum number of saved addresses (10) reached');
    error.code = 'MAX_ADDRESSES_REACHED';
    customerAccountApi.createAddress.mockRejectedValueOnce(error);

    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/No. 45\/2, Alfred House Gardens/i)).toBeTruthy();
    });

    fireEvent.click(screen.getByRole('button', { name: /\+ add address/i }));

    fireEvent.change(screen.getByLabelText(/^label/i), { target: { value: 'Extra' } });
    fireEvent.change(screen.getByLabelText(/^address line/i), { target: { value: 'Line 1' } });
    fireEvent.change(screen.getByLabelText(/^city/i), { target: { value: 'Colombo' } });
    fireEvent.change(screen.getByLabelText(/^district/i), { target: { value: 'Colombo' } });

    fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => {
      expect(customerAccountApi.createAddress).toHaveBeenCalledWith({
        label: 'Extra',
        addressLine1: 'Line 1',
        city: 'Colombo',
        district: 'Colombo',
      });
      expect(screen.getByText(/maximum number of saved addresses \(10\) reached/i)).toBeTruthy();
    });
  });

  it('handles 409 CONCURRENT_MODIFICATION error on address save by showing error toast and closing modal', async () => {
    const error = new Error('Concurrent modification detected');
    error.code = 'CONCURRENT_MODIFICATION';
    customerAccountApi.createAddress.mockRejectedValueOnce(error);

    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/No. 45\/2, Alfred House Gardens/i)).toBeTruthy();
    });

    fireEvent.click(screen.getByRole('button', { name: /\+ add address/i }));

    fireEvent.change(screen.getByLabelText(/^label/i), { target: { value: 'Branch' } });
    fireEvent.change(screen.getByLabelText(/^address line/i), { target: { value: '10 Galle Road' } });
    fireEvent.change(screen.getByLabelText(/^city/i), { target: { value: 'Colombo' } });
    fireEvent.change(screen.getByLabelText(/^district/i), { target: { value: 'Colombo' } });

    fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => {
      expect(customerAccountApi.createAddress).toHaveBeenCalledWith({
        label: 'Branch',
        addressLine1: '10 Galle Road',
        city: 'Colombo',
        district: 'Colombo',
      });
      expect(mockToast.error).toHaveBeenCalledWith('Address was modified by another action. Reloading list.');
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });

  it('handles 409 CONCURRENT_MODIFICATION error when setting default address', async () => {
    const error = new Error('Concurrent modification detected');
    error.code = 'CONCURRENT_MODIFICATION';
    customerAccountApi.setDefaultAddress.mockRejectedValueOnce(error);

    render(
      <MemoryRouter>
        <SavedAddressesPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/128 Kandy Road, Dalugama/i)).toBeTruthy();
    });

    const setDefaultBtn = screen.getByRole('button', { name: /set as default/i });
    fireEvent.click(setDefaultBtn);

    await waitFor(() => {
      expect(customerAccountApi.setDefaultAddress).toHaveBeenCalledWith('addr-2');
      expect(mockToast.error).toHaveBeenCalledWith(
        'Unable to set default address due to a concurrent update. Reloading list.'
      );
    });
  });
});
