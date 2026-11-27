import { describe, it, expect, vi, beforeEach } from 'vitest';
import apiClient from '../../../shared/services/apiClient';
import {
  getProfile,
  updateProfile,
  getAddresses,
  createAddress,
  updateAddress,
  deleteAddress,
  setDefaultAddress,
  getDistricts,
  changePassword,
  deactivateAccount,
  uploadProfilePhoto,
  removeProfilePhoto,
  getRecentBookings,
} from '../services/customerAccountApi';

vi.mock('../../../shared/services/apiClient');

describe('customerAccountApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getProfile calls GET /customers/me', async () => {
    const mockData = { id: 'c1', fullName: 'Nimal' };
    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getProfile();
    expect(apiClient.get).toHaveBeenCalledWith('/customers/me');
    expect(result).toEqual(mockData);
  });

  it('updateProfile calls PUT /customers/me', async () => {
    const payload = { fullName: 'Nimal P', phone: '+94771234567' };
    apiClient.put.mockResolvedValueOnce({ data: { id: 'c1', ...payload } });

    const result = await updateProfile(payload);
    expect(apiClient.put).toHaveBeenCalledWith('/customers/me', payload);
    expect(result.fullName).toBe('Nimal P');
  });

  it('getAddresses calls GET /customers/me/addresses', async () => {
    apiClient.get.mockResolvedValueOnce({ data: [] });
    const result = await getAddresses();
    expect(apiClient.get).toHaveBeenCalledWith('/customers/me/addresses');
    expect(result).toEqual([]);
  });

  it('createAddress calls POST /customers/me/addresses', async () => {
    const payload = { label: 'Home', addressLine1: '123 Galle Rd', city: 'Colombo', district: 'Colombo' };
    apiClient.post.mockResolvedValueOnce({ data: { id: 'a1', ...payload } });

    const result = await createAddress(payload);
    expect(apiClient.post).toHaveBeenCalledWith('/customers/me/addresses', payload);
    expect(result.id).toBe('a1');
  });

  it('updateAddress calls PUT /customers/me/addresses/:id', async () => {
    const payload = { label: 'Work', addressLine1: '456 Kandy Rd', city: 'Kelaniya', district: 'Gampaha' };
    apiClient.put.mockResolvedValueOnce({ data: { id: 'a1', ...payload } });

    const result = await updateAddress('a1', payload);
    expect(apiClient.put).toHaveBeenCalledWith('/customers/me/addresses/a1', payload);
    expect(result.label).toBe('Work');
  });

  it('deleteAddress calls DELETE /customers/me/addresses/:id', async () => {
    apiClient.delete.mockResolvedValueOnce({});
    await deleteAddress('a1');
    expect(apiClient.delete).toHaveBeenCalledWith('/customers/me/addresses/a1');
  });

  it('setDefaultAddress calls PUT /customers/me/addresses/:id/default', async () => {
    apiClient.put.mockResolvedValueOnce({ data: { id: 'a1', isDefault: true } });
    const result = await setDefaultAddress('a1');
    expect(apiClient.put).toHaveBeenCalledWith('/customers/me/addresses/a1/default');
    expect(result.isDefault).toBe(true);
  });

  it('getDistricts calls GET /locations/districts', async () => {
    apiClient.get.mockResolvedValueOnce({ data: { provinces: [] } });
    const result = await getDistricts();
    expect(apiClient.get).toHaveBeenCalledWith('/locations/districts');
    expect(result).toEqual({ provinces: [] });
  });

  it('changePassword calls PUT /customers/me/password', async () => {
    apiClient.put.mockResolvedValueOnce({});
    await changePassword({ currentPassword: 'old', newPassword: 'new' });
    expect(apiClient.put).toHaveBeenCalledWith('/customers/me/password', { currentPassword: 'old', newPassword: 'new' });
  });

  it('deactivateAccount calls POST /customers/me/deactivate', async () => {
    apiClient.post.mockResolvedValueOnce({});
    await deactivateAccount({ password: 'secret' });
    expect(apiClient.post).toHaveBeenCalledWith('/customers/me/deactivate', { password: 'secret' });
  });

  it('uploadProfilePhoto calls PUT /customers/me/photo with FormData and unsets Content-Type', async () => {
    const mockResponse = { id: 'c1', profileImageUrl: 'https://example.com/photo.jpg' };
    apiClient.put.mockResolvedValueOnce({ data: mockResponse });

    const file = new File(['dummy content'], 'avatar.jpg', { type: 'image/jpeg' });
    const result = await uploadProfilePhoto(file);

    expect(apiClient.put).toHaveBeenCalledWith(
      '/customers/me/photo',
      expect.any(FormData),
      expect.objectContaining({
        headers: expect.objectContaining({
          'Content-Type': undefined,
        }),
      })
    );

    const callArgs = apiClient.put.mock.calls[0];
    const headers = callArgs[2]?.headers;
    expect(headers?.['Content-Type']).toBeUndefined();
    expect(headers?.['Content-Type']).not.toBe('application/json');
    expect(headers?.['Content-Type']).not.toBe('multipart/form-data');
    expect(result).toEqual(mockResponse);
  });

  it('removeProfilePhoto calls DELETE /customers/me/photo', async () => {
    apiClient.delete.mockResolvedValueOnce({});
    await removeProfilePhoto();
    expect(apiClient.delete).toHaveBeenCalledWith('/customers/me/photo');
  });

  it('getRecentBookings calls GET /bookings/customer with default size 5 and page 0', async () => {
    const mockData = { content: [], totalElements: 0 };
    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getRecentBookings();
    expect(apiClient.get).toHaveBeenCalledWith('/bookings/customer', {
      params: { page: 0, size: 5 },
    });
    expect(result).toEqual(mockData);
  });

  it('getRecentBookings accepts custom size parameter', async () => {
    const mockData = { content: [], totalElements: 0 };
    apiClient.get.mockResolvedValueOnce({ data: mockData });

    const result = await getRecentBookings({ size: 10 });
    expect(apiClient.get).toHaveBeenCalledWith('/bookings/customer', {
      params: { page: 0, size: 10 },
    });
    expect(result).toEqual(mockData);
  });
});
