// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ToastProvider } from '../../../shared/components/feedback'
import { listingRoutes } from '../routes/listingRoutes'
import { listingHttp, resetLocalListingsDemoStore } from '../services/listingService'
import { CreateEditListingWizardPage } from './CreateEditListingWizardPage'
import { ProviderListingsDashboardPage } from './ProviderListingsDashboardPage'

function renderWithRouter(initialEntries = ['/provider/listings']) {
  return render(
    <ToastProvider>
      <MemoryRouter initialEntries={initialEntries}>
        <Routes>
          <Route path="/provider/listings" element={<ProviderListingsDashboardPage />} />
          <Route path="/provider/listings/new" element={<CreateEditListingWizardPage />} />
          <Route path="/provider/listings/:listingId/edit" element={<CreateEditListingWizardPage />} />
        </Routes>
      </MemoryRouter>
    </ToastProvider>,
  )
}

describe('WP03 Provider Listings Dashboard & 4-Step Wizard UI', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    window.sessionStorage.clear()
    resetLocalListingsDemoStore()

    const notFoundErr = new Error('Not found')
    notFoundErr.response = { status: 404, data: { message: 'Not found' } }
    vi.spyOn(listingHttp, 'get').mockRejectedValue(notFoundErr)
    vi.spyOn(listingHttp, 'post').mockRejectedValue(notFoundErr)
    vi.spyOn(listingHttp, 'put').mockRejectedValue(notFoundErr)
    vi.spyOn(listingHttp, 'patch').mockRejectedValue(notFoundErr)
  })

  afterEach(() => {
    cleanup()
  })

  it('renders KPI metrics, category-inactive policy callout, Grid/List switch, and Pause/Archive modals', async () => {
    renderWithRouter(['/provider/listings'])

    expect(await screen.findByText('My Service Listings')).toBeTruthy()
    expect(await screen.findByText('Emergency Pipe Leak Repair & Waterline Diagnostic')).toBeTruthy()
    expect(screen.getByText(/Public bookings temporarily disabled by admin policy/i)).toBeTruthy()

    // Switch to List View
    fireEvent.click(screen.getByRole('button', { name: /☰ List/i }))
    expect(await screen.findByText('PRICE & BILLING MODEL')).toBeTruthy()

    // Switch back to Cards and open Pause modal
    fireEvent.click(screen.getByRole('button', { name: /⊞ Cards/i }))
    const pauseButtons = await screen.findAllByRole('button', { name: /^Pause$/i })
    const enabledPauseBtn = pauseButtons.find((btn) => !btn.disabled)
    expect(enabledPauseBtn).toBeTruthy()
    fireEvent.click(enabledPauseBtn)

    expect(await screen.findByText('Pause Service Listing?')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Pause Service' }))
    await waitFor(() => expect(screen.queryByText('Pause Service Listing?')).toBeNull())

    // Open Archive modal and confirm
    const archiveButtons = await screen.findAllByRole('button', { name: /^Archive$/i })
    fireEvent.click(archiveButtons[0])
    expect(await screen.findByText(/Service Action Confirmation/i)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Archive Listing' }))
    await waitFor(() => expect(screen.queryByText(/Service Action Confirmation/i)).toBeNull())

    // Empty state on unmatched search + reset filter
    const searchBox = screen.getByLabelText('Search services')
    fireEvent.change(searchBox, { target: { value: 'zzzz-unmatched-query' } })
    expect(await screen.findByText('No service listings found')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Reset All Filters/i }))
    expect(await screen.findByText('Inverter Split AC Deep Chemical Wash & Coil Flush')).toBeTruthy()
  })

  it('walks through the 4-Step Wizard with FIXED/HOURLY pricing only, policy warning, AI suggest, and publishes', async () => {
    renderWithRouter(['/provider/listings/new'])

    expect(await screen.findByText('Create Service Listing')).toBeTruthy()

    // Verify Leaflet.js map updates reactively on District & Coverage Radius changes
    const mapBox = screen.getByTestId('leaflet-coverage-map')
    expect(mapBox.getAttribute('data-radius-km')).toBe('15')
    fireEvent.change(screen.getByLabelText(/Base City \/ District/i), {
      target: { value: 'Kandy' },
    })
    fireEvent.change(screen.getByLabelText(/Coverage Radius/i), {
      target: { value: '25' },
    })
    expect(screen.getByTestId('leaflet-coverage-map').getAttribute('data-lat')).toBe('7.2906')
    expect(screen.getByTestId('leaflet-coverage-map').getAttribute('data-radius-km')).toBe('25')

    // Enter title & test anti-circumvention Policy Warning banner
    fireEvent.change(screen.getByLabelText(/Service Title/i), {
      target: { value: 'Solar Water Heater Annual Service & Pipe Seal' },
    })
    fireEvent.change(screen.getByLabelText(/Detailed Service Scope/i), {
      target: { value: 'Call 0771234567 for fast solar heater gasket replacement.' },
    })
    expect(await screen.findByText(/Platform Policy:/i)).toBeTruthy()

    // Replace description cleanly using AI-003 Suggestion button
    fireEvent.click(screen.getByRole('button', { name: /AI Suggest Description/i }))
    expect(screen.queryByText(/Platform Policy:/i)).toBeNull()

    // Advance to Step 2
    fireEvent.click(screen.getByRole('button', { name: /Continue To Pricing & Add-Ons/i }))
    expect(await screen.findByText('Pricing Model & Base Rates')).toBeTruthy()

    // Verify strictly FIXED & HOURLY only (Custom Quote must not exist)
    expect(screen.getByRole('button', { name: /Fixed Price/i })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Hourly Rate/i })).toBeTruthy()
    expect(screen.queryByText(/Custom Quote/i)).toBeNull()

    // Switch to HOURLY and add an add-on
    fireEvent.click(screen.getByRole('button', { name: /Hourly Rate/i }))
    fireEvent.click(screen.getByRole('button', { name: /Add Another Extra/i }))
    fireEvent.change(screen.getByLabelText(/Add-On Title/i), {
      target: { value: 'Roof Collector Valve Replacement' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save Add-On' }))
    expect(await screen.findByText('Roof Collector Valve Replacement')).toBeTruthy()

    // Advance to Step 3: Portfolio Media
    fireEvent.click(screen.getByRole('button', { name: /Continue To Portfolio Media/i }))
    expect(await screen.findByRole('heading', { name: 'Media Dropzone' })).toBeTruthy()
    fireEvent.change(screen.getByLabelText('HTTPS image URL'), {
      target: { value: 'https://cdn.localserve.lk/listings/solar-2.jpg' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add URL' }))
    expect(await screen.findByText(/2 \/ 8/)).toBeTruthy()

    // Advance to Step 4: Review & Publish
    fireEvent.click(screen.getByRole('button', { name: /Continue To Review & Publish/i }))
    expect(await screen.findByText('Basic Details & Geography')).toBeTruthy()
    expect(screen.getByText('Solar Water Heater Annual Service & Pipe Seal')).toBeTruthy()
    expect(screen.getByText('Hourly Rate (LKR)')).toBeTruthy()

    // Publish listing -> returns to dashboard showing newly published item
    fireEvent.click(screen.getByRole('button', { name: /Publish Service Listing/i }))
    expect(await screen.findByText('My Service Listings')).toBeTruthy()
    expect(await screen.findByText('Solar Water Heater Annual Service & Pipe Seal')).toBeTruthy()
  })

  it('preloads existing listing data in edit mode and exports expected ProviderLayout route paths', async () => {
    renderWithRouter(['/provider/listings/lst-1001/edit'])
    expect(await screen.findByText('Edit Service Listing')).toBeTruthy()
    expect(screen.getByDisplayValue('Emergency Pipe Leak Repair & Waterline Diagnostic')).toBeTruthy()

    const mountedPaths = listingRoutes.map((route) => route.path)
    expect(mountedPaths).toEqual([
      '/provider/listings',
      '/provider/listings/new',
      '/provider/listings/:listingId/edit',
    ])
  })
})
