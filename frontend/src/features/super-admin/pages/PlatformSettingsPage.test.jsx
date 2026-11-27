// @vitest-environment jsdom

import { screen, waitFor, fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import PlatformSettingsPage from './PlatformSettingsPage'
import { getSettings, updateGroupSettings, getGroupHistory } from '../services/superAdminService'
import { settingsResponse, renderSuperAdminPage } from '../testing/renderSuperAdmin'

vi.mock('../services/superAdminService', () => ({
  getSettings: vi.fn(),
  updateGroupSettings: vi.fn(),
  getGroupHistory: vi.fn(),
  isRequestCancelled: () => false,
}))

beforeEach(() => {
  getSettings.mockReset().mockResolvedValue(settingsResponse())
  updateGroupSettings.mockReset().mockResolvedValue({})
  getGroupHistory.mockReset().mockResolvedValue([])
})

afterEach(() => vi.restoreAllMocks())

describe('Platform Settings', () => {
  it('renders settings page with group sidebar and booking fields', async () => {
    renderSuperAdminPage(PlatformSettingsPage)
    expect(await screen.findByRole('heading', { name: /Platform settings/ })).toBeTruthy()
    expect(screen.getByText('Booking')).toBeTruthy()
    expect(screen.getByText('Cancellation')).toBeTruthy()
    expect(screen.getByText('AI thresholds')).toBeTruthy()
    // Booking fields should be visible by default
    await waitFor(() => {
      expect(screen.getByText('Minimum booking notice')).toBeTruthy()
    })
  })

  it('displays Super Admin banner', async () => {
    renderSuperAdminPage(PlatformSettingsPage)
    expect(await screen.findByText(/Only Super Admin can change/)).toBeTruthy()
  })

  it('shows booking field values from API', async () => {
    renderSuperAdminPage(PlatformSettingsPage)
    await waitFor(() => {
      const input = screen.getByLabelText('Minimum booking notice')
      expect(input.value).toBe('3')
    })
  })

  it('switches groups when clicking sidebar items', async () => {
    renderSuperAdminPage(PlatformSettingsPage)
    await screen.findByText('Minimum booking notice')
    fireEvent.click(screen.getByText('Cancellation'))
    await waitFor(() => {
      expect(screen.getByText('Full refund')).toBeTruthy()
    })
  })

  it('renders booking timeline projection visual', async () => {
    renderSuperAdminPage(PlatformSettingsPage)
    await waitFor(() => {
      expect(screen.getByText('BOOKING TIMELINE')).toBeTruthy()
    })
  })

  it('shows AI gradient projection for AI thresholds group', async () => {
    renderSuperAdminPage(PlatformSettingsPage)
    await screen.findByText('Minimum booking notice')
    fireEvent.click(screen.getByText('AI thresholds'))
    await waitFor(() => {
      expect(screen.getByText('AI SCORE SCALE')).toBeTruthy()
    })
  })

  it('enables save button only when values change', async () => {
    renderSuperAdminPage(PlatformSettingsPage)
    await waitFor(() => {
      const saveBtn = screen.getByRole('button', { name: /Save changes/ })
      expect(saveBtn.disabled).toBe(true)
    })
  })

  it('handles API error on load', async () => {
    getSettings.mockRejectedValue(new Error('Server error'))
    renderSuperAdminPage(PlatformSettingsPage)
    expect(await screen.findByText(/could not be loaded/i)).toBeTruthy()
  })

  it('shows version history button', async () => {
    renderSuperAdminPage(PlatformSettingsPage)
    await waitFor(() => {
      expect(screen.getByText(/Version history/)).toBeTruthy()
    })
  })

  it('renders locked provider cancellation refund field as disabled', async () => {
    renderSuperAdminPage(PlatformSettingsPage)
    await screen.findByText('Minimum booking notice')
    fireEvent.click(screen.getByText('Cancellation'))
    await waitFor(() => {
      const input = screen.getByLabelText('Provider cancellation refund')
      expect(input.disabled).toBe(true)
    })
  })

  it('renders projection visuals across policy groups', async () => {
    renderSuperAdminPage(PlatformSettingsPage)
    await screen.findByText('Minimum booking notice')

    // Cancellation: Refund Tiers
    fireEvent.click(screen.getByText('Cancellation'))
    expect(await screen.findByText('REFUND TIERS')).toBeTruthy()
    expect(screen.getByText(/Customer Cancellation Schedule/)).toBeTruthy()

    // Customer: Completion & Review Timeline
    fireEvent.click(screen.getByText('Customer'))
    expect(await screen.findByText('COMPLETION & REVIEW TIMELINE')).toBeTruthy()

    // Dispute: Dispute Lifespan
    fireEvent.click(screen.getByText('Dispute'))
    expect(await screen.findByText('DISPUTE LIFESPAN PROJECTION')).toBeTruthy()

    // Penalty: Penalty Example
    fireEvent.click(screen.getByText('Penalty'))
    expect(await screen.findByText('EXAMPLE')).toBeTruthy()
    expect(screen.getByText(/Net provider payout/)).toBeTruthy()
  })
})
