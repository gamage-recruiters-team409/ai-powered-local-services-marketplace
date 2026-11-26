import { useId, useState } from 'react'

import { ConfirmDialog, useToast } from '../../../shared/components/feedback'
import { Button, Textarea } from '../../../shared/components/forms'
import { DISPUTE_LIMITS, DISPUTE_STATUS, PAYMENT_NOTE } from '../config/disputeStatus'
import { submitAppeal } from '../services/disputesApi'
import { formatTimeLeft, getTimeLeft } from '../utils/countdown'
import { parseApiError } from '../utils/disputeErrors'
import { formatDateTime } from '../utils/format'
import { outcomeText } from '../utils/outcome'
import { DisputeIcon } from './DisputeIcon'
import { CardHeading } from './DisputeParts'
import styles from '../disputes.module.css'

/**
 * The admin's decision as the parties see it, and everything about the appeal:
 * - RESOLVED, appeal still possible (backend `appealEligible` and the deadline not passed): an "Open appeal" card
 *   with a countdown, the reason field, a confirm step and POST /disputes/{id}/appeal.
 * - RESOLVED but the window passed or the appeal is used: a short note instead of the form.
 * - APPEALED: "Appeal under review".
 * - RESOLVED_FINAL: the final outcome ("Decision upheld" / "Decision changed") and that it is final.
 * Payment wording stays neutral: it never says a refund or payout happened.
 */
export function AppealSection({ dispute, now, viewerRole, onUpdated, onReload }) {
  const outcome = dispute.outcome
  if (!outcome) {
    return null
  }
  const isFinal = dispute.status === DISPUTE_STATUS.RESOLVED_FINAL
  const appealOpen = dispute.status === DISPUTE_STATUS.RESOLVED && dispute.appealEligible === true
    && getTimeLeft(dispute.appealDeadline, now)?.overdue === false

  return (
    <>
      <DecisionCard dispute={dispute} outcome={outcome} isFinal={isFinal} viewerRole={viewerRole} />
      {appealOpen && <AppealForm dispute={dispute} now={now} onUpdated={onUpdated} onReload={onReload} />}
      {dispute.status === DISPUTE_STATUS.RESOLVED && !appealOpen && (
        <div className={`${styles.banner} ${styles.bannerInfo}`} role="note">
          <span className={styles.bannerText}>
            <DisputeIcon name="lock" />
            {dispute.appeal
              ? 'An appeal has already been filed for this dispute.'
              : 'The appeal window for this dispute has closed. The decision will become final.'}
          </span>
        </div>
      )}
      {dispute.status === DISPUTE_STATUS.APPEALED && <AppealUnderReview dispute={dispute} viewerRole={viewerRole} />}
    </>
  )
}

function DecisionCard({ dispute, outcome, isFinal, viewerRole }) {
  let heading = 'Admin decision'
  if (isFinal) {
    if (dispute.appealStatus === 'UPHELD') heading = 'Decision upheld'
    else if (dispute.appealStatus === 'CHANGED') heading = 'Decision changed'
    else heading = 'Final decision'
  }
  const paymentNote = outcome.paymentNote ?? (isFinal ? null : PAYMENT_NOTE)
  return (
    <section className={styles.card} aria-labelledby="dispute-decision-title">
      <CardHeading
        id="dispute-decision-title"
        icon="shield"
        title={heading}
        aside={isFinal
          ? <span className={`${styles.badge} ${styles['tone-success']}`}>Final</span>
          : <span className={`${styles.badge} ${styles['tone-info']}`}>Appeal possible</span>}
      />
      <p className={styles.decisionText}>{outcomeText(outcome)}</p>
      {viewerRole === 'PROVIDER' && outcome.providerPenaltyApplied === true && (
        <p className={styles.meta}>A provider penalty applies to this decision.</p>
      )}
      <p className={styles.meta}>Decided on {formatDateTime(outcome.decidedAt)}</p>
      {isFinal && (
        <p className={styles.muted}>
          {dispute.appealStatus === 'CHANGED'
            ? 'The appeal was reviewed by a different admin and the decision was changed. This decision is final.'
            : dispute.appealStatus === 'UPHELD'
              ? 'The appeal was reviewed by a different admin and the decision was upheld. This decision is final.'
              : 'This decision is final.'}
        </p>
      )}
      {paymentNote && (
        <div className={styles.note}>
          <span className={styles.noteIcon}><DisputeIcon name="info" /></span>
          <div><strong>Payment</strong>{paymentNote}</div>
        </div>
      )}
    </section>
  )
}

function AppealUnderReview({ dispute, viewerRole }) {
  const appeal = dispute.appeal
  const byYou = appeal?.appealedByRole && appeal.appealedByRole === viewerRole
  return (
    <section className={styles.card} aria-labelledby="dispute-appeal-review-title">
      <CardHeading
        id="dispute-appeal-review-title"
        icon="clock"
        title="Appeal under review"
        aside={<span className={`${styles.badge} ${styles['tone-info']}`}>In review</span>}
      />
      <p className={styles.text}>
        {byYou ? 'You appealed this decision.' : 'The other party appealed this decision.'}{' '}
        A different admin is reviewing the case. You will see the result here.
      </p>
      {appeal?.filedAt && <p className={styles.meta}>Appeal filed on {formatDateTime(appeal.filedAt)}</p>}
      {appeal?.reviewDueAt && <p className={styles.meta}>Review expected by {formatDateTime(appeal.reviewDueAt)}</p>}
    </section>
  )
}

function AppealForm({ dispute, now, onUpdated, onReload }) {
  const toast = useToast()
  const fieldId = useId()
  const [grounds, setGrounds] = useState('')
  const [error, setError] = useState(null)
  const [confirming, setConfirming] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const trimmed = grounds.trim()
  const timeLeft = formatTimeLeft(getTimeLeft(dispute.appealDeadline, now))

  function validate() {
    if (trimmed.length === 0) return 'Explain why you are appealing.'
    if (trimmed.length < DISPUTE_LIMITS.APPEAL_GROUNDS_MIN) {
      return `Write at least ${DISPUTE_LIMITS.APPEAL_GROUNDS_MIN} characters.`
    }
    if (trimmed.length > DISPUTE_LIMITS.APPEAL_GROUNDS_MAX) {
      return `Keep it to ${DISPUTE_LIMITS.APPEAL_GROUNDS_MAX} characters or fewer.`
    }
    return null
  }

  function requestSubmit(event) {
    event.preventDefault()
    const problem = validate()
    setError(problem)
    if (!problem) {
      setConfirming(true)
    }
  }

  async function confirm() {
    setSubmitting(true)
    try {
      const updated = await submitAppeal(dispute.id, trimmed)
      setConfirming(false)
      toast.success('Your appeal was submitted. A different admin will review it.')
      onUpdated(updated)
    } catch (caught) {
      const parsed = parseApiError(caught)
      setConfirming(false)
      if (parsed.kind === 'validation') {
        setError(parsed.fieldErrors.grounds ?? parsed.message)
      } else {
        toast.error(parsed.message)
        if (parsed.kind === 'appeal-closed' || parsed.kind === 'appeal-used' || parsed.kind === 'conflict') {
          onReload?.()
        }
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className={`${styles.card} ${styles.appealCard}`} aria-labelledby="dispute-appeal-title">
      <CardHeading
        id="dispute-appeal-title"
        icon="steps"
        title="Open appeal"
        aside={timeLeft && <span className={`${styles.badge} ${styles['tone-warning']}`}>{timeLeft}</span>}
      />
      <p className={styles.text}>
        You can appeal until <strong>{formatDateTime(dispute.appealDeadline)}</strong>. Each dispute can be appealed
        once, and a different admin reviews it.
      </p>
      <form className={styles.form} onSubmit={requestSubmit} noValidate>
        <Textarea
          id={fieldId}
          label="Why are you appealing?"
          required
          rows={5}
          value={grounds}
          maxLength={DISPUTE_LIMITS.APPEAL_GROUNDS_MAX}
          disabled={submitting}
          placeholder="Explain what the decision got wrong and anything the admin should look at again..."
          error={error ?? undefined}
          onChange={(event) => {
            setGrounds(event.target.value)
            if (error) setError(null)
          }}
        />
        <p className={styles.counter} aria-live="polite">{trimmed.length} / {DISPUTE_LIMITS.APPEAL_GROUNDS_MAX}</p>
        <div className={styles.actions}>
          <Button type="submit" disabled={submitting}>Submit appeal</Button>
        </div>
      </form>
      <ConfirmDialog
        open={confirming}
        title="Submit your appeal?"
        message="You can appeal only once. A different admin will review the case and their decision is final."
        confirmLabel="Submit appeal"
        loading={submitting}
        onConfirm={confirm}
        onCancel={() => setConfirming(false)}
      />
    </section>
  )
}
