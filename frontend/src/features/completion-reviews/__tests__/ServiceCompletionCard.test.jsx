// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

// These screen tests open dialogs and type text; slower PCs need more than the 5 s default.
vi.setConfig({ testTimeout: 20000 })

const calls = vi.hoisted(() => ({ customer: [], provider: [] }))

vi.mock('../components/CustomerCompletionSection', () => ({
  default: (props) => {
    calls.customer.push(props)
    return <div>customer-card</div>
  },
}))
vi.mock('../components/ServiceCompletionSection', () => ({
  default: (props) => {
    calls.provider.push(props)
    return <div>provider-card</div>
  },
}))

import { ServiceCompletionCard } from '..'

afterEach(() => {
  cleanup()
  calls.customer.length = 0
  calls.provider.length = 0
})

const renderCard = (props) =>
  render(
    <MemoryRouter>
      <ServiceCompletionCard bookingId="bk-1" slotStart="2026-11-21T04:00:00Z" {...props} />
    </MemoryRouter>,
  )

describe('ServiceCompletionCard (for the WP05 Booking Details pages)', () => {
  it.each(['PENDING_PROVIDER', 'AWAITING_PAYMENT', 'DECLINED', 'EXPIRED', 'CANCELLED_BY_CUSTOMER', 'CANCELLED_BY_PROVIDER'])(
    'renders nothing for %s',
    (status) => {
      const { container } = renderCard({ status, role: 'CUSTOMER' })
      expect(container.textContent).toBe('')
    },
  )

  it('shows the customer view with the booking, review links and dispute links', () => {
    const onStatusChange = vi.fn()
    renderCard({ status: 'COMPLETION_PENDING', role: 'CUSTOMER', slotEnd: '2026-11-21T06:00:00Z', onStatusChange, hasReview: true })

    expect(screen.getByText('customer-card')).toBeTruthy()
    const props = calls.customer.at(-1)
    expect(props.booking).toEqual({ id: 'bk-1', status: 'COMPLETION_PENDING', slotStart: '2026-11-21T04:00:00Z', slotEnd: '2026-11-21T06:00:00Z' })
    expect(props.hasReview).toBe(true)
    expect(props.disputeUrlFor('d 1')).toBe('/customer/disputes/d%201')
    expect(props.onBookingStatusChange).toBe(onStatusChange)
  })

  it('shows the provider view from CONFIRMED with the provider dispute link', () => {
    renderCard({ status: 'CONFIRMED', role: 'PROVIDER', dispute: { id: 'd-9', status: 'OPEN' } })

    expect(screen.getByText('provider-card')).toBeTruthy()
    const props = calls.provider.at(-1)
    expect(props.booking.status).toBe('CONFIRMED')
    expect(props.dispute).toEqual({ url: '/provider/disputes/d-9', id: 'd-9', status: 'OPEN' })
  })

  it('renders nothing without a bookingId or with an unknown role', () => {
    expect(renderCard({ bookingId: '', status: 'COMPLETED', role: 'CUSTOMER' }).container.textContent).toBe('')
    cleanup()
    expect(renderCard({ status: 'COMPLETED', role: 'ADMIN' }).container.textContent).toBe('')
  })
})
