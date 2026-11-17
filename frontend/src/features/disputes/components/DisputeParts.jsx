import { Link } from 'react-router-dom'

import {
  categoryLabel,
  DISPUTE_STATUS,
  getStatusBadge,
  roleLabel,
  TOTAL_STAGES,
} from '../config/disputeStatus'
import { formatTimeLeft, getTimeLeft } from '../utils/countdown'
import { evidenceName, formatDateTime, shortReference } from '../utils/format'
import { safeEvidenceHref } from '../utils/safeLink'
import { DisputeIcon } from './DisputeIcon'
import styles from '../disputes.module.css'

/** All user-provided text is rendered as React text (escaped); nothing here uses raw HTML. */

export function Breadcrumb({ items }) {
  return (
    <nav aria-label="Breadcrumb" className={`${styles.breadcrumb} ${styles.noPrint}`}>
      <ol>
        {items.map((item, index) => (
          <li key={item.label}>
            {index === items.length - 1 || !item.to
              ? <span aria-current={index === items.length - 1 ? 'page' : undefined}>{item.label}</span>
              : <Link to={item.to}>{item.label}</Link>}
          </li>
        ))}
      </ol>
    </nav>
  )
}

/** Card title with its icon, and optional content on the right (badge or chip). */
export function CardHeading({ id, icon, title, aside }) {
  return (
    <div className={styles.cardHeader}>
      <h2 id={id} className={styles.cardTitle}>
        {icon && <span className={styles.cardIcon}><DisputeIcon name={icon} /></span>}
        {title}
      </h2>
      {aside}
    </div>
  )
}

/** `overdue` shows "Response overdue" instead of "Awaiting ... response" once the deadline has passed. */
export function StatusBadge({ status, viewerIsResponder, responderRole, overdue = false }) {
  if (overdue && status === DISPUTE_STATUS.AWAITING_RESPONSE) {
    return <span className={`${styles.badge} ${styles['tone-danger']}`}>Response overdue</span>
  }
  const badge = getStatusBadge(status, { viewerIsResponder, responderRole })
  return <span className={`${styles.badge} ${styles[`tone-${badge.tone}`]}`}>{badge.label}</span>
}

/**
 * Yellow deadline banner while the dispute waits for a response; a red one when the deadline has passed.
 * `waitingText` is the lead sentence for this viewer, e.g. "Please respond within 72 hours."
 */
export function DeadlineBanner({ dispute, now, waitingText, showStage = false }) {
  const stage = getStatusBadge(dispute.status).stage
  const stageLabel = showStage && stage ? <span className={styles.stage}>Stage {stage} of {TOTAL_STAGES}</span> : null

  if (dispute.status !== DISPUTE_STATUS.AWAITING_RESPONSE) {
    if (!stageLabel) {
      return null
    }
    const text = dispute.status === DISPUTE_STATUS.UNDER_REVIEW
      ? 'Both sides have been received. An admin is reviewing this dispute.'
      : 'This dispute has been decided or closed.'
    return (
      <div className={`${styles.banner} ${styles.bannerInfo}`}>
        <span className={styles.bannerText}><DisputeIcon name="steps" />{text}</span>
        {stageLabel}
      </div>
    )
  }

  const timeLeft = getTimeLeft(dispute.responseDueAt, now)
  const closed = timeLeft?.overdue
  return (
    <div className={`${styles.banner} ${closed ? styles.bannerClosed : ''}`}>
      <span className={styles.bannerText} aria-live="polite">
        <DisputeIcon name="clock" />
        <span>
          {closed
            ? 'The response window has closed.'
            : <>{waitingText} <strong>{formatTimeLeft(timeLeft)}.</strong></>}
          {dispute.responseDueAt && (
            <span className={styles.bannerDue}> Due {formatDateTime(dispute.responseDueAt)}.</span>
          )}
        </span>
      </span>
      {stageLabel}
    </div>
  )
}

/** Only fields the backend returns (it has no service, provider name, date window or amount yet). */
export function BookingCard({ dispute, title = 'Booking details' }) {
  return (
    <section className={styles.card} aria-labelledby="dispute-booking-title">
      <CardHeading
        id="dispute-booking-title"
        icon="booking"
        title={title}
        aside={<span className={styles.chip}>{shortReference('BK', dispute.bookingId)}</span>}
      />
      <dl className={styles.fields}>
        <div>
          <dt>Raised by</dt>
          <dd>{roleLabel(dispute.raisedByRole)}</dd>
        </div>
        <div>
          <dt>Raised on</dt>
          <dd>{formatDateTime(dispute.createdAt)}</dd>
        </div>
        <div>
          <dt>Response due</dt>
          <dd>{formatDateTime(dispute.responseDueAt) || '—'}</dd>
        </div>
      </dl>
    </section>
  )
}

const IMAGE_EXTENSION = /\.(jpe?g|png|gif|webp|heic)$/i

export function EvidenceList({ evidence }) {
  if (!Array.isArray(evidence) || evidence.length === 0) {
    return <p className={styles.muted}>No evidence attached.</p>
  }
  return (
    <ul className={styles.evidenceList}>
      {evidence.map((reference, index) => {
        const href = safeEvidenceHref(reference)
        const name = evidenceName(reference)
        return (
          <li key={`${index}-${name}`} className={styles.evidenceItem}>
            <span className={styles.evidenceIcon}>
              <DisputeIcon name={IMAGE_EXTENSION.test(name) ? 'image' : 'file'} size={22} />
            </span>
            {href
              ? <a href={href} target="_blank" rel="noopener noreferrer" className={styles.evidenceName}>{name}</a>
              : <span className={styles.evidenceName}>{name}</span>}
          </li>
        )
      })}
    </ul>
  )
}

export function IssueCard({ dispute, title }) {
  const count = Array.isArray(dispute.evidence) ? dispute.evidence.length : 0
  return (
    <section className={styles.card} aria-labelledby="dispute-issue-title">
      <CardHeading
        id="dispute-issue-title"
        icon="issue"
        title={title}
        aside={<span className={`${styles.badge} ${styles['tone-danger']}`}>{categoryLabel(dispute.category)}</span>}
      />
      <p className={styles.meta}>Raised on {formatDateTime(dispute.createdAt)}</p>
      <div className={styles.quote}>
        <p className={styles.text}>{dispute.description}</p>
      </div>
      <h3 className={styles.subTitle}>Attached evidence{count > 0 && ` (${count})`}</h3>
      <EvidenceList evidence={dispute.evidence} />
    </section>
  )
}

const TIMELINE_TITLES = Object.freeze({
  AWAITING_RESPONSE: 'Issue raised',
  OPEN: 'Issue raised',
  UNDER_REVIEW: 'Response submitted, under review',
  RESOLVED: 'Decision made',
  RESOLVED_FINAL: 'Decision final',
  CLOSED: 'Dispute closed',
})

/** Built from the embedded status history, plus a "waiting" step while a response is due. */
export function DisputeTimeline({ dispute, waitingTitle, overdue = false }) {
  const history = Array.isArray(dispute.statusHistory) ? dispute.statusHistory : []
  return (
    <section className={styles.card} aria-labelledby="dispute-timeline-title">
      <CardHeading id="dispute-timeline-title" icon="timeline" title="Dispute timeline" />
      <ol className={styles.timeline}>
        {history.map((entry, index) => (
          <li key={`${index}-${entry.timestamp}`} className={styles.timelineItem}>
            <span className={styles.dot} aria-hidden="true" />
            <p className={styles.timelineTitle}>{TIMELINE_TITLES[entry.toStatus] ?? 'Status changed'}</p>
            <p className={styles.muted}>
              {formatDateTime(entry.timestamp)} · by {roleLabel(entry.actorRole)}
            </p>
            {entry.reason && <p className={`${styles.text} ${styles.muted}`}>{entry.reason}</p>}
          </li>
        ))}
        {dispute.status === DISPUTE_STATUS.AWAITING_RESPONSE && waitingTitle && (
          <li className={styles.timelineItem}>
            <span className={`${styles.dot} ${overdue ? styles.dotOverdue : styles.dotPending}`} aria-hidden="true" />
            <p className={`${styles.timelineTitle} ${overdue ? styles.timelineOverdue : styles.timelinePending}`}>
              {overdue ? 'No response before the deadline' : waitingTitle}
            </p>
            <p className={styles.muted}>
              {overdue ? 'Response window closed' : 'Current status · response window active'}
            </p>
          </li>
        )}
      </ol>
    </section>
  )
}

const NEXT_STEPS = Object.freeze([
  { title: 'The provider responds', text: 'The provider reviews the issue and submits their side within 72 hours.' },
  { title: 'An admin reviews both sides', text: 'A resolution specialist reviews the issue, the response and the evidence.' },
  { title: 'You are told the decision', text: 'You are notified of the outcome, any refund, or next steps.' },
])

/** Right-column guide for the customer, highlighting the current stage. */
export function WhatHappensNext({ status }) {
  const stage = getStatusBadge(status).stage
  return (
    <section className={styles.card} aria-labelledby="dispute-next-title">
      <CardHeading id="dispute-next-title" icon="steps" title="What happens next" />
      <ol className={styles.steps}>
        {NEXT_STEPS.map((step, index) => {
          const number = index + 1
          const state = stage > number ? styles.stepDone : stage === number ? styles.stepActive : ''
          return (
            <li key={step.title} className={`${styles.step} ${state}`} aria-current={stage === number ? 'step' : undefined}>
              <span className={styles.stepNumber} aria-hidden="true">{number}</span>
              <div>
                <p className={styles.stepTitle}>{step.title}</p>
                <p className={styles.muted}>{step.text}</p>
              </div>
            </li>
          )
        })}
      </ol>
      <div className={styles.note}>
        <span className={styles.noteIcon}><DisputeIcon name="shield" /></span>
        <div>
          <strong>Funds protected</strong>
          The provider payout for this booking is held until the dispute is resolved.
        </div>
      </div>
    </section>
  )
}

/** The other party's (or your own) submitted response, read-only. */
export function ResponseView({ response, title = 'Response' }) {
  if (!response) {
    return null
  }
  return (
    <section className={styles.card} aria-labelledby="dispute-response-title">
      <CardHeading id="dispute-response-title" icon="response" title={title} />
      <p className={styles.meta}>Submitted on {formatDateTime(response.respondedAt)}</p>
      <div className={styles.quote}>
        <p className={styles.text}>{response.text}</p>
      </div>
      {Array.isArray(response.evidence) && response.evidence.length > 0 && (
        <>
          <h3 className={styles.subTitle}>Evidence</h3>
          <EvidenceList evidence={response.evidence} />
        </>
      )}
    </section>
  )
}
