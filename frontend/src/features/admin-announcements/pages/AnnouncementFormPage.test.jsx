// @vitest-environment jsdom

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  createAnnouncement,
  getAnnouncement,
  getAnnouncementSummary,
  listAnnouncements,
  updateAnnouncement,
} from '../services/announcementService'
import { announcement, apiError, BASE, page, renderAnnouncements, SUMMARY } from '../testing/renderAnnouncements'
import { toLocalInputValue } from '../utils/datetime'

vi.mock('../services/announcementService', () => ({
  listAnnouncements: vi.fn(),
  getAnnouncementSummary: vi.fn(),
  getAnnouncement: vi.fn(),
  createAnnouncement: vi.fn(),
  updateAnnouncement: vi.fn(),
  deleteAnnouncement: vi.fn(),
  isRequestCancelled: () => false,
}))

const titleInput = () => screen.getByRole('textbox', { name: /Announcement title/ })
const messageInput = () => screen.getByRole('textbox', { name: /Announcement message/ })
const scheduleInput = () => screen.getByLabelText(/Scheduled dispatch/)

function fill({ title = 'Planned maintenance', message = 'We are **offline** on Sunday.' } = {}) {
  fireEvent.change(titleInput(), { target: { value: title } })
  fireEvent.change(messageInput(), { target: { value: message } })
}

afterEach(() => {
  vi.useRealTimers()
})

beforeEach(() => {
  createAnnouncement.mockReset()
  updateAnnouncement.mockReset()
  getAnnouncement.mockReset()
  listAnnouncements.mockReset().mockResolvedValue(page([]))
  getAnnouncementSummary.mockReset().mockResolvedValue(SUMMARY)
})

describe('New announcement form', () => {
  it('shows the Draft chip, the title counter, the browser time zone and no recipient estimate', () => {
    renderAnnouncements(`${BASE}/new`)

    expect(screen.getByRole('heading', { name: 'New announcement' })).toBeTruthy()
    expect(screen.getByText('Draft')).toBeTruthy()
    expect(screen.getByText('0 / 80 characters')).toBeTruthy()
    fireEvent.change(titleInput(), { target: { value: 'Hello' } })
    expect(screen.getByText('5 / 80 characters')).toBeTruthy()
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
    expect(screen.getByText(new RegExp(`Times shown in ${zone.replace('/', '\\/')}`))).toBeTruthy()
    expect(screen.queryByText(/Eastern Time|UTC-04:00/)).toBeNull()
    expect(screen.queryByText(/Deliverable to approximately/)).toBeNull()
    expect(screen.queryByRole('button', { name: /Underline/ })).toBeNull()
  })

  it('validates required fields, the 80-character title and raw HTML before calling the API', async () => {
    renderAnnouncements(`${BASE}/new`)

    fireEvent.click(screen.getByRole('button', { name: 'Publish announcement' }))
    expect(screen.getByText('Enter a title.')).toBeTruthy()
    expect(screen.getByText('Enter a message.')).toBeTruthy()

    fill({ title: 'T'.repeat(81), message: '<script>alert(1)</script>' })
    fireEvent.click(screen.getByRole('button', { name: 'Publish announcement' }))
    expect(screen.getByText('Title must be at most 80 characters.')).toBeTruthy()
    expect(screen.getByText('Message is markdown and must not contain HTML tags.')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(createAnnouncement).not.toHaveBeenCalled()
  })

  it('Save draft sends intent DRAFT (trimmed title, no schedule) and opens the Drafts tab', async () => {
    createAnnouncement.mockResolvedValue(announcement({ id: 'new-1', status: 'DRAFT' }))
    const router = renderAnnouncements(`${BASE}/new`)
    fill({ title: '  Planned maintenance  ' })

    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }))

    await waitFor(() => expect(createAnnouncement).toHaveBeenCalledWith({
      title: 'Planned maintenance',
      message: 'We are **offline** on Sunday.',
      audience: 'ALL_USERS',
      scheduledAt: null,
      sendPush: true,
      pinToHeader: false,
      intent: 'DRAFT',
    }))
    await waitFor(() => expect(router.state.location.search).toBe('?tab=drafts'))
    expect(await screen.findByText('Draft saved.')).toBeTruthy()
  })

  it('Publish now: the modal says "Publish now", then sends intent PUBLISH without a schedule', async () => {
    createAnnouncement.mockResolvedValue(announcement({ id: 'new-1', status: 'PUBLISHED' }))
    const router = renderAnnouncements(`${BASE}/new`)
    fill()
    fireEvent.click(screen.getByLabelText('Pin to user portal header'))

    fireEvent.click(screen.getByRole('button', { name: 'Publish announcement' }))
    const dialog = screen.getByRole('dialog', { name: 'Publish announcement now' })
    expect(within(dialog).getByText('Planned maintenance')).toBeTruthy()
    expect(within(dialog).getByText('All users')).toBeTruthy()
    expect(within(dialog).getByText('Publish now', { selector: 'dd' })).toBeTruthy()
    expect(within(dialog).getByText(/Pin to the user portal header for 48 hours/)).toBeTruthy()
    expect(within(dialog).queryByText(/recipients/i)).toBeNull()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish now' }))

    await waitFor(() => expect(createAnnouncement).toHaveBeenCalledWith(expect.objectContaining({
      intent: 'PUBLISH', scheduledAt: null, pinToHeader: true,
    })))
    expect(createAnnouncement.mock.calls[0][0]).not.toHaveProperty('version')
    await waitFor(() => expect(router.state.location.search).toBe('?tab=published'))
    expect(await screen.findByText(/^Announcement published\. Delivery to users is pending/)).toBeTruthy()
  })

  it('delivery not connected: the modal and the form say publishing saves it and changes its status, nothing is sent', () => {
    renderAnnouncements(`${BASE}/new`)
    fill()
    fireEvent.click(screen.getByLabelText('Pin to user portal header'))
    expect(screen.getByText(/These options are saved with the announcement\./).textContent)
      .toContain('Delivery to users is not connected yet.')

    fireEvent.click(screen.getByRole('button', { name: 'Publish announcement' }))
    const dialog = screen.getByRole('dialog', { name: 'Publish announcement now' })
    const info = within(dialog).getByText(/its status changes to Published now/)
    expect(info.textContent).toContain('Delivery to users is not connected yet.')
    expect(info.textContent).toContain('no push alerts, emails or header banners are sent until notifications are integrated')
    expect(within(dialog).getByText('Requested delivery options')).toBeTruthy()
    expect(within(dialog).queryByText(/will be sent to|sent to the selected users|will be sent at/)).toBeNull()
    expect(dialog.getAttribute('aria-describedby')).toBe(info.parentElement.id)
  })

  it('Scheduled: the modal shows the local time and the schedule is sent as UTC ISO', async () => {
    const local = toLocalInputValue(new Date(Date.now() + 2 * 60 * 60 * 1000))
    createAnnouncement.mockResolvedValue(announcement({ id: 'new-1', status: 'SCHEDULED', scheduledAt: new Date(local).toISOString() }))
    const router = renderAnnouncements(`${BASE}/new`)
    fill()
    fireEvent.change(scheduleInput(), { target: { value: local } })

    fireEvent.click(screen.getByRole('button', { name: 'Publish announcement' }))
    const dialog = screen.getByRole('dialog', { name: 'Schedule announcement' })
    expect(within(dialog).getByText(/^Scheduled: /)).toBeTruthy()
    expect(within(dialog).getByText(/saved as Scheduled and changes to Published at the scheduled time/).textContent)
      .toContain('Delivery to users is not connected yet.')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Schedule' }))

    await waitFor(() => expect(createAnnouncement).toHaveBeenCalled())
    const sent = createAnnouncement.mock.calls[0][0].scheduledAt
    expect(sent).toBe(new Date(local).toISOString())
    expect(sent.endsWith('Z')).toBe(true)
    await waitFor(() => expect(router.state.location.search).toBe('?tab=scheduled'))
  })

  it('rejects a schedule in the past', () => {
    renderAnnouncements(`${BASE}/new`)
    fill()
    fireEvent.change(scheduleInput(), { target: { value: toLocalInputValue(new Date(Date.now() - 60 * 60 * 1000)) } })

    fireEvent.click(screen.getByRole('button', { name: 'Publish announcement' }))
    expect(screen.getByText('The scheduled time is in the past.')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('rejects a schedule within the backend 1-minute threshold (it would be published immediately)', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 19, 10, 0, 20))
    renderAnnouncements(`${BASE}/new`)
    fill()
    // 10:01:00 is 40 seconds ahead: not more than 1 minute.
    fireEvent.change(scheduleInput(), { target: { value: '2026-11-19T10:01' } })

    fireEvent.click(screen.getByRole('button', { name: 'Publish announcement' }))
    expect(screen.getByText(/A schedule must be more than 1 minute from now\./)).toBeTruthy()
    expect(scheduleInput().getAttribute('aria-invalid')).toBe('true')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(createAnnouncement).not.toHaveBeenCalled()
  })

  it('schedule that falls inside the threshold while the modal is open: Schedule is refused, nothing is sent', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 19, 10, 0, 30))
    renderAnnouncements(`${BASE}/new`)
    fill()
    fireEvent.change(scheduleInput(), { target: { value: '2026-11-19T10:02' } })

    fireEvent.click(screen.getByRole('button', { name: 'Publish announcement' }))
    const dialog = screen.getByRole('dialog', { name: 'Schedule announcement' })
    vi.setSystemTime(new Date(2026, 10, 19, 10, 1, 30))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Schedule' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByText(/A schedule must be more than 1 minute from now\./)).toBeTruthy()
    expect(createAnnouncement).not.toHaveBeenCalled()
  })

  it('shows backend validation errors next to the field', async () => {
    createAnnouncement.mockRejectedValue(apiError(400, 'VALIDATION_FAILED', [
      { field: 'message', message: 'message is markdown and must not contain HTML tags' },
    ]))
    renderAnnouncements(`${BASE}/new`)
    fill()

    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }))

    expect(await screen.findByText('message is markdown and must not contain HTML tags')).toBeTruthy()
    expect(messageInput().getAttribute('aria-invalid')).toBe('true')
  })

  it('publish failure: an error toast, the modal closes and the form keeps the input', async () => {
    createAnnouncement.mockRejectedValue(apiError(500, 'INTERNAL_SERVER_ERROR'))
    renderAnnouncements(`${BASE}/new`)
    fill()
    fireEvent.click(screen.getByRole('button', { name: 'Publish announcement' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Publish now' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect((await screen.findAllByText('INTERNAL_SERVER_ERROR')).length).toBeGreaterThan(0)
    expect(titleInput().value).toBe('Planned maintenance')
  })

  it('toolbar wraps the selection in bold and the preview renders it', () => {
    renderAnnouncements(`${BASE}/new`)
    fireEvent.change(messageInput(), { target: { value: 'Sale today' } })
    messageInput().setSelectionRange(0, 4)

    fireEvent.click(screen.getByRole('button', { name: 'Bold' }))
    expect(messageInput().value).toBe('**Sale** today')

    fireEvent.click(screen.getByRole('button', { name: 'Preview banner' }))
    const preview = screen.getByLabelText('Banner preview')
    expect(within(preview).getByText('Sale').tagName).toBe('STRONG')
  })

  it('warns about unsaved changes before leaving', async () => {
    const router = renderAnnouncements(`${BASE}/new`)
    fireEvent.change(titleInput(), { target: { value: 'Half written' } })

    fireEvent.click(screen.getByRole('link', { name: 'Cancel' }))
    const dialog = await screen.findByRole('dialog', { name: 'Discard unsaved changes?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stay on page' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(router.state.location.pathname).toBe(`${BASE}/new`)

    fireEvent.click(screen.getByRole('link', { name: 'Cancel' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Leave without saving' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(BASE))
  })
})

describe('Edit announcement form', () => {
  const PUBLISHED = announcement({ id: 'pub-1', scheduledAt: '2026-03-28T03:30:00.123Z' })

  it('published: only title and message are editable; the other fields are sent unchanged', async () => {
    getAnnouncement.mockResolvedValue(PUBLISHED)
    updateAnnouncement.mockResolvedValue({ ...PUBLISHED, title: 'Corrected title' })
    const router = renderAnnouncements(`${BASE}/pub-1/edit`)

    expect(await screen.findByRole('heading', { name: 'Edit announcement' })).toBeTruthy()
    expect(screen.getAllByText('Locked after publishing').length).toBe(3)
    expect(screen.getByLabelText(/Target audience/).disabled).toBe(true)
    expect(scheduleInput().disabled).toBe(true)
    expect(screen.getByLabelText('Send push notification alert').disabled).toBe(true)
    expect(screen.queryByRole('button', { name: 'Save draft' })).toBeNull()
    expect(titleInput().disabled).toBe(false)

    fireEvent.change(titleInput(), { target: { value: 'Corrected title' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(updateAnnouncement).toHaveBeenCalledWith('pub-1', {
      title: 'Corrected title',
      message: PUBLISHED.message,
      audience: 'ALL_USERS',
      scheduledAt: '2026-03-28T03:30:00.123Z',
      sendPush: true,
      pinToHeader: true,
      intent: 'PUBLISH',
      version: 4,
    }))
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(router.state.location.pathname).toBe(`${BASE}/pub-1`))
  })

  it('scheduled: fields are prefilled, the Scheduled chip is shown and the draft button says "Move to drafts"', async () => {
    getAnnouncement.mockResolvedValue(announcement({
      id: 'sch-1', status: 'SCHEDULED', publishedAt: null, pinExpiresAt: null,
      scheduledAt: new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString(), audience: 'PROVIDERS',
    }))
    renderAnnouncements(`${BASE}/sch-1/edit`)

    expect((await screen.findByRole('textbox', { name: /Announcement title/ })).value).toBe('Easter Weekend Service Hours')
    expect(screen.getByText('Scheduled')).toBeTruthy()
    expect(screen.getByLabelText(/Target audience/).value).toBe('PROVIDERS')
    expect(scheduleInput().value).not.toBe('')
    expect(screen.getByRole('button', { name: 'Move to drafts' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Save draft' })).toBeNull()
  })

  it('sends the version the form was loaded with when saving a draft', async () => {
    getAnnouncement.mockResolvedValue(announcement({ id: 'dr-1', status: 'DRAFT', publishedAt: null, version: 7 }))
    updateAnnouncement.mockResolvedValue(announcement({ id: 'dr-1', status: 'DRAFT', version: 8 }))
    renderAnnouncements(`${BASE}/dr-1/edit`)

    fireEvent.change(await screen.findByRole('textbox', { name: /Announcement title/ }), { target: { value: 'Mine' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }))

    await waitFor(() => expect(updateAnnouncement).toHaveBeenCalledWith('dr-1', expect.objectContaining({
      title: 'Mine', intent: 'DRAFT', version: 7,
    })))
  })

  it('stale form: another admin saved first -> 409 UPDATE_CONFLICT is shown, nothing is overwritten, reload shows theirs', async () => {
    const draft = { id: 'dr-1', status: 'DRAFT', publishedAt: null, pinExpiresAt: null }
    getAnnouncement
      .mockResolvedValueOnce(announcement({ ...draft, title: 'Original', version: 4 }))
      .mockResolvedValueOnce(announcement({ ...draft, title: 'Title from admin B', version: 5 }))
    updateAnnouncement.mockRejectedValue(apiError(409, 'ANNOUNCEMENT_UPDATE_CONFLICT'))
    const router = renderAnnouncements(`${BASE}/dr-1/edit`)

    fireEvent.change(await screen.findByRole('textbox', { name: /Announcement title/ }), { target: { value: 'Title from admin A' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }))

    await waitFor(() => expect(updateAnnouncement).toHaveBeenCalledWith('dr-1', expect.objectContaining({ version: 4 })))
    expect((await screen.findAllByText(/changed this announcement after you opened it/)).length).toBeGreaterThan(0)
    expect(router.state.location.pathname).toBe(`${BASE}/dr-1/edit`)
    expect(titleInput().value).toBe('Title from admin A')

    fireEvent.click(screen.getByRole('button', { name: 'Reload latest version' }))
    await waitFor(() => expect(titleInput().value).toBe('Title from admin B'))
    expect(getAnnouncement).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('button', { name: 'Reload latest version' })).toBeNull()
  })

  it('shows "not found" for an unknown announcement', async () => {
    getAnnouncement.mockRejectedValue(apiError(404, 'ANNOUNCEMENT_NOT_FOUND'))
    renderAnnouncements(`${BASE}/missing/edit`)

    expect(await screen.findByText('Announcement not found')).toBeTruthy()
  })
})

describe('SUPER_ADMIN (view only)', () => {
  it('new and edit pages show "You have view-only access" and never load or save', () => {
    renderAnnouncements(`${BASE}/new`, { canEdit: false })
    expect(screen.getByText('You have view-only access')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Publish|Save/ })).toBeNull()
  })

  it('edit page too', () => {
    renderAnnouncements(`${BASE}/pub-1/edit`, { canEdit: false })
    expect(screen.getByText('You have view-only access')).toBeTruthy()
    expect(getAnnouncement).not.toHaveBeenCalled()
  })
})
