// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { submitDisputeResponse } from '../services/disputesApi'
import { MAX_RESPONSE_LENGTH, validateResponseText } from '../utils/responseValidation'
import { DisputeResponseForm } from './DisputeResponseForm'

vi.mock('../services/disputesApi', () => ({
  submitDisputeResponse: vi.fn(),
}))

function renderForm(props = {}) {
  const onSubmitted = vi.fn()
  const onWindowClosed = vi.fn()
  render(
    <MemoryRouter>
      <DisputeResponseForm
        disputeId="d-1"
        canRespond
        closedMessage="Response window closed. The 72-hour deadline has passed."
        onSubmitted={onSubmitted}
        onWindowClosed={onWindowClosed}
        {...props}
      />
    </MemoryRouter>,
  )
  return { onSubmitted, onWindowClosed }
}

function typeResponse(text) {
  fireEvent.change(screen.getByLabelText(/tell your side/i), { target: { value: text } })
}

function submitButton() {
  return screen.getByRole('button', { name: /submit/i })
}

function apiError(status, error, fieldErrors = []) {
  return Object.assign(new Error(error), { response: { status, data: { error, fieldErrors } } })
}

beforeEach(() => {
  submitDisputeResponse.mockReset()
})

afterEach(() => {
  cleanup()
})

describe('validateResponseText', () => {
  it('requires text and enforces the backend limit', () => {
    expect(validateResponseText('   ')).toMatch(/tell your side/i)
    expect(validateResponseText('x'.repeat(MAX_RESPONSE_LENGTH))).toBeNull()
    expect(validateResponseText('x'.repeat(MAX_RESPONSE_LENGTH + 1))).toMatch(/at most 2000/)
  })
})

describe('DisputeResponseForm', () => {
  it('shows a validation error for an empty response and does not call the API', () => {
    renderForm()

    fireEvent.click(submitButton())

    expect(screen.getByText(/please tell your side/i)).toBeTruthy()
    expect(submitDisputeResponse).not.toHaveBeenCalled()
  })

  it('shows a character counter and limits input to 2000 characters', () => {
    renderForm()
    typeResponse('Arrived on time')

    expect(screen.getByText(`15 / ${MAX_RESPONSE_LENGTH}`)).toBeTruthy()
    expect(screen.getByLabelText(/tell your side/i).getAttribute('maxlength')).toBe(String(MAX_RESPONSE_LENGTH))
  })

  it('submits the trimmed text once, even when clicked twice, and reports the updated dispute', async () => {
    let resolve
    submitDisputeResponse.mockReturnValue(new Promise((done) => { resolve = done }))
    const { onSubmitted } = renderForm()
    typeResponse('  I arrived on time and finished the work.  ')

    fireEvent.click(submitButton())
    fireEvent.click(submitButton())

    expect(submitDisputeResponse).toHaveBeenCalledTimes(1)
    expect(submitDisputeResponse).toHaveBeenCalledWith('d-1', 'I arrived on time and finished the work.')
    expect(submitButton().disabled).toBe(true)

    resolve({ id: 'd-1', status: 'UNDER_REVIEW' })
    await waitFor(() => expect(onSubmitted).toHaveBeenCalledWith(
      { id: 'd-1', status: 'UNDER_REVIEW', evidenceFiles: [] }, { failedUploads: 0 }))
  })

  it('is replaced by the closed message when the window is closed', () => {
    renderForm({ canRespond: false })

    expect(screen.getByText(/response window closed/i)).toBeTruthy()
    expect(screen.queryByLabelText(/tell your side/i)).toBeNull()
    expect(screen.queryByRole('button', { name: /submit/i })).toBeNull()
  })

  it('maps RESPONSE_WINDOW_CLOSED to a friendly message and asks the page to reload', async () => {
    submitDisputeResponse.mockRejectedValue(apiError(400, 'RESPONSE_WINDOW_CLOSED'))
    const { onWindowClosed, onSubmitted } = renderForm()
    typeResponse('Late response')

    fireEvent.click(submitButton())

    expect(await screen.findByText(/response window for this dispute has closed/i)).toBeTruthy()
    expect(onWindowClosed).toHaveBeenCalledTimes(1)
    expect(onSubmitted).not.toHaveBeenCalled()
    expect(submitButton().disabled).toBe(false)
  })

  it('shows the backend field error for 400 VALIDATION_FAILED', async () => {
    submitDisputeResponse.mockRejectedValue(apiError(400, 'VALIDATION_FAILED', [{ field: 'text', message: 'text is required' }]))
    renderForm()
    typeResponse('Something')

    fireEvent.click(submitButton())

    expect(await screen.findByText('text is required')).toBeTruthy()
  })

  it('shows the not-responding-party message for 403 NOT_RESPONDING_PARTY', async () => {
    submitDisputeResponse.mockRejectedValue(apiError(403, 'NOT_RESPONDING_PARTY'))
    renderForm()
    typeResponse('Something')

    fireEvent.click(submitButton())

    expect(await screen.findByText(/only the other party can respond/i)).toBeTruthy()
  })
})
