// @vitest-environment jsdom
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'

import { ToastProvider } from '../../../shared/components/feedback'
import { AreaAccessContext } from '../../disputes-admin/access/accessContexts'
import ModerationDetailPage from '../pages/admin/ModerationDetailPage'
import ModerationQueuePage from '../pages/admin/ModerationQueuePage'

// These screen tests open dialogs and type text; slower PCs need more than the 5 s default.
vi.setConfig({ testTimeout: 20000 })

// jsdom does not implement <dialog>.showModal()/close(); the shared Modal needs them.
beforeAll(() => {
  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function showModal() { this.setAttribute('open', '') }
  }
  if (!HTMLDialogElement.prototype.close) {
    HTMLDialogElement.prototype.close = function close() { this.removeAttribute('open') }
  }
})

afterEach(() => cleanup())

const BASE = '/admin/moderator/moderation/reviews'

const held = {
  id: 'r1', bookingId: 'bk-1', providerId: 'p-1', providerName: "Maya's Home Care", customerId: 'c-1',
  customerName: 'Amaya R', rating: 5, comment: 'Great! Call 077 123 4567', status: 'AWAITING_MODERATION',
  moderationFlags: ['CONTACT_DETAILS'], openReportCount: 0, edited: false, providerReply: null,
  removalReason: null, createdAt: '2026-11-18T04:00:00Z', moderationRevision: 3,
}
const reported = {
  ...held, id: 'r2', customerName: 'Nimali F', rating: 1, comment: 'Bad service', status: 'PUBLISHED',
  moderationFlags: [], openReportCount: 2, moderationRevision: 5,
}

function fakeApi(overrides = {}) {
  return {
    getQueue: vi.fn().mockResolvedValue({ content: [held, reported], page: 0, size: 10, totalElements: 2, totalPages: 1 }),
    getReview: vi.fn().mockImplementation(async (id) => ({
      review: id === 'r1' ? held : reported,
      openReports: id === 'r2' ? [{ id: 'rp1', reviewId: 'r2', reporterRole: 'PROVIDER', reason: 'OFFENSIVE', details: 'Rude', status: 'OPEN', createdAt: '2026-11-18T05:00:00Z' }] : [],
      history: [],
    })),
    publishReview: vi.fn().mockResolvedValue({ ...held, status: 'PUBLISHED' }),
    removeReview: vi.fn().mockResolvedValue({ ...held, status: 'REMOVED' }),
    ...overrides,
  }
}

function renderAt(path, api, { canEdit = true } = {}) {
  const access = { area: 'MODERATOR', mode: canEdit ? 'edit' : 'view', canEdit, isViewOnly: !canEdit }
  const router = createMemoryRouter(
    [
      { path: BASE, element: <ModerationQueuePage api={api} /> },
      { path: `${BASE}/:reviewId`, element: <ModerationDetailPage api={api} /> },
    ].map((route) => ({
      ...route,
      element: (
        <AreaAccessContext.Provider value={access}>
          <ToastProvider>{route.element}</ToastProvider>
        </AreaAccessContext.Provider>
      ),
    })),
    { initialEntries: [path] },
  )
  render(<RouterProvider router={router} />)
  return router
}

describe('S14 Review moderation queue', () => {
  it('lists held and reported reviews and filters by tab', async () => {
    const api = fakeApi()
    const user = userEvent.setup()
    renderAt(BASE, api)

    expect(await screen.findByText('Amaya R', { exact: false })).toBeTruthy()
    expect(screen.getByText('Contact details')).toBeTruthy()
    expect(screen.getByText('2 reports')).toBeTruthy()
    expect(api.getQueue).toHaveBeenCalledWith({ filter: 'ALL', page: 0, size: 10 })

    await user.click(screen.getByRole('tab', { name: /Reported/ }))
    await waitFor(() => expect(api.getQueue).toHaveBeenLastCalledWith({ filter: 'REPORTED', page: 0, size: 10 }))
  })

  it('shows "All caught up!" when nothing is waiting', async () => {
    renderAt(BASE, fakeApi({ getQueue: vi.fn().mockResolvedValue({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }) }))
    expect(await screen.findByText('All caught up!')).toBeTruthy()
  })
})

describe('S15/S16 Review moderation detail', () => {
  it('highlights the contact number and publishes a held review with its revision', async () => {
    const api = fakeApi()
    const user = userEvent.setup()
    const router = renderAt(`${BASE}/r1`, api)

    const mark = await screen.findByText('077 123 4567')
    expect(mark.tagName).toBe('MARK')

    await user.click(screen.getByRole('button', { name: 'Publish' }))
    const dialog = await screen.findByRole('dialog', { name: /Publish this review/ })
    await user.type(within(dialog).getByLabelText(/Details/), 'Office number')
    await user.click(within(dialog).getByRole('button', { name: 'Publish review' }))

    await waitFor(() => expect(api.publishReview).toHaveBeenCalledWith('r1', { note: 'Office number', revision: 3 }))
    await waitFor(() => expect(router.state.location.pathname).toBe(BASE))
  })

  it('requires a reason before removing, then sends it with the revision', async () => {
    const api = fakeApi()
    const user = userEvent.setup()
    renderAt(`${BASE}/r2`, api)

    expect(await screen.findByText('Offensive')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Remove' }))
    const dialog = await screen.findByRole('dialog', { name: /Remove this review/ })
    await user.click(within(dialog).getByRole('button', { name: 'Remove review' }))
    expect(within(dialog).getByText('Please choose a reason')).toBeTruthy()
    expect(api.removeReview).not.toHaveBeenCalled()

    await user.selectOptions(within(dialog).getByLabelText(/Reason/), 'OFFENSIVE')
    await user.click(within(dialog).getByRole('button', { name: 'Remove review' }))
    await waitFor(() => expect(api.removeReview).toHaveBeenCalledWith('r2', { reasonCode: 'OFFENSIVE', note: '', revision: 5 }))
  })

  it('offers "Keep review" for a reported published review', async () => {
    renderAt(`${BASE}/r2`, fakeApi())
    expect(await screen.findByRole('button', { name: 'Keep review' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Publish' })).toBeNull()
  })

  it('reloads the review and explains when another moderator decided first (409)', async () => {
    const conflict = Object.assign(new Error('conflict'), {
      response: { status: 409, data: { error: 'REVIEW_UPDATE_CONFLICT', message: 'changed' } },
    })
    const api = fakeApi({ publishReview: vi.fn().mockRejectedValue(conflict) })
    const user = userEvent.setup()
    renderAt(`${BASE}/r1`, api)

    await user.click(await screen.findByRole('button', { name: 'Publish' }))
    const dialog = await screen.findByRole('dialog', { name: /Publish this review/ })
    await user.click(within(dialog).getByRole('button', { name: 'Publish review' }))

    expect(await screen.findByText(/changed by another moderator/)).toBeTruthy()
    await waitFor(() => expect(api.getReview).toHaveBeenCalledTimes(2))
  })

  it('Super Admin (view only) sees the review without decision buttons', async () => {
    renderAt(`${BASE}/r1`, fakeApi(), { canEdit: false })
    expect(await screen.findByText('077 123 4567')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Publish' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull()
    expect(screen.getByText(/View only/)).toBeTruthy()
  })
})
