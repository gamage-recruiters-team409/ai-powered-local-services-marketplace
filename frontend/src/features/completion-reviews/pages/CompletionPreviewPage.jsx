import { useState } from 'react'

import * as mockApi from '../api/completionApi.mock'
import ServiceCompletionSection from '../components/ServiceCompletionSection'
import CompletionStatusBadge from '../components/CompletionStatusBadge'
import '../styles/completion.css'

const HOUR = 60 * 60 * 1000

/**
 * Development preview of the provider Service Completion card (WP08, S01–S03) with the mock API.
 * The real booking details page belongs to WP05; it will render <ServiceCompletionSection />.
 */
function buildScenario(name) {
  const now = Date.now()
  const iso = (offsetMs) => new Date(now + offsetMs).toISOString()
  const booking = {
    id: `preview-${name}`,
    ref: 'BK-2026-00123',
    title: 'Deep House Cleaning',
    customerName: 'Nimal Perera',
    providerName: 'Bright Home Cleaning',
    slotStart: iso(-1 * HOUR),
    slotEnd: iso(2 * HOUR),
  }
  const started = { startedAt: iso(-55 * 60 * 1000) }
  const marked = {
    ...started,
    completionNote: 'Cleaned 3 rooms, kitchen and 2 bathrooms. Balcony glass wiped.',
    photoUrls: ['mock://photo-1', 'mock://photo-2', 'mock://photo-3'],
    markedCompletedAt: iso(-(16 * HOUR + 40 * 60 * 1000)),
  }

  switch (name) {
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

const SCENARIOS = [
  ['confirmed', 'Confirmed'],
  ['in-progress', 'In progress'],
  ['overdue', 'In progress – overdue'],
  ['pending', 'Awaiting confirmation'],
  ['disputed', 'Disputed'],
  ['resolved', 'Dispute resolved – appeal'],
  ['completed', 'Completed'],
]

export default function CompletionPreviewPage() {
  const [run, setRun] = useState(() => {
    const scenario = buildScenario('confirmed')
    mockApi.seedCompletion(scenario.booking.id, scenario.completion)
    return { name: 'confirmed', version: 0, scenario, status: scenario.booking.status }
  })

  const choose = (name) => {
    const scenario = buildScenario(name)
    mockApi.seedCompletion(scenario.booking.id, scenario.completion)
    setRun((previous) => ({ name, version: previous.version + 1, scenario, status: scenario.booking.status }))
  }

  const { scenario } = run

  return (
    <main className="sc-preview">
      <p className="sc-preview__note">
        Preview – WP08 Service Completion (provider) with mock data. Pick a state:
      </p>
      <div className="sc-preview__tabs" role="group" aria-label="Preview state">
        {SCENARIOS.map(([name, label]) => (
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

      <nav className="sc-preview__crumbs" aria-label="Breadcrumb">Dashboard › My Bookings › {scenario.booking.ref}</nav>
      <div className="sc-preview__title">
        <h1>{scenario.booking.title}</h1>
        <CompletionStatusBadge status={run.status} />
      </div>

      <ServiceCompletionSection
        key={`${run.name}-${run.version}`}
        booking={scenario.booking}
        api={mockApi}
        dispute={scenario.dispute}
        earnings={scenario.earnings}
        review={scenario.review}
        onBookingStatusChange={(status) => setRun((previous) => ({ ...previous, status }))}
        onReportNoShow={() => window.alert('Report No-Show will be added in the next task.')}
        onReplyToReview={() => window.alert('Reply to review will be added with the review pages.')}
      />
    </main>
  )
}
