// @vitest-environment jsdom

import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import SupportDashboardPage from './SupportDashboardPage'
import { getDashboard } from '../services/operationsService'
import { dashboardResponse, renderOpsPage } from '../testing/renderOps'

vi.mock('../services/operationsService', () => ({
  getDashboard: vi.fn(),
  isRequestCancelled: () => false,
}))

beforeEach(() => {
  getDashboard.mockReset().mockResolvedValue(dashboardResponse())
})

afterEach(() => vi.restoreAllMocks())

describe('Support dashboard', () => {
  it('renders the View-only dashboard with real counts and no fake numbers', async () => {
    renderOpsPage(SupportDashboardPage, { area: 'SUPPORT_ADMIN' })
    expect(await screen.findByRole('heading', { name: /Dashboard/ })).toBeTruthy()
    expect(screen.getByText('View only')).toBeTruthy()
    expect(await screen.findByText('7')).toBeTruthy() // overdue
    expect(screen.getByText('6')).toBeTruthy() // flagged users
    expect(screen.queryByText(/since morning|vs yesterday|SLA breach|Event Stream|Automated refresh/)).toBeNull()
  })

  it('shows a placeholder for the unavailable late-cases parts, no numbers', async () => {
    renderOpsPage(SupportDashboardPage)
    expect(await screen.findByText('7')).toBeTruthy()
    expect(screen.getAllByText(/Late-start tracking is not available yet/).length).toBeGreaterThan(0)
  })

  it('renders the overdue distribution and late-cases charts as bars (no svg line chart)', async () => {
    renderOpsPage(SupportDashboardPage)
    await screen.findByText('7')
    const dist = document.querySelector('[aria-labelledby="dash-dist"]')
    expect(dist.querySelectorAll('svg').length).toBe(0)
  })

  it('shows the recent overdue bookings table and a View all link', async () => {
    renderOpsPage(SupportDashboardPage, { area: 'SUPPORT_ADMIN' })
    expect(await screen.findByText('#BK-9102')).toBeTruthy()
    const viewAll = screen.getByRole('link', { name: 'View all' })
    expect(viewAll.getAttribute('href')).toContain('/admin/support/overdue-bookings')
  })
})
