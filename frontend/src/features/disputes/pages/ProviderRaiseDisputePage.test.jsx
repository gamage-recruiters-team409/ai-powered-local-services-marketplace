// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../services/disputesApi', () => ({
  raiseDispute: vi.fn(),
  uploadEvidence: vi.fn(),
  fetchMyDisputes: vi.fn(),
}))

import { fetchMyDisputes, raiseDispute, uploadEvidence } from '../services/disputesApi'
import { ProviderRaiseDisputePage } from './ProviderRaiseDisputePage'

const BOOKING = '6a0f00c0ffee00b000048195'

function renderRaise(search = `?bookingId=${BOOKING}`) {
  const router = createMemoryRouter([
    { path: '/provider/disputes/new', element: <ProviderRaiseDisputePage /> },
    { path: '/provider/disputes/:id', element: <p>Dispute detail</p> },
    { path: '/provider/disputes', element: <p>My Disputes list</p> },
  ], { initialEntries: [`/provider/disputes/new${search}`] })
  render(<RouterProvider router={router} />)
  return router
}

function apiError(status, error, fieldErrors = []) {
  return Object.assign(new Error(error), { response: { status, data: { error, fieldErrors } } })
}

const description = () => screen.getByLabelText(/What happened\?/)

function fillValidForm(category = 'Non-payment') {
  fireEvent.click(screen.getByRole('radio', { name: new RegExp(category) }))
  fireEvent.change(description(), { target: { value: '  The customer refused to pay the agreed amount.  ' } })
}

async function submitAndConfirm() {
  fireEvent.click(screen.getByRole('button', { name: 'Submit dispute' }))
  fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Submit dispute' }))
}

beforeEach(() => {
  raiseDispute.mockReset()
  uploadEvidence.mockReset()
  fetchMyDisputes.mockReset()
})

afterEach(() => {
  cleanup()
})

describe('Provider "Raise a dispute"', () => {
  it('without a bookingId: a clear message and a link back to My Disputes, no form', () => {
    renderRaise('')

    expect(screen.getByText(/Open this page from the booking you want to dispute/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Back to My Disputes' }).getAttribute('href')).toBe('/provider/disputes')
    expect(screen.queryByRole('button', { name: 'Submit dispute' })).toBeNull()
  })

  it('an invalid bookingId is treated as missing', () => {
    renderRaise('?bookingId=../../admin')
    expect(screen.getByText(/Open this page from the booking/)).toBeTruthy()
  })

  it('offers exactly the provider categories (no no-show) and shows the booking reference', () => {
    renderRaise()

    const options = screen.getAllByRole('radio').map((radio) => radio.closest('label').querySelector('strong').textContent)
    expect(options).toEqual(['Property damage', 'Customer behaviour', 'Non-payment', 'Other'])
    expect(screen.queryByText('No-show')).toBeNull()
    expect(screen.getByText('B-048195')).toBeTruthy()
  })

  it('validates category and description before asking to confirm', async () => {
    renderRaise()

    fireEvent.click(screen.getByRole('button', { name: 'Submit dispute' }))

    expect(await screen.findByText('Choose what the dispute is about.')).toBeTruthy()
    expect(screen.getByText('Describe what happened.')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()

    fireEvent.change(description(), { target: { value: 'short' } })
    fireEvent.click(screen.getByRole('button', { name: 'Submit dispute' }))
    expect(await screen.findByText('Write at least 10 characters.')).toBeTruthy()
    expect(raiseDispute).not.toHaveBeenCalled()
  })

  it('submits, uploads the chosen evidence to the new dispute, then opens it', async () => {
    raiseDispute.mockResolvedValue({ id: 'new-dispute-1', status: 'AWAITING_RESPONSE' })
    uploadEvidence.mockResolvedValue({ id: 'ev-1' })
    const router = renderRaise()

    fillValidForm('Customer behaviour')
    fireEvent.change(screen.getByLabelText('Add evidence (optional)'), {
      target: { files: [new File(['%PDF-1.7'], 'invoice.pdf', { type: 'application/pdf' })] },
    })
    await submitAndConfirm()

    await waitFor(() => expect(router.state.location.pathname).toBe('/provider/disputes/new-dispute-1'))
    expect(raiseDispute).toHaveBeenCalledWith({ bookingId: BOOKING, category: 'CUSTOMER_BEHAVIOUR',
      description: 'The customer refused to pay the agreed amount.' })
    expect(uploadEvidence).toHaveBeenCalledWith('new-dispute-1', expect.any(File), expect.any(Function))
    expect(router.state.location.state).toEqual({ justRaised: true, failedUploads: 0 })
  })

  it('409 DISPUTE_WINDOW_CLOSED -> "The time to raise a dispute for this booking has passed"', async () => {
    raiseDispute.mockRejectedValue(apiError(409, 'DISPUTE_WINDOW_CLOSED'))
    renderRaise()

    fillValidForm()
    await submitAndConfirm()

    expect(await screen.findByText('The time to raise a dispute for this booking has passed.')).toBeTruthy()
  })

  it('409 DISPUTE_ALREADY_EXISTS -> message with a link to the existing dispute', async () => {
    raiseDispute.mockRejectedValue(apiError(409, 'DISPUTE_ALREADY_EXISTS'))
    fetchMyDisputes.mockResolvedValue({ content: [{ id: 'existing-9', bookingId: BOOKING }], totalPages: 1 })
    renderRaise()

    fillValidForm()
    await submitAndConfirm()

    expect(await screen.findByText(/A dispute already exists for this booking/)).toBeTruthy()
    expect((await screen.findByRole('link', { name: 'View the existing dispute' })).getAttribute('href'))
      .toBe('/provider/disputes/existing-9')
  })

  it('403 -> only for your own bookings; 400 field errors land on the fields', async () => {
    raiseDispute.mockRejectedValueOnce(apiError(403, 'ACCESS_DENIED'))
      .mockRejectedValueOnce(apiError(400, 'VALIDATION_FAILED',
        [{ field: 'description', message: 'description must be at most 2000 characters' }]))
    renderRaise()

    fillValidForm()
    await submitAndConfirm()
    expect(await screen.findByText('You can only raise a dispute for your own bookings.')).toBeTruthy()

    await submitAndConfirm()
    expect(await screen.findByText('description must be at most 2000 characters')).toBeTruthy()
  })

  it('a wrong evidence file type is rejected before submitting', () => {
    renderRaise()

    fireEvent.change(screen.getByLabelText('Add evidence (optional)'), {
      target: { files: [new File(['MZ'], 'setup.exe', { type: 'application/x-msdownload' })] },
    })

    expect(screen.getByText('Only JPG, PNG, WEBP or PDF files can be uploaded.')).toBeTruthy()
  })
})
