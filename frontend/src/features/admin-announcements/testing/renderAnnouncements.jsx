import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'

import { AreaAccessContext } from '../../disputes-admin/access/accessContexts'
import AnnouncementsSection from '../pages/AnnouncementsSection'

export const BASE = '/admin/admin/announcements'

/**
 * Renders the announcements section at `path` in a data router (needed for the unsaved-changes blocker), with the
 * ADMIN area's access: canEdit = true for ADMIN, false for SUPER_ADMIN (view only).
 */
export function renderAnnouncements(path, { canEdit = true } = {}) {
  const access = { area: 'ADMIN', mode: canEdit ? 'edit' : 'view', canEdit, isViewOnly: !canEdit }
  const router = createMemoryRouter(
    [
      {
        path: `${BASE}/*`,
        element: (
          <AreaAccessContext.Provider value={access}>
            <AnnouncementsSection />
          </AreaAccessContext.Provider>
        ),
      },
    ],
    { initialEntries: [path] },
  )
  render(<RouterProvider router={router} />)
  return router
}

export function announcement(overrides = {}) {
  return {
    id: '65f1c0aa11bb22cc33a1b2c3',
    version: 4,
    title: 'Easter Weekend Service Hours',
    message: 'Support hours change over **Easter**.',
    audience: 'ALL_USERS',
    status: 'PUBLISHED',
    scheduledAt: null,
    publishedAt: '2026-03-28T03:30:00Z',
    sendPush: true,
    pinToHeader: true,
    pinExpiresAt: '2026-03-30T03:30:00Z',
    pinActive: false,
    deliveryState: 'NOT_APPLIED',
    createdBy: 'admin-1',
    updatedBy: 'admin-1',
    createdAt: '2026-03-27T10:00:00Z',
    updatedAt: '2026-03-28T03:30:00Z',
    ...overrides,
  }
}

export function page(content, totalElements = content.length, number = 0) {
  return { content, page: number, size: 5, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / 5)) }
}

export const SUMMARY = Object.freeze({
  active: 1,
  scheduled: 1,
  total: 3,
  tabs: { all: 3, published: 1, scheduled: 1, drafts: 1 },
})

export function apiError(status, error, fieldErrors = []) {
  return Object.assign(new Error(error), { response: { status, data: { status, error, message: error, fieldErrors } } })
}
