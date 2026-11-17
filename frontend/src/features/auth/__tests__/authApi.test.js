import { beforeEach, describe, expect, it, vi } from 'vitest';
import apiClient from '../../../shared/services/apiClient';
import * as authApi from '../api/authApi';

vi.mock('../../../shared/services/apiClient', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe('authApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('calls POST /auth/login with credentials', async () => {
    apiClient.post.mockResolvedValueOnce({ data: { accessToken: 'a', refreshToken: 'r' } });
    const res = await authApi.login({ email: 'test@example.com', password: 'Pass123!' });
    expect(apiClient.post).toHaveBeenCalledWith('/auth/login', {
      email: 'test@example.com',
      password: 'Pass123!',
    });
    expect(res.accessToken).toBe('a');
  });

  it('calls POST /auth/register with user payload', async () => {
    apiClient.post.mockResolvedValueOnce({ data: { id: 'u1', email: 'test@example.com' } });
    const payload = {
      name: 'Kasun',
      email: 'kasun@example.lk',
      phone: '+94771234567',
      password: 'Pass123!',
      role: 'CUSTOMER',
      acceptedTerms: true,
      policyVersion: '1.0',
    };
    const res = await authApi.register(payload);
    expect(apiClient.post).toHaveBeenCalledWith('/auth/register', payload);
    expect(res.id).toBe('u1');
  });

  it('calls POST /auth/verify-email with token', async () => {
    apiClient.post.mockResolvedValueOnce({ data: { message: 'Verified' } });
    const res = await authApi.verifyEmail('tok123');
    expect(apiClient.post).toHaveBeenCalledWith('/auth/verify-email', { token: 'tok123' });
    expect(res.message).toBe('Verified');
  });

  it('calls POST /auth/reset-password with token and newPassword', async () => {
    apiClient.post.mockResolvedValueOnce({ data: { message: 'Password reset' } });
    const res = await authApi.resetPassword('tok123', 'NewPass123!');
    expect(apiClient.post).toHaveBeenCalledWith('/auth/reset-password', {
      token: 'tok123',
      newPassword: 'NewPass123!',
    });
    expect(res.message).toBe('Password reset');
  });

  it('calls GET /users/me', async () => {
    apiClient.get.mockResolvedValueOnce({ data: { id: 'u1', email: 'test@example.com' } });
    const res = await authApi.getCurrentUser();
    expect(apiClient.get).toHaveBeenCalledWith('/users/me');
    expect(res.id).toBe('u1');
  });
});
