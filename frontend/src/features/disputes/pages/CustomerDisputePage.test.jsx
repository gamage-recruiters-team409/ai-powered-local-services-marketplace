// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchDispute, submitDisputeResponse } from '../services/disputesApi'
import { CustomerDisputePage } from './CustomerDisputePage'

vi.mock('../services/disputesApi', () => ({
  fetchDispute: vi.fn(),
  submitDisputeResponse: vi.fn(),
  isRequestCancelled: () => false,
}))

const HOUR = 60 * 60 * 1000

function dispute(overrides = {}) {
  const createdAt = new Date(Date.now() - 10 * HOUR).toISOString()
  return {
    id: '6a0f00c0ffee00a000001045',
    bookingId: '6a0f00c0ffee00b000048195',
    customerId: 'c-1',
    providerId: 'p-1',
    raisedBy: 'p-1',
    raisedByRole: 'PROVIDER',
    category: 'CONDUCT',
    description: 'Provider report text',
    evidence: [],
    status: 'AWAITING_RESPONSE',
    response: null,
    responseDueAt: new Date(Date.now() + 62 * HOUR).toISOString(),
    createdAt,
    updatedAt: createdAt,
    statusHistory: [{
      fromStatus: null, toStatus: 'AWAITING_RESPONSE', actorId: 'p-1', actorRole: 'PROVIDER', reason: null,
      timestamp: createdAt,
    }],
    ...overrides,
  }
}

function apiError(status, error, fieldErrors = []) {
  return Object.assign(new Error(error), { response: { status, data: { error, fieldErrors } } })
}

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/customer/disputes/6a0f00c0ffee00a000001045']}>
      <Routes>
        <Route path="/customer/disputes/:disputeId" element={<CustomerDisputePage />} />
      </Routes>
    </MemoryRouter>,
  )
}

const textBox = () => screen.queryByLabelText(/tell your side/i)
const submit = () => screen.getByRole('button', { name: /submit/i })

beforeEach(() => {
  fetchDispute.mockReset()
  submitDisputeResponse.mockReset()
})

afterEach(() => {
  cleanup()
})

describe('CustomerDisputePage response workflow', () => {
  it('shows the response form to the customer when the provider raised the dispute', async () => {
    fetchDispute.mockResolvedValue(dispute())
    renderPage()

    expect(await screen.findByText('Awaiting your response')).toBeTruthy()
    expect(screen.getByText('Issue reported by the provider')).toBeTruthy()
    expect(textBox()).toBeTruthy()
    expect(screen.queryByText(/available soon/i)).toBeNull()
    expect(screen.getByText('You respond')).toBeTruthy()
  })

  it('keeps the read-only view when the customer raised the dispute', async () => {
    fetchDispute.mockResolvedValue(dispute({ raisedBy: 'c-1', raisedByRole: 'CUSTOMER' }))
    renderPage()

    expect(await screen.findByText("Awaiting provider's response")).toBeTruthy()
    expect(screen.getByText('Your issue')).toBeTruthy()
    expect(textBox()).toBeNull()
    expect(screen.queryByRole('button', { name: /submit/i })).toBeNull()
  })

  it('validates an empty response without calling the API', async () => {
    fetchDispute.mockResolvedValue(dispute())
    renderPage()
    await screen.findByLabelText(/tell your side/i)

    fireEvent.click(submit())

    expect(screen.getByText(/please tell your side/i)).toBeTruthy()
    expect(submitDisputeResponse).not.toHaveBeenCalled()
  })

  it('submits once even when clicked twice, then shows the response read-only', async () => {
    fetchDispute.mockResolvedValue(dispute())
    let resolve
    submitDisputeResponse.mockReturnValue(new Promise((done) => { resolve = done }))
    renderPage()
    fireEvent.change(await screen.findByLabelText(/tell your side/i), { target: { value: '  The door was open.  ' } })

    fireEvent.click(submit())
    fireEvent.click(submit())

    expect(submitDisputeResponse).toHaveBeenCalledTimes(1)
    expect(submitDisputeResponse).toHaveBeenCalledWith('6a0f00c0ffee00a000001045', 'The door was open.')

    resolve(dispute({
      status: 'UNDER_REVIEW',
      response: { responderId: 'c-1', text: 'The door was open.', evidence: [], respondedAt: new Date().toISOString() },
    }))
    expect(await screen.findByText(/your response was submitted/i)).toBeTruthy()
    expect(screen.getByText('Under review')).toBeTruthy()
    expect(screen.getByText('The door was open.')).toBeTruthy()
    expect(textBox()).toBeNull()
  })

  it('shows the closed state instead of the form after the deadline', async () => {
    fetchDispute.mockResolvedValue(dispute({ responseDueAt: new Date(Date.now() - HOUR).toISOString() }))
    renderPage()

    expect(await screen.findByText(/the 72-hour deadline has passed/i)).toBeTruthy()
    expect(textBox()).toBeNull()
    expect(screen.getByText('Response overdue')).toBeTruthy()
  })

  it.each([
    [400, 'RESPONSE_WINDOW_CLOSED', /response window for this dispute has closed/i],
    [409, 'INVALID_DISPUTE_STATUS', /response window for this dispute has closed/i],
    [403, 'NOT_RESPONDING_PARTY', /only the other party can respond/i],
    [409, 'DISPUTE_CONCURRENT_UPDATE', /changed by someone else/i],
  ])('maps %i %s to a friendly message', async (status, code, message) => {
    fetchDispute.mockResolvedValue(dispute())
    submitDisputeResponse.mockRejectedValue(apiError(status, code))
    renderPage()
    fireEvent.change(await screen.findByLabelText(/tell your side/i), { target: { value: 'My side' } })

    fireEvent.click(submit())

    expect(await screen.findByText(message)).toBeTruthy()
  })

  it('reloads the dispute when the window closed while the customer was typing', async () => {
    fetchDispute.mockResolvedValue(dispute())
    submitDisputeResponse.mockRejectedValue(apiError(400, 'RESPONSE_WINDOW_CLOSED'))
    renderPage()
    fireEvent.change(await screen.findByLabelText(/tell your side/i), { target: { value: 'My side' } })

    fireEvent.click(submit())

    await waitFor(() => expect(fetchDispute).toHaveBeenCalledTimes(2))
  })

  it('never claims that a payout is on hold', async () => {
    fetchDispute.mockResolvedValue(dispute())
    renderPage()
    await screen.findByLabelText(/tell your side/i)

    expect(screen.queryByText(/on hold|held until|funds protected/i)).toBeNull()
    expect(screen.getByText(/payment handling for this booking will follow the dispute outcome/i)).toBeTruthy()
  })
})
