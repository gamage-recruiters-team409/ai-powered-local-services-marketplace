// @vitest-environment jsdom

import { screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ReportsExportPage from './ReportsExportPage'
import { getReport, exportReportCsv } from '../services/superAdminService'
import { disputeReport, renderSuperAdminPage } from '../testing/renderSuperAdmin'

vi.mock('../services/superAdminService', () => ({
  getReport: vi.fn(),
  exportReportCsv: vi.fn(),
  isRequestCancelled: () => false,
}))

vi.mock('../../support-operations/utils/download', () => ({
  downloadBlob: vi.fn(),
}))

beforeEach(() => {
  getReport.mockReset().mockResolvedValue(disputeReport())
  exportReportCsv.mockReset().mockResolvedValue({ blob: new Blob(['test']), filename: 'test.csv' })
})

afterEach(() => vi.restoreAllMocks())

describe('Reports & Export', () => {
  it('renders report page with disputes data table', async () => {
    renderSuperAdminPage(ReportsExportPage)
    expect(await screen.findByRole('heading', { name: /Reports/ })).toBeTruthy()
    // Disputes table should render with row data
    await waitFor(() => {
      expect(screen.getByText('2026-11-20')).toBeTruthy()
    })
  })

  it('renders column headers for disputes report', async () => {
    renderSuperAdminPage(ReportsExportPage)
    await screen.findByText('2026-11-20')
    expect(screen.getByText('Opened')).toBeTruthy()
    expect(screen.getByText('Resolved')).toBeTruthy()
    expect(screen.getByText('Full Refund')).toBeTruthy()
  })

  it('shows unavailable placeholder for non-disputes report types', async () => {
    getReport.mockResolvedValue({
      available: false,
      type: 'bookings-summary',
      reason: 'Not available yet — needs WP05 booking module',
    })
    renderSuperAdminPage(ReportsExportPage, { path: '/?type=bookings-summary' })
    expect(await screen.findByText(/Not available yet/)).toBeTruthy()
  })

  it('shows totals row when present', async () => {
    renderSuperAdminPage(ReportsExportPage)
    await screen.findByText('2026-11-20')
    expect(screen.getByText('Totals:')).toBeTruthy()
  })

  it('handles report load error', async () => {
    getReport.mockRejectedValue(new Error('Server error'))
    renderSuperAdminPage(ReportsExportPage)
    expect(await screen.findByText(/could not be loaded/i)).toBeTruthy()
  })

  it('renders report type selector with all options', async () => {
    renderSuperAdminPage(ReportsExportPage)
    await screen.findByText('2026-11-20')
    const select = screen.getByLabelText('Report')
    expect(select).toBeTruthy()
    expect(select.querySelectorAll('option').length).toBe(6)
  })
})
