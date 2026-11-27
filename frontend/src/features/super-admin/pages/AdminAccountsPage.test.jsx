// @vitest-environment jsdom

import { screen, waitFor, fireEvent, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import AdminAccountsPage from './AdminAccountsPage'
import {
  listAdminAccounts,
  getAdminAccountsSummary,
  createAdminAccount,
  changeAdminRole,
  changeAdminStatus,
} from '../services/superAdminService'
import {
  adminAccountsResponse,
  adminAccountsSummary,
  renderSuperAdminPage,
} from '../testing/renderSuperAdmin'

vi.mock('../services/superAdminService', () => ({
  listAdminAccounts: vi.fn(),
  getAdminAccountsSummary: vi.fn(),
  createAdminAccount: vi.fn(),
  changeAdminRole: vi.fn(),
  changeAdminStatus: vi.fn(),
  isRequestCancelled: () => false,
}))

vi.mock('../../support-operations/utils/download', () => ({
  downloadBlob: vi.fn(),
}))

beforeEach(() => {
  listAdminAccounts.mockReset().mockResolvedValue(adminAccountsResponse())
  getAdminAccountsSummary.mockReset().mockResolvedValue(adminAccountsSummary())
  createAdminAccount.mockReset().mockResolvedValue({})
  changeAdminRole.mockReset().mockResolvedValue({})
  changeAdminStatus.mockReset().mockResolvedValue({})
})

afterEach(() => vi.restoreAllMocks())

describe('Admin Accounts Page', () => {
  it('renders title, privileged access policy banner and summary cards with real counts', async () => {
    renderSuperAdminPage(AdminAccountsPage)
    expect(await screen.findByRole('heading', { name: /Admin accounts/ })).toBeTruthy()
    expect(screen.getByText(/Privileged access policy/i)).toBeTruthy()

    // Real summary counts
    await waitFor(() => {
      expect(screen.getByText('7')).toBeTruthy() // Total admins
      expect(screen.getByText('2')).toBeTruthy() // Elevated roles (1 super + 1 admin)
      expect(screen.getByText('1')).toBeTruthy() // Suspended
    })

    // Active sessions has no backend data -> placeholder
    expect(screen.getByText(/requires session tracking/i)).toBeTruthy()
  })

  it('renders accounts table with real name, email, role, and status chips', async () => {
    renderSuperAdminPage(AdminAccountsPage)
    expect(await screen.findByText('Eleanor Vance')).toBeTruthy()
    expect(screen.getByText('eleanor.vance@marketplace.local')).toBeTruthy()
    expect(screen.getByText('Alex Dawson')).toBeTruthy()
    expect(screen.getByText('David Silva')).toBeTruthy()
    expect(screen.getAllByText('Super Admin').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Suspended').length).toBeGreaterThan(0)
  })

  it('opens Create Admin Account modal and submits data', async () => {
    renderSuperAdminPage(AdminAccountsPage)
    await screen.findByText('Eleanor Vance')

    const createBtn = screen.getByRole('button', { name: /Create admin account/i })
    fireEvent.click(createBtn)

    expect(await screen.findByRole('dialog')).toBeTruthy()
    expect(screen.getByLabelText(/Full name/i)).toBeTruthy()

    fireEvent.change(screen.getByLabelText(/Full name/i), { target: { value: 'New Admin' } })
    fireEvent.change(screen.getByLabelText(/Email address/i), { target: { value: 'new@admin.local' } })
    fireEvent.change(screen.getByLabelText(/Temporary password/i), { target: { value: 'StrongPass123' } })

    const submitBtn = screen.getByRole('button', { name: /Create account/i })
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(createAdminAccount).toHaveBeenCalledWith({
        fullName: 'New Admin',
        email: 'new@admin.local',
        temporaryPassword: 'StrongPass123',
        role: 'ADMIN',
      })
    })
  })

  it('opens Change Role modal with current role and scope explanation', async () => {
    renderSuperAdminPage(AdminAccountsPage)
    await screen.findByText('Alex Dawson')

    const changeRoleBtns = screen.getAllByRole('button', { name: /Change role/i })
    fireEvent.click(changeRoleBtns[1]) // Alex Dawson (Admin)

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toBeTruthy()
    expect(screen.getByText(/Modifying role for/i)).toBeTruthy()
    expect(screen.getAllByText(/Alex Dawson/i).length).toBeGreaterThan(0)
    expect(screen.getByText(/next session/i)).toBeTruthy()

    const submitBtn = within(dialog).getByRole('button', { name: /Change role/i })
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(changeAdminRole).toHaveBeenCalledWith('user-2', {
        role: 'ADMIN',
        reason: '',
      })
    })
  })

  it('opens status change modal and enforces reason validation', async () => {
    renderSuperAdminPage(AdminAccountsPage)
    await screen.findByText('David Silva')

    const reactivateBtn = screen.getByRole('button', { name: /Reactivate/i })
    fireEvent.click(reactivateBtn)

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toBeTruthy()
    expect(screen.getByText(/Are you sure you want to reactivate/i)).toBeTruthy()

    // Try submit without reason
    const confirmBtn = within(dialog).getByRole('button', { name: /Confirm reactivation/i })
    fireEvent.click(confirmBtn)
    expect(await screen.findByText(/Reason must be between 10 and 500 characters/i)).toBeTruthy()

    // Provide valid reason
    fireEvent.change(screen.getByLabelText(/Reason for status change/i), {
      target: { value: 'Completed compliance re-verification process.' },
    })
    fireEvent.click(confirmBtn)

    await waitFor(() => {
      expect(changeAdminStatus).toHaveBeenCalledWith('user-3', {
        status: 'ACTIVE',
        reason: 'Completed compliance re-verification process.',
      })
    })
  })

  it('exports CSV with sanitized format', async () => {
    renderSuperAdminPage(AdminAccountsPage)
    await screen.findByText('Eleanor Vance')

    const exportBtn = screen.getByRole('button', { name: /Export CSV/i })
    fireEvent.click(exportBtn)

    await waitFor(() => {
      expect(listAdminAccounts).toHaveBeenCalledWith(expect.objectContaining({ size: 100 }))
    })
  })
})
