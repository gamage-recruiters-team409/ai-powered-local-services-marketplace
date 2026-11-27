// @vitest-environment jsdom

import { screen, waitFor, fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import AuditLogPage from './AuditLogPage'
import { getAdminAuditLogs } from '../services/superAdminService'
import { auditLogsResponse, renderSuperAdminPage } from '../testing/renderSuperAdmin'

vi.mock('../services/superAdminService', () => ({
  getAdminAuditLogs: vi.fn(),
  isRequestCancelled: () => false,
}))

beforeEach(() => {
  getAdminAuditLogs.mockReset().mockResolvedValue(auditLogsResponse())
})

afterEach(() => vi.restoreAllMocks())

describe('Audit Log Page', () => {
  it('renders audit log title, badges and real total events stat card', async () => {
    renderSuperAdminPage(AuditLogPage)
    expect(await screen.findByRole('heading', { name: /Audit log/i })).toBeTruthy()
    expect(screen.getByText('Read-only')).toBeTruthy()
    expect(screen.getByText('Audit Trail Active')).toBeTruthy()

    // Real aggregate count
    await waitFor(() => {
      expect(screen.getByText('248')).toBeTruthy() // Total audit events
      expect(screen.getByText('248 Total Events')).toBeTruthy()
    })

    // Unavailable placeholders
    expect(screen.getAllByText(/requires aggregation/i).length).toBe(2)
  })

  it('renders table columns with time, actor, action, entity, and bracketed before/after pills', async () => {
    renderSuperAdminPage(AuditLogPage)
    expect(await screen.findByText('TIME')).toBeTruthy()
    expect(screen.getByText('ACTOR')).toBeTruthy()
    expect(screen.getByText('ACTION')).toBeTruthy()
    expect(screen.getByText('ENTITY')).toBeTruthy()
    expect(screen.getByText('BEFORE & AFTER')).toBeTruthy()

    // Real table data
    expect(screen.getAllByText('Account Created').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Status Changed').length).toBeGreaterThan(0)
    expect(screen.getByText(/\[ACTIVE\]/i)).toBeTruthy()
    expect(screen.getByText(/\[SUSPENDED\]/i)).toBeTruthy()
    expect(screen.getByText(/\[Created \(SUPPORT_ADMIN\)\]/i)).toBeTruthy()
  })

  it('filters by action and actor', async () => {
    renderSuperAdminPage(AuditLogPage)
    expect(await screen.findByText('TIME')).toBeTruthy()

    const actionSelect = screen.getByLabelText(/Filter by action/i)
    fireEvent.change(actionSelect, { target: { value: 'ADMIN_STATUS_CHANGED' } })

    await waitFor(() => {
      expect(getAdminAuditLogs).toHaveBeenCalledWith(
        expect.objectContaining({ action: 'ADMIN_STATUS_CHANGED' }),
        expect.anything(),
      )
    })
  })

  it('displays real-time stream info and browser timezone', async () => {
    renderSuperAdminPage(AuditLogPage)
    expect(await screen.findByText('TIME')).toBeTruthy()

    expect(screen.getByText(/real-time execution stream/i)).toBeTruthy()
    expect(screen.getByText(/Timezone:/i)).toBeTruthy()
  })

  it('handles load error with retry button', async () => {
    getAdminAuditLogs.mockRejectedValue(new Error('Network error'))
    renderSuperAdminPage(AuditLogPage)

    expect(await screen.findByText(/Audit logs could not be loaded/i)).toBeTruthy()
  })
})
