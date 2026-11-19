// @vitest-environment jsdom

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  deleteAnnouncement,
  getAnnouncementSummary,
  listAnnouncements,
} from '../services/announcementService'
import { announcement, apiError, BASE, page, renderAnnouncements, SUMMARY } from '../testing/renderAnnouncements'

vi.mock('../services/announcementService', () => ({
  listAnnouncements: vi.fn(),
  getAnnouncementSummary: vi.fn(),
  getAnnouncement: vi.fn(),
  createAnnouncement: vi.fn(),
  updateAnnouncement: vi.fn(),
  deleteAnnouncement: vi.fn(),
  isRequestCancelled: () => false,
}))

const PUBLISHED = announcement({ id: 'aaaaaaaaaaaaaaaaaaaa0001', title: 'Easter Weekend Service Hours' })
const SCHEDULED = announcement({
  id: 'aaaaaaaaaaaaaaaaaaaa0002',
  title: 'Q2 Dispute Protocol Refinements',
  status: 'SCHEDULED',
  publishedAt: null,
  pinExpiresAt: null,
  scheduledAt: '2026-04-01T02:30:00Z',
  audience: 'CUSTOMERS',
  sendPush: false,
  pinToHeader: false,
})
const DRAFT = announcement({
  id: 'aaaaaaaaaaaaaaaaaaaa0003',
  title: 'Spring Cleaning Guidelines',
  status: 'DRAFT',
  publishedAt: null,
  pinExpiresAt: null,
  audience: 'PROVIDERS',
  pinToHeader: false,
})

function lastListCall() {
  return listAnnouncements.mock.calls.at(-1)[0]
}

function rowFor(title) {
  return screen.getByRole('link', { name: title }).closest('tr')
}

beforeEach(() => {
  listAnnouncements.mockReset().mockResolvedValue(page([PUBLISHED, SCHEDULED, DRAFT]))
  getAnnouncementSummary.mockReset().mockResolvedValue(SUMMARY)
  deleteAnnouncement.mockReset()
})

describe('Announcements list', () => {
  it('shows the summary cards, the recorded badge and the tab counts', async () => {
    renderAnnouncements(BASE)

    expect((await screen.findByTestId('summary-active')).textContent).toBe('1')
    expect(screen.getByTestId('summary-scheduled').textContent).toBe('1')
    expect(screen.getByTestId('summary-total').textContent).toBe('3')
    expect(screen.getByText('3 recorded')).toBeTruthy()
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual(['All3', 'Published1', 'Scheduled1', 'Drafts1'])
    expect(screen.getByRole('tab', { name: /All/ }).getAttribute('aria-selected')).toBe('true')
  })

  it('shows the right actions per status, audience chips and the delivery line', async () => {
    renderAnnouncements(BASE)
    await screen.findByRole('link', { name: PUBLISHED.title })

    const published = within(rowFor(PUBLISHED.title))
    expect(published.getByRole('link', { name: `View ${PUBLISHED.title}` })).toBeTruthy()
    expect(published.getByRole('link', { name: `Edit ${PUBLISHED.title}` })).toBeTruthy()
    expect(published.queryByRole('button', { name: /Delete/ })).toBeNull()
    expect(published.getByText('In-app banner + push notification')).toBeTruthy()
    expect(published.getByText('All users')).toBeTruthy()

    const scheduled = within(rowFor(SCHEDULED.title))
    expect(scheduled.getByRole('link', { name: `Edit ${SCHEDULED.title}` })).toBeTruthy()
    expect(scheduled.getByRole('button', { name: `Delete ${SCHEDULED.title}` })).toBeTruthy()
    expect(scheduled.queryByRole('link', { name: /^View/ })).toBeNull()
    expect(scheduled.getByText('Customers')).toBeTruthy()

    const draft = within(rowFor(DRAFT.title))
    expect(draft.getByRole('button', { name: `Delete ${DRAFT.title}` })).toBeTruthy()
    expect(draft.getByText(/^Edited /)).toBeTruthy()
  })

  it('the row title opens the view page', async () => {
    const router = renderAnnouncements(BASE)
    fireEvent.click(await screen.findByRole('link', { name: DRAFT.title }))

    await waitFor(() => expect(router.state.location.pathname).toBe(`${BASE}/${DRAFT.id}`))
  })

  it('tabs filter by status and keyboard arrows move between tabs', async () => {
    renderAnnouncements(BASE)
    await screen.findByRole('link', { name: PUBLISHED.title })

    fireEvent.click(screen.getByRole('tab', { name: /Scheduled/ }))
    await waitFor(() => expect(lastListCall()).toMatchObject({ status: 'SCHEDULED', page: 0, size: 5 }))

    fireEvent.keyDown(screen.getByRole('tab', { name: /Scheduled/ }), { key: 'ArrowRight' })
    await waitFor(() => expect(lastListCall()).toMatchObject({ status: 'DRAFT' }))
    expect(screen.getByRole('tab', { name: /Drafts/ }).getAttribute('aria-selected')).toBe('true')
  })

  it('search is debounced (~300 ms) and sends the trimmed text', async () => {
    renderAnnouncements(BASE)
    await screen.findByRole('link', { name: PUBLISHED.title })
    const callsBefore = listAnnouncements.mock.calls.length

    fireEvent.change(screen.getByRole('searchbox', { name: 'Search by title' }), { target: { value: ' mainten ' } })
    expect(listAnnouncements.mock.calls.slice(callsBefore).some(([filters]) => filters.q)).toBe(false)

    await waitFor(() => expect(lastListCall()).toMatchObject({ q: 'mainten', page: 0 }))
  })

  it('the filter popover filters by audience and can clear the filters', async () => {
    renderAnnouncements(BASE)
    await screen.findByRole('link', { name: PUBLISHED.title })

    fireEvent.click(screen.getByRole('button', { name: /Filter/ }))
    fireEvent.change(screen.getByLabelText('Audience'), { target: { value: 'PROVIDERS' } })
    await waitFor(() => expect(lastListCall()).toMatchObject({ audience: 'PROVIDERS' }))

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(lastListCall().audience).toBeUndefined())
  })

  it('pagination shows "Showing 1–5 of N" and requests the next page', async () => {
    listAnnouncements.mockResolvedValue(page([PUBLISHED, SCHEDULED, DRAFT, PUBLISHED, SCHEDULED].map((a, i) => ({ ...a, id: `p${i}` })), 12))
    renderAnnouncements(BASE)

    expect(await screen.findByText('Showing 1–5 of 12 announcements')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Go to page 2' }))
    await waitFor(() => expect(lastListCall()).toMatchObject({ page: 1 }))
  })

  it('shows an empty state per tab with a "New announcement" button', async () => {
    listAnnouncements.mockResolvedValue(page([]))
    const router = renderAnnouncements(`${BASE}?tab=drafts`)

    expect(await screen.findByText('No drafts')).toBeTruthy()
    expect(lastListCall()).toMatchObject({ status: 'DRAFT' })
    fireEvent.click(screen.getAllByRole('button', { name: 'New announcement' }).at(-1))
    await waitFor(() => expect(router.state.location.pathname).toBe(`${BASE}/new`))
  })

  it('shows an error state with retry', async () => {
    listAnnouncements.mockRejectedValueOnce(apiError(500, 'INTERNAL_SERVER_ERROR'))
    renderAnnouncements(BASE)

    expect(await screen.findByText('Announcements could not be loaded')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('link', { name: PUBLISHED.title })).toBeTruthy()
  })

  it('delete: Cancel closes without deleting; Delete removes it, shows a toast and reloads', async () => {
    deleteAnnouncement.mockResolvedValue()
    renderAnnouncements(BASE)
    fireEvent.click(await screen.findByRole('button', { name: `Delete ${DRAFT.title}` }))

    const dialog = screen.getByRole('dialog', { name: 'Delete draft' })
    expect(within(dialog).getByText(/This announcement and its settings will be permanently removed/)).toBeTruthy()
    expect(within(dialog).getByText('Draft')).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(deleteAnnouncement).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: `Delete ${SCHEDULED.title}` }))
    const scheduledDialog = screen.getByRole('dialog', { name: 'Delete scheduled announcement' })
    const callsBefore = listAnnouncements.mock.calls.length
    fireEvent.click(within(scheduledDialog).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(deleteAnnouncement).toHaveBeenCalledWith(SCHEDULED.id))
    expect(await screen.findByText(`"${SCHEDULED.title}" was deleted.`)).toBeTruthy()
    await waitFor(() => expect(listAnnouncements.mock.calls.length).toBeGreaterThan(callsBefore))
  })

  it('delete: Esc closes the dialog', async () => {
    renderAnnouncements(BASE)
    fireEvent.click(await screen.findByRole('button', { name: `Delete ${DRAFT.title}` }))

    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('delete: 409 (it was just published) shows a clear message and refreshes the list', async () => {
    deleteAnnouncement.mockRejectedValue(apiError(409, 'ANNOUNCEMENT_STATE_CONFLICT'))
    renderAnnouncements(BASE)
    fireEvent.click(await screen.findByRole('button', { name: `Delete ${SCHEDULED.title}` }))
    const callsBefore = listAnnouncements.mock.calls.length

    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))

    expect(await screen.findByText(/This announcement just changed/)).toBeTruthy()
    await waitFor(() => expect(listAnnouncements.mock.calls.length).toBeGreaterThan(callsBefore))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('SUPER_ADMIN (view only): no New, Edit or Delete; every row can be viewed', async () => {
    renderAnnouncements(BASE, { canEdit: false })
    await screen.findByRole('link', { name: PUBLISHED.title })

    expect(screen.queryByRole('button', { name: 'New announcement' })).toBeNull()
    expect(screen.queryByRole('link', { name: /^Edit / })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Delete / })).toBeNull()
    expect(screen.getAllByRole('link', { name: /^View / })).toHaveLength(3)
  })
})
