// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import ToastProvider from './ToastProvider.jsx'
import { useToast } from './useToast.js'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

function Trigger({ onReady }) {
  const toast = useToast()
  onReady(toast)
  return null
}

function setup(maxToasts) {
  let api
  render(
    <ToastProvider maxToasts={maxToasts}>
      <Trigger onReady={(t) => (api = t)} />
    </ToastProvider>,
  )
  return () => api
}

describe('ToastProvider', () => {
  it('shows a toast and removes it with the close button', () => {
    const get = setup()
    act(() => get().success('Saved'))
    expect(screen.getByText('Saved')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss notification' }))
    expect(screen.queryByText('Saved')).toBeNull()
  })

  it('auto-dismisses after its duration', () => {
    vi.useFakeTimers()
    const get = setup()
    act(() => get().success('Saved'))
    expect(screen.getByText('Saved')).toBeTruthy()
    act(() => vi.advanceTimersByTime(4100))
    expect(screen.queryByText('Saved')).toBeNull()
  })

  it('runs the action and dismisses the toast', () => {
    const onClick = vi.fn()
    const get = setup()
    act(() =>
      get().message('New message', { action: { label: 'Open chat', onClick } }),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Open chat' }))
    expect(onClick).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('New message')).toBeNull()
  })

  it('keeps sticky and actionable toasts when the limit is reached', () => {
    const get = setup(3)
    act(() => {
      get().error('Sticky error', { duration: 0 })
      get().message('Actionable', { action: { label: 'Open', onClick: () => {} } })
      get().info('Plain info')
      get().success('Newest')
    })
    expect(screen.getByText('Sticky error')).toBeTruthy()
    expect(screen.getByText('Actionable')).toBeTruthy()
    expect(screen.getByText('Newest')).toBeTruthy()
    expect(screen.queryByText('Plain info')).toBeNull()
  })

  it('drops the oldest toast only when every toast is protected', () => {
    const get = setup(2)
    act(() => {
      get().error('First sticky', { duration: 0 })
      get().error('Second sticky', { duration: 0 })
      get().error('Third sticky', { duration: 0 })
    })
    expect(screen.queryByText('First sticky')).toBeNull()
    expect(screen.getByText('Second sticky')).toBeTruthy()
    expect(screen.getByText('Third sticky')).toBeTruthy()
  })
})
