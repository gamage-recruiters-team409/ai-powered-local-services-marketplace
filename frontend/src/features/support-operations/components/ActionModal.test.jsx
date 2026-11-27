// @vitest-environment jsdom

import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import '../testing/renderOps'
import { ActionModal } from './ActionModal'

describe('ActionModal', () => {
  const actions = [
    { value: 'FLAG_CONCERN', label: 'Flag concern' },
    { value: 'SUSPEND', label: 'Suspension' },
  ]

  it('allows 501-character note for record-only action (FLAG_CONCERN) but rejects it for SUSPEND', async () => {
    const onSubmit = vi.fn().mockResolvedValue({})
    const note501 = 'a'.repeat(501)

    render(
      <ActionModal
        isOpen
        onClose={vi.fn()}
        title="Take action"
        facts={[]}
        actions={actions}
        onSubmit={onSubmit}
      />,
    )

    // Initial action is FLAG_CONCERN (record-only)
    const textarea = screen.getByLabelText(/Administrative note/)
    expect(screen.getByText('0/1000')).toBeTruthy()

    fireEvent.change(textarea, { target: { value: note501 } })
    expect(screen.getByText('501/1000')).toBeTruthy()

    // Switch action to SUSPEND
    const select = screen.getByLabelText(/Action/)
    fireEvent.change(select, { target: { value: 'SUSPEND' } })

    // Character counter dynamically updates to /500
    expect(screen.getByText('501/500')).toBeTruthy()

    // Submitting 501 characters for SUSPEND is prevented with an inline error
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('A note of 10–500 characters is required.')).toBeTruthy()
    expect(onSubmit).not.toHaveBeenCalled()

    // Switch back to record-only action
    fireEvent.change(select, { target: { value: 'FLAG_CONCERN' } })
    expect(screen.getByText('501/1000')).toBeTruthy()

    // Submitting 501 characters for record-only action is permitted
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(onSubmit).toHaveBeenCalledWith('FLAG_CONCERN', note501)
  })

  it('accepts up to 500 characters for SUSPEND', async () => {
    const onSubmit = vi.fn().mockResolvedValue({})
    const note500 = 'b'.repeat(500)

    render(
      <ActionModal
        isOpen
        onClose={vi.fn()}
        title="Take action"
        facts={[]}
        actions={[{ value: 'SUSPEND', label: 'Suspension' }]}
        onSubmit={onSubmit}
      />,
    )

    const textarea = screen.getByLabelText(/Administrative note/)
    expect(screen.getByText('0/500')).toBeTruthy()

    fireEvent.change(textarea, { target: { value: note500 } })
    expect(screen.getByText('500/500')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(onSubmit).toHaveBeenCalledWith('SUSPEND', note500)
  })
})
