import apiClient from '../../../shared/services/apiClient';

export async function getProfile() {
  const response = await apiClient.get('/customers/me');
  return response.data;
}

export async function updateProfile(data) {
  const response = await apiClient.put('/customers/me', data);
  return response.data;
}

export async function getAddresses() {
  const response = await apiClient.get('/customers/me/addresses');
  return response.data;
}

export async function createAddress(data) {
  const response = await apiClient.post('/customers/me/addresses', data);
  return response.data;
}

export async function updateAddress(addressId, data) {
  const response = await apiClient.put(`/customers/me/addresses/${addressId}`, data);
  return response.data;
}

export async function deleteAddress(addressId) {
  await apiClient.delete(`/customers/me/addresses/${addressId}`);
}

export async function setDefaultAddress(addressId) {
  const response = await apiClient.put(`/customers/me/addresses/${addressId}/default`);
  return response.data;
}

export async function getDistricts() {
  const response = await apiClient.get('/locations/districts');
  return response.data;
}

export async function changePassword(data) {
  await apiClient.put('/customers/me/password', data);
}

export async function deactivateAccount(data) {
  await apiClient.post('/customers/me/deactivate', data);
}
