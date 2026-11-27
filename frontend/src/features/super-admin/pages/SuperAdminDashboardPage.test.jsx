// @vitest-environment jsdom

import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import SuperAdminDashboardPage from './SuperAdminDashboardPage'
import { getAnalyticsSummary } from '../services/superAdminService'
import { analyticsSummary, renderSuperAdminPage } from '../testing/renderSuperAdmin'

vi.mock('../services/superAdminService', () => ({
  getAnalyticsSummary: vi.fn(),
  isRequestCancelled: () => false,
}))

beforeEach(() => {
  getAnalyticsSummary.mockReset().mockResolvedValue(analyticsSummary())
})

afterEach(() => vi.restoreAllMocks())

describe('SuperAdmin Dashboard', () => {
  it('renders dashboard title and real metric values, no fake numbers', async () => {
    renderSuperAdminPage(SuperAdminDashboardPage)
    expect(await screen.findByRole('heading', { name: /Dashboard/ })).toBeTruthy()
    // Real values for users, providers, bookings, money, disputes
    expect(await screen.findByText('14,280')).toBeTruthy() // users
    expect(screen.getByText('1,842')).toBeTruthy() // providers
    expect(screen.getByText('628')).toBeTruthy() // bookings
    expect(screen.getByText('LKR 18.4M')).toBeTruthy() // booking value
    expect(screen.getByText('LKR 2.7M')).toBeTruthy() // commission
    expect(screen.getByText('LKR 141K')).toBeTruthy() // refunds
    expect(screen.getByText('19')).toBeTruthy() // open disputes
    expect(screen.getByText('5')).toBeTruthy() // admin accounts
    expect(screen.getByText('47')).toBeTruthy() // pending verifications
  })

  it('shows unavailable placeholders when dependency is not available', async () => {
    getAnalyticsSummary.mockResolvedValue(
      analyticsSummary({
        users: { available: false, reason: 'WP01 User Directory offline' },
        bookings: { available: false, reason: 'WP05 Booking System offline' },
      }),
    )
    renderSuperAdminPage(SuperAdminDashboardPage)
    expect(await screen.findByText(/WP01 User Directory offline/)).toBeTruthy()
    expect(screen.getByText(/WP05 Booking System offline/)).toBeTruthy()
  })

  it('renders Day, Week, Month time range toggle', async () => {
    renderSuperAdminPage(SuperAdminDashboardPage)
    expect(await screen.findByRole('button', { name: 'Day' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Week' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Month' })).toBeTruthy()
  })

  it('shows chart placeholders instead of line/area/donut charts', async () => {
    renderSuperAdminPage(SuperAdminDashboardPage)
    await screen.findByText('19')
    expect(screen.getAllByText(/needs WP05 booking module/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/needs WP07 payment module/).length).toBeGreaterThan(0)
  })

  it('renders dispute breakdown with real counts', async () => {
    renderSuperAdminPage(SuperAdminDashboardPage)
    await screen.findByText('19')
    expect(screen.getByText('8')).toBeTruthy() // underReview
    expect(screen.getByText('6')).toBeTruthy() // awaitingResponse
    expect(screen.getByText('3')).toBeTruthy() // appealed
    expect(screen.getByText('2')).toBeTruthy() // manualReview
  })

  it('renders audit activity entries', async () => {
    renderSuperAdminPage(SuperAdminDashboardPage)
    await screen.findByText('Eleanor Vance')
    expect(screen.getByText(/platform settings updated/i)).toBeTruthy()
  })

  it('renders quick links with correct navigation', async () => {
    renderSuperAdminPage(SuperAdminDashboardPage)
    await screen.findByText('19')
    const links = screen.getAllByRole('link')
    const settingsLink = links.find((l) => l.textContent.includes('Platform settings'))
    expect(settingsLink).toBeTruthy()
  })

  it('handles API error gracefully', async () => {
    getAnalyticsSummary.mockRejectedValue(new Error('Server error'))
    renderSuperAdminPage(SuperAdminDashboardPage)
    expect(await screen.findByText(/could not be loaded/i)).toBeTruthy()
  })
})
