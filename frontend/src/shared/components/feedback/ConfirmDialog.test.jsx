// @vitest-environment jsdom
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ConfirmDialog from './ConfirmDialog.jsx'

afterEach(() => cleanup())

function Page(props) {
  return (
    <div>
      <button type="button">Background action</button>
      <ConfirmDialog
        open
        title="Hide message"
        message="The message will be hidden."
        {...props}
      />
    </div>
  )
}

const insideDialog = () =>
  screen.getByRole('dialog').contains(document.activeElement)

describe('ConfirmDialog accessibility', () => {
  it('makes the background inert while open and restores it on close', () => {
    const { container, rerender } = render(<Page />)

    expect(container.hasAttribute('inert')).toBe(true)
    expect(container.getAttribute('aria-hidden')).toBe('true')
    // the dialog itself is not isolated
    expect(screen.getByRole('dialog').closest('[inert]')).toBeNull()

    rerender(<Page open={false} />)

    expect(container.hasAttribute('inert')).toBe(false)
    expect(container.hasAttribute('aria-hidden')).toBe(false)
  })

  it('keeps Tab and Shift+Tab inside the dialog', async () => {
    const user = userEvent.setup()
    render(<Page />)
    expect(insideDialog()).toBe(true)

    for (let i = 0; i < 4; i += 1) {
      await user.tab()
      expect(insideDialog()).toBe(true)
    }
    for (let i = 0; i < 4; i += 1) {
      await user.tab({ shift: true })
      expect(insideDialog()).toBe(true)
    }
  })

  it('keeps focus inside, and ignores actions, while loading', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(<Page loading onConfirm={onConfirm} onCancel={onCancel} />)

    const buttons = screen.getAllByRole('button', { hidden: false })
    const confirm = screen.getByRole('button', { name: 'Confirm' })
    const cancel = screen.getByRole('button', { name: 'Cancel' })
    expect(buttons.length).toBeGreaterThan(0)
    // not natively disabled, so they stay focusable
    expect(confirm.getAttribute('aria-disabled')).toBe('true')
    expect(confirm.hasAttribute('disabled')).toBe(false)
    expect(cancel.getAttribute('aria-disabled')).toBe('true')
    expect(insideDialog()).toBe(true)

    for (let i = 0; i < 4; i += 1) {
      await user.tab()
      expect(insideDialog()).toBe(true)
    }

    await user.click(confirm)
    await user.click(cancel)
    await user.keyboard('{Escape}')
    expect(onConfirm).not.toHaveBeenCalled()
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('keeps the reason field focusable (read-only) while loading', () => {
    render(<Page loading requireReason />)
    const reason = screen.getByRole('textbox')
    expect(reason.hasAttribute('readonly')).toBe(true)
    expect(reason.hasAttribute('disabled')).toBe(false)
  })

  it('pulls focus back if something outside tries to take it', () => {
    render(<Page />)
    screen.getByText('Background action').focus()
    expect(insideDialog()).toBe(true)
  })

  it('closes on Escape when not loading', async () => {
    const user = userEvent.setup()
    const onCancel = vi.fn()
    render(<Page onCancel={onCancel} />)
    await user.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('gives focus to the safe button first for destructive dialogs', () => {
    render(<Page variant="destructive" confirmLabel="Hide" />)
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cancel' }))
  })

  it('returns focus to the opener when it closes', async () => {
    const user = userEvent.setup()
    function Opener() {
      const [open, setOpen] = useState(false)
      return (
        <div>
          <button type="button" onClick={() => setOpen(true)}>
            Open
          </button>
          <ConfirmDialog
            open={open}
            title="Sure?"
            onCancel={() => setOpen(false)}
            onConfirm={() => setOpen(false)}
          />
        </div>
      )
    }
    render(<Opener />)
    const opener = screen.getByRole('button', { name: 'Open' })
    await user.click(opener)
    expect(screen.getByRole('dialog')).toBeTruthy()

    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(opener)
  })
})
