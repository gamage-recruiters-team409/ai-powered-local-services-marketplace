// @vitest-environment jsdom

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import OverdueBookingsPage from './OverdueBookingsPage'
import { exportOverdueCsv, getOverdueBookings, recordOverdueAction } from '../services/operationsService'
import { opsPage, overdueRow, overdueSection, renderOpsPage, section, unavailable } from '../testing/renderOps'

vi.mock('../services/operationsService', () => ({
  getOverdueBookings: vi.fn(),
  recordOverdueAction: vi.fn(),
  exportOverdueCsv: vi.fn(),
  isRequestCancelled: () => false,
}))

beforeEach(() => {
  getOverdueBookings.mockReset().mockResolvedValue(overdueSection())
  recordOverdueAction.mockReset().mockResolvedValue({ caseStatus: 'CONTACTED' })
  exportOverdueCsv.mockReset().mockResolvedValue({ blob: new Blob(['x']), filename: 'overdue-bookings.csv' })
  URL.createObjectURL = vi.fn(() => 'blob:test')
  URL.revokeObjectURL = vi.fn()
})

afterEach(() => vi.restoreAllMocks())

describe('Overdue bookings page', () => {
  it.each(['SUPPORT_ADMIN', 'ADMIN'])('renders cards, the action chip and no fake numbers in the %s area', async (area) => {
    renderOpsPage(OverdueBookingsPage, { area })

    expect(await screen.findByRole('heading', { name: /Overdue bookings/ })).toBeTruthy()
    expect(await screen.findByText('7')).toBeTruthy()
    expect(screen.getByText('3')).toBeTruthy()
    expect(screen.getByText('4')).toBeTruthy()
    expect(screen.getByText('7 Action Required')).toBeTruthy()
    expect(screen.queryByText(/since morning|SLA breach|vs yesterday|Event Stream|Auto-assign|Automated refresh/)).toBeNull()
  })

  it('shows the Overdue-by-days distribution as bars (no line/area chart)', async () => {
    renderOpsPage(OverdueBookingsPage)
    expect(await screen.findByText('1 Day')).toBeTruthy()
    expect(screen.getByText('4+ Days')).toBeTruthy()
    // The distribution is a horizontal bar list (CSS divs) — never an SVG line/area chart.
    const dist = document.querySelector('[aria-labelledby="ovd-dist"]')
    expect(dist.querySelectorAll('svg').length).toBe(0)
  })

  it('shows Handle for an editor', async () => {
    renderOpsPage(OverdueBookingsPage, { canEdit: true })
    expect(await screen.findByRole('button', { name: 'Handle' })).toBeTruthy()
  })

  it('hides Handle for a view-only SUPER_ADMIN', async () => {
    renderOpsPage(OverdueBookingsPage, { area: 'SUPER_ADMIN', canEdit: false })
    await screen.findByText('#BK-9102')
    expect(screen.queryByRole('button', { name: 'Handle' })).toBeNull()
  })

  it('opens the Handle modal, requires a note, and records the action on save', async () => {
    renderOpsPage(OverdueBookingsPage, { area: 'ADMIN' })
    fireEvent.click(await screen.findByRole('button', { name: 'Handle' }))

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('Handle overdue booking')).toBeTruthy()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByText(/note of 10–1000 characters/)).toBeTruthy()
    expect(recordOverdueAction).not.toHaveBeenCalled()

    fireEvent.change(within(dialog).getByLabelText(/Administrative note/), {
      target: { value: 'Called the provider to confirm the job status.' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(recordOverdueAction).toHaveBeenCalledWith('bk-1', expect.objectContaining({ action: 'CONTACT_PROVIDER' })),
    )
    await waitFor(() => expect(getOverdueBookings).toHaveBeenCalledTimes(2))
  })

  it('renders a placeholder with the reason and no numbers when the source is unavailable', async () => {
    getOverdueBookings.mockResolvedValue(unavailable('Booking overdue source failed (WP05).'))
    renderOpsPage(OverdueBookingsPage)
    expect(await screen.findByText('Booking overdue source failed (WP05).')).toBeTruthy()
    expect(screen.queryByText('7 Action Required')).toBeNull()
  })

  it('exports the CSV', async () => {
    renderOpsPage(OverdueBookingsPage)
    fireEvent.click(await screen.findByRole('button', { name: 'Export' }))
    await waitFor(() => expect(exportOverdueCsv).toHaveBeenCalled())
  })

  it('F6: filtering the table does not change distribution percentages or produce values above 100%', async () => {
    // 50 bookings in 1-day bucket out of 100 total overdue bookings, but table returns only 5 filtered rows
    getOverdueBookings.mockResolvedValue(
      section({
        rows: opsPage([overdueRow()], 5), // filtered totalElements = 5
        summary: { overdueNow: 100, over24h: 50, resolvedToday: 0 },
        distribution: { oneDay: 50, twoDays: 20, threeDays: 15, fourPlusDays: 15 },
      }),
    )

    renderOpsPage(OverdueBookingsPage)

    // The percentage should be 50 / 100 * 100 = 50%, NOT 50 / 5 * 100 = 1000%
    expect(await screen.findByText('(50%)')).toBeTruthy()
    expect(screen.queryByText(/1000%/)).toBeNull()
    expect(screen.getByText('(20%)')).toBeTruthy()
    expect(screen.getAllByText('(15%)').length).toBe(2)
  })
})
