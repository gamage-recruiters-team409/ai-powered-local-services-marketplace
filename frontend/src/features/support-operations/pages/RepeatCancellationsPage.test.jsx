// @vitest-environment jsdom

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import RepeatCancellationsPage from './RepeatCancellationsPage'
import { exportRepeatCsv, getRepeatCancellations, recordRepeatAction } from '../services/operationsService'
import { renderOpsPage, repeatSection } from '../testing/renderOps'

vi.mock('../services/operationsService', () => ({
  getRepeatCancellations: vi.fn(),
  recordRepeatAction: vi.fn(),
  exportRepeatCsv: vi.fn().mockResolvedValue({ blob: new Blob(['x']), filename: 'repeat.csv' }),
  isRequestCancelled: () => false,
}))

beforeEach(() => {
  getRepeatCancellations.mockReset().mockResolvedValue(repeatSection())
  recordRepeatAction.mockReset().mockResolvedValue({ caseStatus: 'SUSPENSION_RECOMMENDED' })
})

afterEach(() => vi.restoreAllMocks())

describe('Repeat cancellations page', () => {
  it.each(['SUPPORT_ADMIN', 'ADMIN'])('renders cards, the threshold chip and top-5 bars in the %s area', async (area) => {
    renderOpsPage(RepeatCancellationsPage, { area })
    expect(await screen.findByRole('heading', { name: 'Repeat cancellations' })).toBeTruthy()
    expect(screen.getByText(/Threshold:.*4 cancellations \/ 30d/)).toBeTruthy()
    expect(await screen.findByText('6')).toBeTruthy() // flagged users
    // top-5 ranking is a horizontal bar list, not an svg chart
    const top = document.querySelector('[aria-labelledby="repeat-top"]')
    expect(top.querySelectorAll('svg').length).toBe(0)
  })

  it('does not show a cancellation rate % (WP05 does not provide totals)', async () => {
    renderOpsPage(RepeatCancellationsPage)
    await screen.findByText('6')
    expect(screen.queryByText(/Rate:|%\)/)).toBeNull()
  })

  it('SUPPORT_ADMIN sees Flag concern + Recommend suspension, and never Suspension or Account Ban', async () => {
    renderOpsPage(RepeatCancellationsPage, { area: 'SUPPORT_ADMIN' })
    fireEvent.click(await screen.findByRole('button', { name: 'Take action' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('option', { name: 'Flag concern' })).toBeTruthy()
    expect(within(dialog).getByRole('option', { name: 'Recommend suspension' })).toBeTruthy()
    expect(within(dialog).queryByRole('option', { name: 'Suspension' })).toBeNull()
    expect(within(dialog).queryByRole('option', { name: /Ban/ })).toBeNull()
  })

  it('ADMIN sees Flag concern + Suspension, and never Account Ban or timed suspension', async () => {
    renderOpsPage(RepeatCancellationsPage, { area: 'ADMIN' })
    fireEvent.click(await screen.findByRole('button', { name: 'Take action' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('option', { name: 'Suspension' })).toBeTruthy()
    expect(within(dialog).queryByRole('option', { name: 'Recommend suspension' })).toBeNull()
    expect(within(dialog).queryByRole('option', { name: /Ban/ })).toBeNull()
    expect(within(dialog).queryByText(/14 days/)).toBeNull()
  })

  it('shows the "stays until an admin reactivates" note for Suspension', async () => {
    renderOpsPage(RepeatCancellationsPage, { area: 'ADMIN' })
    fireEvent.click(await screen.findByRole('button', { name: 'Take action' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByRole('combobox'), { target: { value: 'SUSPEND' } })
    expect(within(dialog).getByText(/stays until an admin reactivates/)).toBeTruthy()
  })

  it('hides Take action for a view-only SUPER_ADMIN', async () => {
    renderOpsPage(RepeatCancellationsPage, { area: 'SUPER_ADMIN', canEdit: false })
    await screen.findByText('6')
    expect(screen.queryByRole('button', { name: 'Take action' })).toBeNull()
  })

  it('records the action on apply', async () => {
    renderOpsPage(RepeatCancellationsPage, { area: 'ADMIN' })
    fireEvent.click(await screen.findByRole('button', { name: 'Take action' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByRole('combobox'), { target: { value: 'FLAG_CONCERN' } })
    fireEvent.change(within(dialog).getByLabelText(/Administrative note/), {
      target: { value: 'Repeated short-notice cancellations across bookings.' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply' }))
    await waitFor(() =>
      expect(recordRepeatAction).toHaveBeenCalledWith('user-david-miller', expect.objectContaining({ action: 'FLAG_CONCERN' })),
    )
  })

  it('warns when account is suspended but operations record persistence is pending (partial success F3)', async () => {
    recordRepeatAction.mockResolvedValue({
      caseType: 'REPEAT_CANCELLATIONS',
      subjectId: 'user-david-miller',
      action: 'SUSPEND',
      caseStatus: 'SUSPENDED',
      suspensionApplied: true,
      listingVisibilitySynced: true,
      opsRecordPending: true,
    })
    renderOpsPage(RepeatCancellationsPage, { area: 'ADMIN' })
    fireEvent.click(await screen.findByRole('button', { name: 'Take action' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByRole('combobox'), { target: { value: 'SUSPEND' } })
    fireEvent.change(within(dialog).getByLabelText(/Administrative note/), {
      target: { value: 'Excessive cancellations impacting marketplace reliability.' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply' }))

    expect(await screen.findByText(/operations log could not be saved.*reconciled automatically/i)).toBeTruthy()
  })

  it('warns when account is suspended but provider listing visibility sync failed (partial success F3)', async () => {
    recordRepeatAction.mockResolvedValue({
      caseType: 'REPEAT_CANCELLATIONS',
      subjectId: 'user-david-miller',
      action: 'SUSPEND',
      caseStatus: 'SUSPENDED',
      suspensionApplied: true,
      listingVisibilitySynced: false,
      opsRecordPending: false,
    })
    renderOpsPage(RepeatCancellationsPage, { area: 'ADMIN' })
    fireEvent.click(await screen.findByRole('button', { name: 'Take action' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.change(within(dialog).getByRole('combobox'), { target: { value: 'SUSPEND' } })
    fireEvent.change(within(dialog).getByLabelText(/Administrative note/), {
      target: { value: 'Excessive cancellations impacting marketplace reliability.' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply' }))

    expect(await screen.findByText(/listing visibility synchronization remains incomplete.*verify listings/i)).toBeTruthy()
  })

  it('warns when CSV export is source-limited (F4)', async () => {
    exportRepeatCsv.mockResolvedValue({
      blob: new Blob(['x']),
      filename: 'repeat-cancellations.csv',
      sourceLimited: true,
      truncated: false,
    })
    renderOpsPage(RepeatCancellationsPage)
    fireEvent.click(await screen.findByRole('button', { name: 'Export log' }))
    expect(await screen.findByText(/Data source limit reached; some records may be omitted/i)).toBeTruthy()
  })

  it('warns when CSV export is truncated at max limit (F4)', async () => {
    exportRepeatCsv.mockResolvedValue({
      blob: new Blob(['x']),
      filename: 'repeat-cancellations.csv',
      sourceLimited: false,
      truncated: true,
    })
    renderOpsPage(RepeatCancellationsPage)
    fireEvent.click(await screen.findByRole('button', { name: 'Export log' }))
    expect(await screen.findByText(/Export capped at maximum export limit/i)).toBeTruthy()
  })

  it('shows standard success notification when CSV export is complete (F4)', async () => {
    exportRepeatCsv.mockResolvedValue({
      blob: new Blob(['x']),
      filename: 'repeat-cancellations.csv',
      sourceLimited: false,
      truncated: false,
    })
    renderOpsPage(RepeatCancellationsPage)
    fireEvent.click(await screen.findByRole('button', { name: 'Export log' }))
    expect(await screen.findByText(/Exported repeat-cancellations\.csv\./)).toBeTruthy()
  })
})
