import { useState } from 'react'

import * as mockApi from '../api/completionApi.mock'
import CompletionStatusBadge from '../components/CompletionStatusBadge'
import CustomerCompletionSection from '../components/CustomerCompletionSection'
import ServiceCompletionSection from '../components/ServiceCompletionSection'
import '../styles/completion.css'

const HOUR = 60 * 60 * 1000
const MINUTE = 60 * 1000

/**
 * Development preview of the Service Completion card (WP08) with the mock API.
 * Provider view: S01–S03 + S06 · Customer view: S04–S06.
 * The real booking details page belongs to WP05; it will render one of these cards.
 */
function buildScenario(name) {
  const now = Date.now()
  const iso = (offsetMs) => new Date(now + offsetMs).toISOString()
  const booking = {
    id: `preview-${name}`,
    ref: 'BK-2026-00123',
    title: 'Deep House Cleaning',
    customerName: 'Nimal Perera',
    providerName: 'Kamal Services',
    slotStart: iso(-1 * HOUR),
    slotEnd: iso(2 * HOUR),
  }
  const started = { startedAt: iso(-55 * MINUTE) }
  const marked = {
    ...started,
    completionNote: 'Cleaned 3 rooms, kitchen and 2 bathrooms. Balcony glass wiped.',
    photoUrls: ['mock://photo-1', 'mock://photo-2', 'mock://photo-3'],
    markedCompletedAt: iso(-(16 * HOUR + 40 * MINUTE)),
  }

  switch (name) {
    case 'no-show-allowed':
      return {
        booking: { ...booking, status: 'CONFIRMED', slotStart: iso(-(2 * HOUR + 30 * MINUTE)), slotEnd: iso(-30 * MINUTE) },
        completion: null,
      }
    case 'in-progress':
      return { booking: { ...booking, status: 'IN_PROGRESS' }, completion: started }
    case 'overdue':
      return {
        booking: { ...booking, status: 'IN_PROGRESS', slotStart: iso(-6 * HOUR), slotEnd: iso(-3 * HOUR) },
        completion: { startedAt: iso(-5.9 * HOUR) },
      }
    case 'pending':
      return { booking: { ...booking, status: 'COMPLETION_PENDING' }, completion: marked }
    case 'disputed':
      return {
        booking: { ...booking, status: 'DISPUTED' },
        completion: { ...marked, issueRaisedAt: iso(-2 * HOUR), disputeId: 'DSP-1043' },
        dispute: { id: 'DSP-1043', reason: 'Balcony was not cleaned.', url: '#dispute-DSP-1043' },
      }
    case 'resolved':
      return {
        booking: { ...booking, status: 'PARTIALLY_REFUNDED' },
        completion: { ...marked, issueRaisedAt: iso(-50 * HOUR), disputeId: 'DSP-1043' },
        dispute: {
          id: 'DSP-1043', status: 'RESOLVED', resolvedAt: iso(-3 * HOUR), canAppeal: true,
          outcome: '30% refunded to the customer.', url: '#dispute-DSP-1043',
        },
      }
    case 'completed':
      return {
        booking: { ...booking, status: 'COMPLETED' },
        completion: { ...marked, confirmedAt: iso(-2 * HOUR), confirmationType: 'CUSTOMER' },
        earnings: { amountLabel: 'LKR 6,500', payoutStatus: 'PENDING', url: '#earnings' },
        review: {
          customerName: 'Nimal Perera', rating: 5, status: 'PUBLISHED',
          comment: 'Arrived on time and did a careful job. Would book again.',
        },
      }
    default:
      return { booking: { ...booking, status: 'CONFIRMED' }, completion: null }
  }
}

const SCENARIOS = {
  PROVIDER: [
    ['confirmed', 'Confirmed (no-show too early)'],
    ['no-show-allowed', 'Confirmed (no-show allowed)'],
    ['in-progress', 'In progress'],
    ['overdue', 'In progress – overdue'],
    ['pending', 'Awaiting confirmation'],
    ['disputed', 'Disputed'],
    ['resolved', 'Dispute resolved – appeal'],
    ['completed', 'Completed'],
  ],
  CUSTOMER: [
    ['confirmed', 'Confirmed (no-show too early)'],
    ['no-show-allowed', 'Confirmed (no-show allowed)'],
    ['in-progress', 'In progress'],
    ['pending', 'Awaiting confirmation'],
    ['disputed', 'Disputed'],
    ['resolved', 'Dispute resolved – appeal'],
    ['completed', 'Completed'],
  ],
}

function seed(scenario) {
  mockApi.seedCompletion(scenario.booking.id, scenario.completion)
  mockApi.seedBookingSlot(scenario.booking.id, scenario.booking.slotStart)
}

export default function CompletionPreviewPage() {
  const [run, setRun] = useState(() => {
    const scenario = buildScenario('confirmed')
    seed(scenario)
    return { role: 'PROVIDER', name: 'confirmed', version: 0, scenario, status: scenario.booking.status }
  })

  const choose = (name, role = run.role) => {
    const scenario = buildScenario(name)
    seed(scenario)
    setRun((previous) => ({ role, name, version: previous.version + 1, scenario, status: scenario.booking.status }))
  }

  const switchRole = (role) => {
    const available = SCENARIOS[role].some(([name]) => name === run.name)
    choose(available ? run.name : 'confirmed', role)
  }

  const { scenario } = run
  const onStatus = (status) => setRun((previous) => ({ ...previous, status }))
  const key = `${run.role}-${run.name}-${run.version}`

  return (
    <main className="sc-preview">
      <p className="sc-preview__note">Preview – WP08 Service Completion with mock data. Pick a view and a state:</p>
      <div className="sc-preview__roles" role="group" aria-label="View">
        {['PROVIDER', 'CUSTOMER'].map((role) => (
          <button
            key={role}
            type="button"
            className={`sc-preview__role${run.role === role ? ' sc-preview__role--active' : ''}`}
            aria-pressed={run.role === role}
            onClick={() => switchRole(role)}
          >
            {role === 'PROVIDER' ? 'Provider' : 'Customer'}
          </button>
        ))}
      </div>
      <div className="sc-preview__tabs" role="group" aria-label="Preview state">
        {SCENARIOS[run.role].map(([name, label]) => (
          <button
            key={name}
            type="button"
            className={`sc-preview__tab${run.name === name ? ' sc-preview__tab--active' : ''}`}
            aria-pressed={run.name === name}
            onClick={() => choose(name)}
          >
            {label}
          </button>
        ))}
      </div>

      <nav className="sc-preview__crumbs" aria-label="Breadcrumb">
        {run.role === 'PROVIDER' ? 'Dashboard' : 'Home'} › My Bookings › {scenario.booking.ref}
      </nav>
      <div className="sc-preview__title">
        <h1>{scenario.booking.title}</h1>
        <CompletionStatusBadge status={run.status} />
      </div>

      {run.role === 'PROVIDER' ? (
        <ServiceCompletionSection
          key={key}
          booking={scenario.booking}
          api={mockApi}
          dispute={scenario.dispute}
          earnings={scenario.earnings}
          review={scenario.review}
          onBookingStatusChange={onStatus}
          onReplyToReview={() => window.alert('Reply to review will be added with the review pages.')}
        />
      ) : (
        <CustomerCompletionSection
          key={key}
          booking={scenario.booking}
          api={mockApi}
          dispute={scenario.dispute}
          disputeUrlFor={(id) => `#dispute-${id}`}
          onBookingStatusChange={onStatus}
          onLeaveReview={() => window.alert('The review pages (S07) will be added next.')}
        />
      )}
    </main>
  )
}
