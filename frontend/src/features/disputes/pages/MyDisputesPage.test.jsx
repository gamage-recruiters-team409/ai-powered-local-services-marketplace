// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../services/disputesApi', () => ({
  fetchMyDisputes: vi.fn(),
  isRequestCancelled: () => false,
}))

import { fetchMyDisputes } from '../services/disputesApi'
import { MyDisputesPage } from './MyDisputesPage'

function row(overrides = {}) {
  return {
    id: '6a0f00c0ffee00a000001045',
    bookingId: '6a0f00c0ffee00b000048195',
    customerId: 'c-1',
    providerId: 'p-1',
    raisedByRole: 'CUSTOMER',
    category: 'NO_SHOW',
    status: 'AWAITING_RESPONSE',
    responseDueAt: '2026-11-25T10:00:00Z',
    appealDeadline: null,
    createdAt: '2026-11-22T10:00:00Z',
    updatedAt: '2026-11-22T10:00:00Z',
    ...overrides,
  }
}

function pageOf(content, totalPages = 1) {
  return { content, page: 0, size: 10, totalElements: content.length, totalPages }
}

function renderList(role, path) {
  const base = role === 'PROVIDER' ? '/provider/disputes' : '/customer/disputes'
  const router = createMemoryRouter([
    { path: base, element: <MyDisputesPage role={role} /> },
    { path: `${base}/:id`, element: <p>Detail page</p> },
    { path: `${base}/new`, element: <p>Raise page</p> },
  ], { initialEntries: [path ?? base] })
  render(<RouterProvider router={router} />)
  return router
}

beforeEach(() => {
  fetchMyDisputes.mockReset()
})

afterEach(() => {
  cleanup()
})

describe('My Disputes list', () => {
  it('shows case, booking, category, raised by, status chip, raised date, deadline and View', async () => {
    fetchMyDisputes.mockResolvedValue(pageOf([row()]))
    renderList('PROVIDER')

    const table = await screen.findByRole('table')
    const cells = within(table).getAllByRole('row')[1]
    expect(within(cells).getByText('D-001045')).toBeTruthy()
    expect(within(cells).getByText('B-048195')).toBeTruthy()
    expect(within(cells).getByText('No-show')).toBeTruthy()
    expect(within(cells).getByText('Customer')).toBeTruthy()
    expect(within(cells).getByText('Awaiting your response')).toBeTruthy()
    expect(within(cells).getByText(/Response due:/)).toBeTruthy()
    expect(within(cells).getByRole('link', { name: 'View dispute D-001045' }).getAttribute('href'))
      .toBe('/provider/disputes/6a0f00c0ffee00a000001045')
    expect(fetchMyDisputes).toHaveBeenCalledWith({ statuses: [], page: 0, size: 10 }, expect.anything())
  })

  it('"You" when the viewer raised it; the appeal deadline shows while RESOLVED', async () => {
    fetchMyDisputes.mockResolvedValue(pageOf([row({ status: 'RESOLVED', appealDeadline: '2026-11-24T10:00:00Z' })]))
    renderList('CUSTOMER')

    const table = await screen.findByRole('table')
    expect(within(table).getByText('You')).toBeTruthy()
    expect(within(table).getByText(/Appeal until:/)).toBeTruthy()
    expect(within(table).getByText('Resolved')).toBeTruthy()
  })

  it('status tabs filter through the API and live in the URL', async () => {
    fetchMyDisputes.mockResolvedValue(pageOf([row()]))
    const router = renderList('CUSTOMER')
    await screen.findByRole('table')

    fireEvent.click(screen.getByRole('tab', { name: 'Under review' }))

    expect(router.state.location.search).toBe('?tab=review')
    expect(screen.getByRole('tab', { name: 'Under review' }).getAttribute('aria-selected')).toBe('true')
    await screen.findByRole('table')
    expect(fetchMyDisputes).toHaveBeenLastCalledWith(
      { statuses: ['UNDER_REVIEW', 'APPEALED'], page: 0, size: 10 }, expect.anything())

    fireEvent.click(screen.getByRole('tab', { name: 'Final' }))
    await screen.findByRole('table')
    expect(fetchMyDisputes).toHaveBeenLastCalledWith(
      { statuses: ['RESOLVED_FINAL', 'CLOSED'], page: 0, size: 10 }, expect.anything())
  })

  it('pagination asks for the next page and keeps the tab', async () => {
    fetchMyDisputes.mockResolvedValue(pageOf([row()], 3))
    const router = renderList('CUSTOMER', '/customer/disputes?tab=open')
    await screen.findByRole('table')

    fireEvent.click(screen.getByRole('button', { name: /page 2/i }))

    expect(router.state.location.search).toBe('?tab=open&page=2')
    await screen.findByRole('table')
    expect(fetchMyDisputes).toHaveBeenLastCalledWith(
      { statuses: ['OPEN', 'AWAITING_RESPONSE', 'UNDER_REVIEW', 'APPEALED'], page: 1, size: 10 }, expect.anything())
  })

  it('empty: "You have no disputes"', async () => {
    fetchMyDisputes.mockResolvedValue(pageOf([]))
    renderList('CUSTOMER')

    expect(await screen.findByText('You have no disputes')).toBeTruthy()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('error state with retry', async () => {
    fetchMyDisputes.mockRejectedValueOnce(Object.assign(new Error('boom'), { response: { status: 500, data: {} } }))
      .mockResolvedValueOnce(pageOf([row()]))
    renderList('CUSTOMER')

    fireEvent.click(await screen.findByRole('button', { name: /try again/i }))

    expect(await screen.findByRole('table')).toBeTruthy()
  })

  it('provider list has "Raise a dispute"; the customer list does not', async () => {
    fetchMyDisputes.mockResolvedValue(pageOf([]))
    renderList('PROVIDER')
    expect(await screen.findByRole('link', { name: 'Raise a dispute' })).toBeTruthy()
    cleanup()

    renderList('CUSTOMER')
    await screen.findByText('You have no disputes')
    expect(screen.queryByRole('link', { name: 'Raise a dispute' })).toBeNull()
  })
})
