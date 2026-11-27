// @vitest-environment jsdom

import { fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LateCasesPage from './LateCasesPage'
import { exportLateCasesCsv, getLateCases } from '../services/operationsService'
import { lateCasesResponse, renderOpsPage } from '../testing/renderOps'

vi.mock('../services/operationsService', () => ({
  getLateCases: vi.fn(),
  recordLateCaseAction: vi.fn().mockResolvedValue({}),
  exportLateCasesCsv: vi.fn().mockResolvedValue({ blob: new Blob(['x']), filename: 'late.csv' }),
  isRequestCancelled: () => false,
}))

beforeEach(() => {
  getLateCases.mockReset().mockResolvedValue(lateCasesResponse({ lateAvailable: false }))
})

afterEach(() => vi.restoreAllMocks())

describe('Late cases page', () => {
  it.each(['SUPPORT_ADMIN', 'ADMIN'])('renders in the %s area with the late-start stub message', async (area) => {
    renderOpsPage(LateCasesPage, { area })
    expect(await screen.findByRole('heading', { name: 'Late cases' })).toBeTruthy()
    expect(await screen.findByText('Late-start tracking is not available yet')).toBeTruthy()
    expect(screen.queryByText(/Avg .*\/day|Auto-assign|Event Stream/)).toBeNull()
  })

  it('shows the separate No-show reports table with a source tag, and no late-start numbers', async () => {
    renderOpsPage(LateCasesPage)
    expect(await screen.findByRole('heading', { name: 'No-show reports' })).toBeTruthy()
    expect(screen.getByText('No-show dispute')).toBeTruthy()
    // No open/critical/resolved numbers while late starts are unavailable.
    expect(screen.queryByText('14')).toBeNull()
  })

  it('disables the urgency filter while late starts are unavailable', async () => {
    renderOpsPage(LateCasesPage)
    await screen.findByText('No-show dispute')
    expect(screen.getByLabelText('Urgency').disabled).toBe(true)
  })

  it('shows urgency chips, a 14-day bar chart and Handle when late starts are available', async () => {
    getLateCases.mockResolvedValue(lateCasesResponse({ lateAvailable: true }))
    renderOpsPage(LateCasesPage, { area: 'ADMIN' })
    expect(await screen.findByText('14')).toBeTruthy() // open late cases card
    expect(screen.getByText('4h 15m late')).toBeTruthy() // delay chip
    expect(screen.getAllByText('Critical').length).toBeGreaterThan(0) // urgency chip
    // per-day chart is a bar chart (divs), not an svg line chart
    const chart = document.querySelector('[aria-labelledby="late-chart"]')
    expect(chart.querySelectorAll('svg').length).toBe(0)
    expect(screen.getAllByRole('button', { name: 'Handle' }).length).toBeGreaterThan(0)
  })

  it('hides Handle for a view-only SUPER_ADMIN', async () => {
    getLateCases.mockResolvedValue(lateCasesResponse({ lateAvailable: true }))
    renderOpsPage(LateCasesPage, { area: 'SUPER_ADMIN', canEdit: false })
    await screen.findByText('14')
    expect(screen.queryByRole('button', { name: 'Handle' })).toBeNull()
  })

  it('warns when CSV export is source-limited (F4)', async () => {
    exportLateCasesCsv.mockResolvedValue({
      blob: new Blob(['x']),
      filename: 'late-cases.csv',
      sourceLimited: true,
      truncated: false,
    })
    renderOpsPage(LateCasesPage)
    fireEvent.click(await screen.findByRole('button', { name: 'Export' }))
    expect(await screen.findByText(/Data source limit reached; some records may be omitted/i)).toBeTruthy()
  })
})
