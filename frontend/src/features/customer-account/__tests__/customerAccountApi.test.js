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
});
