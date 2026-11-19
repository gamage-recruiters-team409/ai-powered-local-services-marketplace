// @vitest-environment jsdom

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  deleteAnnouncement,
  getAnnouncement,
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

const MESSAGE = [
  'Support hours change over **Easter**.',
  '',
  '- Disputes stay open',
  '- Payouts resume [Tuesday](https://example.com/payouts)',
  '',
  'Unsafe [click](javascript:alert(1)) stays text. <img src=x onerror=alert(1)>',
].join('\n')

beforeEach(() => {
  getAnnouncement.mockReset()
  deleteAnnouncement.mockReset()
  listAnnouncements.mockReset().mockResolvedValue(page([]))
  getAnnouncementSummary.mockReset().mockResolvedValue(SUMMARY)
})

describe('View announcement', () => {
  it('renders the markdown safely: formatting and https links only, never raw HTML', async () => {
    getAnnouncement.mockResolvedValue(announcement({ id: '65f1c0aa11bb22cc33a1b2c3', message: MESSAGE }))
    const { container } = { container: document.body }
    renderAnnouncements(`${BASE}/65f1c0aa11bb22cc33a1b2c3`)

    expect(await screen.findByRole('heading', { name: 'Easter Weekend Service Hours' })).toBeTruthy()
    expect(screen.getByText('Easter').tagName).toBe('STRONG')
    expect(within(screen.getByRole('article')).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Disputes stay open',
      'Payouts resume Tuesday',
    ])
    const link = screen.getByRole('link', { name: 'Tuesday' })
    expect(link.getAttribute('href')).toBe('https://example.com/payouts')
    expect(link.getAttribute('rel')).toContain('noopener')
    expect(screen.queryByRole('link', { name: 'click' })).toBeNull()
    expect(screen.getByText(/Unsafe \[click\]\(javascript:alert\(1\)\) stays text/)).toBeTruthy()
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('a[href^="javascript"]')).toBeNull()
  })

  it('shows the reference, status, audience, requested delivery options and no fake delivery numbers', async () => {
    getAnnouncement.mockResolvedValue(announcement({ id: '65f1c0aa11bb22cc33a1b2c3' }))
    renderAnnouncements(`${BASE}/65f1c0aa11bb22cc33a1b2c3`)

    expect(await screen.findByText('#A1B2C3')).toBeTruthy()
    expect(screen.getByText('Published')).toBeTruthy()
    expect(screen.getByText('All users')).toBeTruthy()
    expect(screen.getByText('Published on')).toBeTruthy()
    expect(screen.getByText('Admin')).toBeTruthy()
    expect(screen.getByText('Push alert requested')).toBeTruthy()
    expect(screen.getByText(/^Header pin requested until /)).toBeTruthy()
    expect(screen.getByText(/Delivery tracking will be available when notifications are connected\./)).toBeTruthy()
    expect(screen.queryByText(/Delivered to|Total views|Acknowledge rate/)).toBeNull()
  })

  it('delivery not applied: says it is published but NOT delivered, and never claims a push or pin was sent', async () => {
    getAnnouncement.mockResolvedValue(announcement({ id: 'pub-1', deliveryState: 'NOT_APPLIED' }))
    renderAnnouncements(`${BASE}/pub-1`)

    const note = await screen.findByText(/^Published, not delivered to users\./)
    expect(note.textContent).toContain('Delivery to users is not connected yet.')
    expect(note.textContent).toContain('no push alerts, emails or header banners are sent')
    expect(screen.queryByText(/^Pinned until|^Push alert$|has been sent|was delivered|Delivered/)).toBeNull()
  })

  it('ADMIN: Edit is shown; a published announcement cannot be deleted', async () => {
    getAnnouncement.mockResolvedValue(announcement({ id: 'pub-1' }))
    const router = renderAnnouncements(`${BASE}/pub-1`)

    fireEvent.click(await screen.findByRole('button', { name: 'Edit announcement' }))
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull()
    await waitFor(() => expect(router.state.location.pathname).toBe(`${BASE}/pub-1/edit`))
  })

  it('scheduled: Delete is offered and goes back to the list after deleting', async () => {
    getAnnouncement.mockResolvedValue(announcement({
      id: 'sch-1', status: 'SCHEDULED', publishedAt: null, pinExpiresAt: null, scheduledAt: '2026-12-01T03:30:00Z',
    }))
    deleteAnnouncement.mockResolvedValue()
    const router = renderAnnouncements(`${BASE}/sch-1`)

    expect(await screen.findByText('Scheduled for')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Delete scheduled announcement' }))
      .getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(deleteAnnouncement).toHaveBeenCalledWith('sch-1', 4))
    await waitFor(() => expect(router.state.location.pathname).toBe(BASE))
  })

  it('delete 409 NOT_DELETABLE: clear message and the announcement is reloaded', async () => {
    getAnnouncement.mockResolvedValue(announcement({ id: 'sch-1', status: 'SCHEDULED', publishedAt: null }))
    deleteAnnouncement.mockRejectedValue(apiError(409, 'ANNOUNCEMENT_NOT_DELETABLE'))
    renderAnnouncements(`${BASE}/sch-1`)

    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))

    expect(await screen.findByText(/already been published and can no longer be deleted/)).toBeTruthy()
    await waitFor(() => expect(getAnnouncement).toHaveBeenCalledTimes(2))
  })

  it('SUPER_ADMIN: no Edit and no Delete', async () => {
    getAnnouncement.mockResolvedValue(announcement({ id: 'sch-1', status: 'SCHEDULED', publishedAt: null }))
    renderAnnouncements(`${BASE}/sch-1`, { canEdit: false })

    expect(await screen.findByRole('heading', { name: 'Easter Weekend Service Hours' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Edit announcement' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull()
    expect(screen.getByRole('link', { name: /Back to announcements/ })).toBeTruthy()
  })

  it('unknown id: "Announcement not found"', async () => {
    getAnnouncement.mockRejectedValue(apiError(404, 'ANNOUNCEMENT_NOT_FOUND'))
    renderAnnouncements(`${BASE}/missing`)

    expect(await screen.findByText('Announcement not found')).toBeTruthy()
  })
})
