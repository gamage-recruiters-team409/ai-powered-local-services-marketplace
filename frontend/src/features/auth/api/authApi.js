import apiClient from '../../../shared/services/apiClient';

const AUTH_BASE = '/auth';

export async function login({ email, password }) {
  const response = await apiClient.post(`${AUTH_BASE}/login`, { email, password });
  return response.data;
}

export async function register({ name, email, phone, password, role, acceptedTerms, policyVersion }) {
  const response = await apiClient.post(`${AUTH_BASE}/register`, {
    name,
    email,
    phone,
    password,
    role,
    acceptedTerms,
    policyVersion,
  });
  return response.data;
}

export async function refreshToken(token) {
  const response = await apiClient.post(`${AUTH_BASE}/refresh`, { refreshToken: token });
  return response.data;
}

export async function logout(token) {
  const response = await apiClient.post(`${AUTH_BASE}/logout`, { refreshToken: token });
  return response.data;
}

export async function getCurrentUser() {
  const response = await apiClient.get('/users/me');
  return response.data;
}

export async function verifyEmail(token) {
  const response = await apiClient.post(`${AUTH_BASE}/verify-email`, { token });
  return response.data;
}

export async function resendVerification(email) {
  const response = await apiClient.post(`${AUTH_BASE}/resend-verification`, { email });
  return response.data;
}

export async function forgotPassword(email) {
  const response = await apiClient.post(`${AUTH_BASE}/forgot-password`, { email });
  return response.data;
}

export async function resetPassword(token, newPassword) {
  const response = await apiClient.post(`${AUTH_BASE}/reset-password`, { token, newPassword });
  return response.data;
}

export async function changePassword(currentPassword, newPassword) {
  const response = await apiClient.post(`${AUTH_BASE}/change-password`, { currentPassword, newPassword });
  return response.data;
}
