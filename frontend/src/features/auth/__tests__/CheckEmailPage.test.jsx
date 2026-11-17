// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../api/authApi';
import CheckEmailPage from '../pages/CheckEmailPage';

vi.mock('../api/authApi');

describe('CheckEmailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
  });

  it('renders check your email title and masked/displayed email from state', () => {
    render(
      <MemoryRouter initialEntries={[{ pathname: '/check-email', state: { email: 'kasun@example.lk' } }]}>
        <CheckEmailPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /check your email/i })).toBeTruthy();
    expect(screen.getByText('kasun@example.lk')).toBeTruthy();
    expect(screen.getByRole('button', { name: /resend email/i })).toBeTruthy();
    expect(screen.getByRole('link', { name: /back to login/i })).toBeTruthy();
  });

  it('handles resend email and triggers countdown', async () => {
    authApi.resendVerification.mockResolvedValueOnce({ message: 'Sent' });

    render(
      <MemoryRouter initialEntries={[{ pathname: '/check-email', state: { email: 'kasun@example.lk' } }]}>
        <CheckEmailPage />
      </MemoryRouter>
    );

    const resendBtn = screen.getByRole('button', { name: /resend email/i });
    fireEvent.click(resendBtn);

    await waitFor(() => {
      expect(authApi.resendVerification).toHaveBeenCalledWith('kasun@example.lk');
      expect(screen.getByText(/verification email sent!/i)).toBeTruthy();
      expect(screen.getByText(/you can request a new email in/i)).toBeTruthy();
    });
  });
});
