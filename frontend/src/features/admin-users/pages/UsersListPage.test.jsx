// @vitest-environment jsdom

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { exportUsersCsv, getRegistrations, getUsersSummary, listUsers } from '../services/adminUsersService'
import { apiError, BASE, notConnected, page, renderUsers, row, summary } from '../testing/renderUsers'

vi.mock('../services/adminUsersService', () => ({
  listUsers: vi.fn(),
  getUsersSummary: vi.fn(),
  getRegistrations: vi.fn(),
  exportUsersCsv: vi.fn(),
  getUser: vi.fn(),
  isRequestCancelled: () => false,
}))

const lastListCall = () => listUsers.mock.calls.at(-1)[0]

beforeEach(() => {
  listUsers.mockReset().mockResolvedValue(page([row()]))
  getUsersSummary.mockReset().mockResolvedValue(summary())
  getRegistrations.mockReset().mockResolvedValue({ period: 'WEEKS', ...notConnected() })
  exportUsersCsv.mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Users list', () => {
  it('shows the title, the three summary cards with real numbers and no made-up lines', async () => {
    renderUsers(BASE)

    expect(screen.getByRole('heading', { name: 'Users' })).toBeTruthy()
    expect(screen.getByText('Manage customers, providers, and account status across the marketplace')).toBeTruthy()
    expect(await screen.findByText('1,240')).toBeTruthy()
    expect(screen.getByText('310')).toBeTruthy()
    expect(screen.getByText('9')).toBeTruthy()
    expect(screen.queryByText(/this week/)).toBeNull()
    expect(screen.queryByText(/healthy acquisition|capacity|flagged in 24h|Requires review/)).toBeNull()
  })

  it('weekly line only when the API returns it', async () => {
    getUsersSummary.mockResolvedValue(summary({ weekly: true }))
    renderUsers(BASE)

    expect((await screen.findAllByText(/\+42 this week/)).length).toBe(3)
  })

  it('unavailable cards show a dash and "Not available yet", never a number', async () => {
    getUsersSummary.mockResolvedValue(summary({ available: false }))
    renderUsers(BASE)

    expect((await screen.findAllByText('Not available yet')).length).toBe(3)
    expect(screen.queryByText('1,240')).toBeNull()
  })

  it('registrations not connected: empty state; the Months toggle asks for MONTHS', async () => {
    renderUsers(BASE)

    expect(await screen.findByText('Registration data will appear when the accounts service is connected.')).toBeTruthy()
    expect(getRegistrations).toHaveBeenCalledWith('WEEKS', expect.anything())
    fireEvent.click(screen.getByRole('button', { name: 'Months' }))
    await waitFor(() => expect(getRegistrations).toHaveBeenCalledWith('MONTHS', expect.anything()))
    expect(screen.getByRole('button', { name: 'Months' }).getAttribute('aria-pressed')).toBe('true')
  })

  it('registrations available: accessible bar chart with a summary label, legend and a hidden data table', async () => {
    getRegistrations.mockResolvedValue({
      period: 'WEEKS',
      available: true,
      buckets: [
        { start: '2026-11-09T00:00:00Z', end: '2026-11-16T00:00:00Z', customers: 12, providers: 3 },
        { start: '2026-11-16T00:00:00Z', end: '2026-11-23T00:00:00Z', customers: 20, providers: 5 },
      ],
    })
    renderUsers(BASE)

    const chart = await screen.findByRole('group', { name: /32 customers and 8 providers in total/ })
    expect(within(chart).getByRole('button', { name: 'Week of Nov 16: Customers 20, Providers 5' })).toBeTruthy()
    const table = within(chart).getByRole('table')
    expect(within(table).getAllByRole('row').length).toBe(3)
    fireEvent.mouseEnter(within(chart).getByRole('button', { name: /Week of Nov 9/ }))
    expect(within(chart).getByText('Customers: 12')).toBeTruthy()
  })

  it('table: avatar name, short id, role, email, joined date, status chip and View; PRO only when the API says so', async () => {
    listUsers.mockResolvedValue(page([
      row(),
      row({ id: 'u2', displayName: 'Aruni Jay', pro: true, status: 'SUSPENDED' }),
      row({ id: 'u3', displayName: 'Tharindu M', role: 'CUSTOMER', status: 'PENDING_VERIFICATION' }),
    ]))
    renderUsers(BASE)

    expect(await screen.findByRole('link', { name: 'Sarah Smith' })).toBeTruthy()
    const table = within(screen.getByRole('table'))
    expect(table.getByText('ID: #PRO-0002')).toBeTruthy()
    expect(table.getAllByText('PRO').length).toBe(1)
    expect(table.getByText('Suspended')).toBeTruthy()
    expect(table.getByText('Pending verification')).toBeTruthy()
    expect(table.getAllByText('Active').length).toBe(1)
    expect(table.getAllByText('sarah.smith@example.test').length).toBe(3)
    expect(screen.getByRole('link', { name: 'View Sarah Smith' }).getAttribute('href')).toBe(`${BASE}/65f1c0aa11bb22cc33a10002`)
  })

  it('search is debounced (~300 ms), goes back to page 1 and is kept in the URL', async () => {
    const router = renderUsers(`${BASE}?page=2`)
    await screen.findByRole('link', { name: 'Sarah Smith' })
    const calls = listUsers.mock.calls.length

    fireEvent.change(screen.getByRole('searchbox', { name: /Search by name, email, or user ID/ }), { target: { value: 'sarah' } })
    expect(listUsers.mock.calls.length).toBe(calls)

    await waitFor(() => expect(lastListCall()).toMatchObject({ q: 'sarah', page: 0 }), { timeout: 2000 })
    expect(router.state.location.search).toBe('?q=sarah')
  })

  it('role and status filters are sent to the API and written to the URL', async () => {
    const router = renderUsers(BASE)
    await screen.findByRole('link', { name: 'Sarah Smith' })

    fireEvent.change(screen.getByRole('combobox', { name: 'Role' }), { target: { value: 'PROVIDER' } })
    await waitFor(() => expect(lastListCall()).toMatchObject({ role: 'PROVIDER' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'Status' }), { target: { value: 'SUSPENDED' } })
    await waitFor(() => expect(lastListCall()).toMatchObject({ role: 'PROVIDER', status: 'SUSPENDED', page: 0 }))
    expect(router.state.location.search).toBe('?role=PROVIDER&status=SUSPENDED')
  })

  it('reads filters and page from the URL (Back keeps them)', async () => {
    renderUsers(`${BASE}?q=nimal&role=CUSTOMER&status=ACTIVE&page=3`)

    await waitFor(() => expect(lastListCall()).toEqual({ q: 'nimal', role: 'CUSTOMER', status: 'ACTIVE', page: 2, size: 8 }))
    expect(screen.getByRole('searchbox').value).toBe('nimal')
    expect(screen.getByRole('combobox', { name: 'Role' }).value).toBe('CUSTOMER')
  })

  it('pagination: "Showing 1 to 8 of 20 users" and page 2 asks for page index 1', async () => {
    listUsers.mockResolvedValue(page(Array.from({ length: 8 }, (_, i) => row({ id: `u${i}`, displayName: `User ${i}` })), 20))
    const router = renderUsers(BASE)

    expect(await screen.findByText('Showing 1 to 8 of 20 users')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /page 2/i }))
    await waitFor(() => expect(lastListCall()).toMatchObject({ page: 1 }))
    expect(router.state.location.search).toBe('?page=2')
  })

  it('Export CSV downloads with the current filters and the file name from the service', async () => {
    exportUsersCsv.mockResolvedValue({ blob: new Blob(['id\n']), filename: 'users-2026-11-19.csv' })
    const createObjectURL = vi.fn(() => 'blob:x')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    renderUsers(`${BASE}?q=perera&role=PROVIDER`)
    await screen.findByRole('link', { name: 'Sarah Smith' })

    fireEvent.click(screen.getByRole('button', { name: /Export CSV/ }))

    await waitFor(() => expect(exportUsersCsv).toHaveBeenCalledWith({ q: 'perera', role: 'PROVIDER', status: undefined }))
    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(createObjectURL).toHaveBeenCalled()
    expect(await screen.findByText('Exported users-2026-11-19.csv.')).toBeTruthy()
  })

  it('empty search result: "No users match" with Clear filters', async () => {
    listUsers.mockResolvedValue(page([], 0))
    const router = renderUsers(`${BASE}?q=zzz`)

    expect(await screen.findByText('No users match')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(router.state.location.search).toBe(''))
    expect(screen.getByRole('searchbox').value).toBe('')
  })

  it('error state with retry', async () => {
    listUsers.mockRejectedValueOnce(apiError(500, 'INTERNAL_SERVER_ERROR', 'Boom'))
    renderUsers(BASE)

    expect(await screen.findByText('Users could not be loaded')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /retry|try again/i }))
    expect(await screen.findByRole('link', { name: 'Sarah Smith' })).toBeTruthy()
  })
})
