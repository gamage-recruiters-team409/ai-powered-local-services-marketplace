// @vitest-environment jsdom

import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../services/adminDisputesService', () => ({
  listAdminDisputes: vi.fn(),
  getAdminDisputeSummary: vi.fn(),
  getAdminDispute: vi.fn(),
  isRequestCancelled: () => false,
}))

import { getAdminDisputeSummary, listAdminDisputes } from '../services/adminDisputesService'
import { BASE, page, renderDisputes, row, SUMMARY } from '../testing/renderAdminDisputes'

beforeEach(() => {
  listAdminDisputes.mockReset()
  getAdminDisputeSummary.mockReset()
  getAdminDisputeSummary.mockResolvedValue(SUMMARY)
  listAdminDisputes.mockResolvedValue(page([row()]))
})

afterEach(() => {
  cleanup()
})

const lastFilters = () => listAdminDisputes.mock.calls.at(-1)[0]

describe('Admin disputes list', () => {
  it('summary cards include the manual review count', async () => {
    renderDisputes(BASE)

    expect(await screen.findByTestId('summary-manualReview')).toBeTruthy()
    await waitFor(() => expect(screen.getByTestId('summary-manualReview').textContent).toBe('2'))
    expect(screen.getByTestId('summary-underReview').textContent).toBe('4')
    expect(screen.getByTestId('summary-resolvedLast30Days').textContent).toBe('9')
  })

  it('table: case, booking, category, raised by, status chip, deadline, raised, settlement and View link', async () => {
    listAdminDisputes.mockResolvedValue(page([
      row({ status: 'AWAITING_RESPONSE' }),
      row({ id: '6a0f00c0ffee00a000002222', status: 'RESOLVED_FINAL', settlementState: 'MANUAL_REVIEW' }),
    ]))
    renderDisputes(BASE)

    const table = await screen.findByRole('table')
    const [, first, second] = within(table).getAllByRole('row')
    expect(within(first).getByText('D-001045')).toBeTruthy()
    expect(within(first).getByText('B-048195')).toBeTruthy()
    expect(within(first).getByText('No-show')).toBeTruthy()
    expect(within(first).getByText('Customer')).toBeTruthy()
    expect(within(first).getByText('Awaiting response')).toBeTruthy()
    expect(within(second).getByText('Manual review')).toBeTruthy()
    expect(within(first).getByRole('link', { name: 'Open case D-001045' }).getAttribute('href'))
      .toBe(`${BASE}/6a0f00c0ffee00a000001045`)
  })

  it('reads its filters from the URL', async () => {
    renderDisputes(`${BASE}?status=APPEALED&category=NON_PAYMENT&from=2026-11-01&to=2026-11-30&sort=createdAt,asc&page=2`)

    await screen.findByRole('table')
    expect(lastFilters()).toEqual({ status: 'APPEALED', category: 'NON_PAYMENT', q: '', from: '2026-11-01',
      to: '2026-11-30', sort: 'createdAt,asc', page: 1, size: 10 })
    expect(screen.getByLabelText('Status').value).toBe('APPEALED')
  })

  it('changing a filter updates the URL (back to page 1) and the request; unknown URL values are ignored', async () => {
    const router = renderDisputes(`${BASE}?status=BOGUS&page=3`)
    await screen.findByRole('table')
    expect(lastFilters().status).toBe('')

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'UNDER_REVIEW' } })

    await waitFor(() => expect(lastFilters().status).toBe('UNDER_REVIEW'))
    expect(router.state.location.search).toBe('?status=UNDER_REVIEW')
    expect(lastFilters().page).toBe(0)

    fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'responseDueAt,asc' } })
    await waitFor(() => expect(lastFilters().sort).toBe('responseDueAt,asc'))
    expect(router.state.location.search).toContain('sort=responseDueAt%2Casc')
  })

  it('search is sent after a short pause and kept in the URL', async () => {
    const router = renderDisputes(BASE)
    await screen.findByRole('table')

    fireEvent.change(screen.getByLabelText('Search'), { target: { value: '6a0f00' } })

    await waitFor(() => expect(lastFilters().q).toBe('6a0f00'))
    expect(router.state.location.search).toBe('?q=6a0f00')
  })

  it('pagination goes through the URL', async () => {
    listAdminDisputes.mockResolvedValue(page([row()], 4))
    const router = renderDisputes(BASE)
    await screen.findByRole('table')

    fireEvent.click(screen.getByRole('button', { name: 'Go to page 3' }))

    await waitFor(() => expect(lastFilters().page).toBe(2))
    expect(router.state.location.search).toBe('?page=3')
  })

  it('empty with filters -> "No disputes match these filters" and Clear filters resets the URL', async () => {
    listAdminDisputes.mockResolvedValue(page([]))
    const router = renderDisputes(`${BASE}?category=OTHER`)

    expect(await screen.findByText('No disputes match these filters')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(router.state.location.search).toBe(''))
  })

  it('error -> message and Try again', async () => {
    listAdminDisputes.mockRejectedValueOnce(Object.assign(new Error('x'),
      { response: { status: 400, data: { error: 'INVALID_SEARCH_TEXT' } } }))
    renderDisputes(BASE)

    expect(await screen.findByText(/Search by a case, booking or user id/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('table')).toBeTruthy()
  })
})
