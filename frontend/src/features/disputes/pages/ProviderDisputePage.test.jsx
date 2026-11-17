// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchDispute, submitDisputeResponse } from '../services/disputesApi'
import { ProviderDisputePage } from './ProviderDisputePage'

vi.mock('../services/disputesApi', () => ({
  fetchDispute: vi.fn(),
  submitDisputeResponse: vi.fn(),
  isRequestCancelled: () => false,
}))

const HOUR = 60 * 60 * 1000

function dispute(overrides = {}) {
  return {
    id: 'abc123def456',
    bookingId: 'booking-789',
    customerId: 'c-1',
    providerId: 'p-1',
    raisedBy: 'c-1',
    raisedByRole: 'CUSTOMER',
    category: 'POOR_QUALITY',
    description: '<img src=x onerror="alert(1)"> plain text only',
    evidence: ['evidence/photo.jpg', 'javascript:alert(1)'],
    status: 'AWAITING_RESPONSE',
    response: null,
    responseDueAt: new Date(Date.now() + 52 * HOUR + 30 * 60 * 1000).toISOString(),
    createdAt: new Date(Date.now() - 20 * HOUR).toISOString(),
    updatedAt: new Date(Date.now() - 20 * HOUR).toISOString(),
    statusHistory: [{
      fromStatus: null, toStatus: 'AWAITING_RESPONSE', actorId: 'c-1', actorRole: 'CUSTOMER', reason: null,
      timestamp: new Date(Date.now() - 20 * HOUR).toISOString(),
    }],
    ...overrides,
  }
}

function apiError(status, error) {
  return Object.assign(new Error(error), { response: { status, data: { error } } })
}

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/provider/disputes/abc123def456']}>
      <Routes>
        <Route path="/provider/disputes/:disputeId" element={<ProviderDisputePage />} />
        <Route path="/login" element={<p>Login page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  fetchDispute.mockReset()
  submitDisputeResponse.mockReset()
})

afterEach(() => {
  cleanup()
})

describe('ProviderDisputePage', () => {
  it('shows the dispute, the countdown and the form while the response window is open', async () => {
    fetchDispute.mockResolvedValue(dispute())
    renderPage()

    expect(await screen.findByRole('heading', { level: 1, name: 'Dispute D-DEF456' })).toBeTruthy()
    expect(screen.getByText('Awaiting your response')).toBeTruthy()
    expect(screen.getByText(/2 days 4 hours left/)).toBeTruthy()
    expect(screen.getByText(/payout for this booking is on hold/i)).toBeTruthy()
    expect(screen.getByLabelText(/tell your side/i)).toBeTruthy()
  })

  it('renders user text as plain text and does not link unsafe evidence', async () => {
    fetchDispute.mockResolvedValue(dispute())
    const { container } = render(
      <MemoryRouter initialEntries={['/provider/disputes/abc123def456']}>
        <Routes>
          <Route path="/provider/disputes/:disputeId" element={<ProviderDisputePage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText(/plain text only/)).toBeTruthy()
    expect(container.querySelector('img[src="x"]')).toBeNull()
    expect(container.querySelector('a[href^="javascript"]')).toBeNull()
  })

  it('disables the form when the 72h deadline has passed', async () => {
    fetchDispute.mockResolvedValue(dispute({ responseDueAt: new Date(Date.now() - HOUR).toISOString() }))
    renderPage()

    expect(await screen.findByText(/the 72-hour deadline has passed/i)).toBeTruthy()
    expect(screen.queryByLabelText(/tell your side/i)).toBeNull()
    expect(screen.getByText('Response overdue')).toBeTruthy()
    expect(screen.queryByText('Awaiting your response')).toBeNull()
  })

  it('submits the response, then shows it read-only with a success message', async () => {
    fetchDispute.mockResolvedValue(dispute())
    const respondedAt = new Date().toISOString()
    submitDisputeResponse.mockResolvedValue(dispute({
      status: 'UNDER_REVIEW',
      response: { responderId: 'p-1', text: 'I arrived on time.', evidence: [], respondedAt },
    }))
    renderPage()

    fireEvent.change(await screen.findByLabelText(/tell your side/i), { target: { value: 'I arrived on time.' } })
    fireEvent.click(screen.getByRole('button', { name: /submit response/i }))

    expect(await screen.findByText(/your response was submitted/i)).toBeTruthy()
    expect(screen.getByText('Under review')).toBeTruthy()
    expect(screen.getByText('I arrived on time.')).toBeTruthy()
    expect(screen.queryByLabelText(/tell your side/i)).toBeNull()
  })

  it.each([
    [404, 'DISPUTE_NOT_FOUND', /dispute not found/i],
    [403, 'NOT_DISPUTE_PARTY', /access denied/i],
    [500, 'INTERNAL_ERROR', /something went wrong/i],
  ])('shows the right state for %i %s', async (status, code, text) => {
    fetchDispute.mockRejectedValue(apiError(status, code))
    renderPage()

    expect(await screen.findByRole('heading', { name: text })).toBeTruthy()
  })

  it('retries after an error', async () => {
    fetchDispute.mockRejectedValueOnce(apiError(500, 'INTERNAL_ERROR')).mockResolvedValueOnce(dispute())
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /try again/i }))

    expect(await screen.findByLabelText(/tell your side/i)).toBeTruthy()
    expect(fetchDispute).toHaveBeenCalledTimes(2)
  })

  it('sends a signed-out user (401) to the login page', async () => {
    fetchDispute.mockRejectedValue(apiError(401, 'UNAUTHORIZED'))
    renderPage()

    expect(await screen.findByText('Login page')).toBeTruthy()
  })
})
