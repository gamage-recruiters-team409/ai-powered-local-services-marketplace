import { useId, useState } from 'react'

import { ConfirmDialog, InlineMessage } from '../../../shared/components/feedback'
import { Button, Textarea } from '../../../shared/components/forms'
import { ACTIONS, decisionText, LIMITS, REFUND_NOTE } from '../config/adminDisputeConfig'
import { parseAdminError } from '../utils/adminDisputeErrors'
import styles from '../adminDisputes.module.css'

const ACTION_ORDER = ['REFUND_FULL', 'REFUND_PARTIAL', 'RELEASE_TO_PROVIDER']
const ACTION_HINTS = Object.freeze({
  REFUND_FULL: 'The customer gets everything back. The booking ends as refunded.',
  REFUND_PARTIAL: 'The customer gets part back (1–99%), the provider the rest.',
  RELEASE_TO_PROVIDER: 'The provider is paid in full. The booking ends as completed.',
})

function isRefund(action) {
  return action === 'REFUND_FULL' || action === 'REFUND_PARTIAL'
}

/**
 * Admin decision fields with the backend's rules: a partial refund needs 1–99%; the 30% provider penalty is only
 * possible together with a refund; the internal note is required (at most 1000 characters). A confirm step shows the
 * decision before it is sent.
 *
 * mode 'resolve': the case decision. mode 'appeal': UPHOLD (note only) or CHANGE (same decision fields).
 * `onSubmit(body)` returns a promise; its rejection (an API error) is shown here.
 */
export function DecisionForm({ mode = 'resolve', onSubmit, submitLabel }) {
  const id = useId()
  const [verdict, setVerdict] = useState(mode === 'appeal' ? '' : 'CHANGE')
  const [action, setAction] = useState('')
  const [percent, setPercent] = useState('')
  const [penalty, setPenalty] = useState(false)
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState({})
  const [formError, setFormError] = useState(null)
  const [confirming, setConfirming] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const decides = mode === 'resolve' || verdict === 'CHANGE'

  function chooseAction(value) {
    setAction(value)
    if (!isRefund(value)) {
      setPenalty(false)
    }
    if (value !== 'REFUND_PARTIAL') {
      setPercent('')
    }
    setErrors((current) => ({ ...current, action: undefined, percent: undefined }))
  }

  function body() {
    if (mode === 'appeal' && verdict === 'UPHOLD') {
      return { verdict: 'UPHOLD', note: note.trim() }
    }
    const decision = {
      action,
      customerRefundPercent: action === 'REFUND_PARTIAL' ? Number(percent) : null,
      applyProviderPenalty: isRefund(action) ? penalty : false,
      note: note.trim(),
    }
    return mode === 'appeal' ? { verdict: 'CHANGE', ...decision } : decision
  }

  function validate() {
    const next = {}
    if (mode === 'appeal' && !verdict) next.verdict = 'Choose Uphold or Change.'
    if (decides && !action) next.action = 'Choose a decision.'
    if (decides && action === 'REFUND_PARTIAL') {
      const value = Number(percent)
      if (!/^\d+$/.test(String(percent)) || value < LIMITS.PARTIAL_MIN || value > LIMITS.PARTIAL_MAX) {
        next.percent = `Enter a whole number from ${LIMITS.PARTIAL_MIN} to ${LIMITS.PARTIAL_MAX}.`
      }
    }
    const trimmed = note.trim()
    if (!trimmed) next.note = 'Add an internal note explaining the decision.'
    else if (trimmed.length > LIMITS.NOTE_MAX) next.note = `Keep the note to ${LIMITS.NOTE_MAX} characters or fewer.`
    return next
  }

  function requestSubmit(event) {
    event.preventDefault()
    const next = validate()
    setErrors(next)
    setFormError(null)
    if (Object.keys(next).length === 0) {
      setConfirming(true)
    }
  }

  async function confirm() {
    setSubmitting(true)
    try {
      await onSubmit(body())
      setConfirming(false)
    } catch (error) {
      const parsed = parseAdminError(error)
      setConfirming(false)
      setFormError(parsed.message)
      setErrors({
        action: parsed.fieldErrors.action,
        percent: parsed.fieldErrors.customerRefundPercent,
        note: parsed.fieldErrors.note,
        verdict: parsed.fieldErrors.verdict,
      })
    } finally {
      setSubmitting(false)
    }
  }

  const summary = mode === 'appeal' && verdict === 'UPHOLD'
    ? 'Uphold the original decision. The dispute becomes final.'
    : `${decisionText({ action, customerRefundPercent: Number(percent),
      providerPenaltyApplied: penalty, providerPenaltyPercent: LIMITS.PENALTY_PERCENT })}.${
      mode === 'appeal' ? ' This replaces the original decision and is final.' : ' The parties can appeal once within 48 hours.'}`

  return (
    <form className={styles.decisionForm} onSubmit={requestSubmit} noValidate aria-busy={submitting}>
      {mode === 'appeal' && (
        <fieldset className={styles.choiceGroup}>
          <legend className={styles.fieldLabel}>Verdict <span aria-hidden="true">*</span></legend>
          {[['UPHOLD', 'Uphold', 'Keep the original decision.'], ['CHANGE', 'Change', 'Replace it with a new decision.']]
            .map(([value, label, hint]) => (
              <label key={value} className={`${styles.choice} ${verdict === value ? styles.choiceActive : ''}`}>
                <input type="radio" name={`${id}-verdict`} value={value} checked={verdict === value} disabled={submitting}
                  onChange={() => { setVerdict(value); setErrors((c) => ({ ...c, verdict: undefined })) }} />
                <span><strong>{label}</strong><span className={styles.muted}>{hint}</span></span>
              </label>
            ))}
          {errors.verdict && <span className={styles.fieldError} role="alert">{errors.verdict}</span>}
        </fieldset>
      )}

      {decides && (
        <fieldset className={styles.choiceGroup}>
          <legend className={styles.fieldLabel}>Decision <span aria-hidden="true">*</span></legend>
          {ACTION_ORDER.map((value) => (
            <label key={value} className={`${styles.choice} ${action === value ? styles.choiceActive : ''}`}>
              <input type="radio" name={`${id}-action`} value={value} checked={action === value} disabled={submitting}
                onChange={() => chooseAction(value)} />
              <span><strong>{ACTIONS[value]}</strong><span className={styles.muted}>{ACTION_HINTS[value]}</span></span>
            </label>
          ))}
          {errors.action && <span className={styles.fieldError} role="alert">{errors.action}</span>}
          <p className={styles.refundNote}>{REFUND_NOTE}</p>
        </fieldset>
      )}

      {decides && action === 'REFUND_PARTIAL' && (
        <div className={styles.field}>
          <label htmlFor={`${id}-percent`} className={styles.fieldLabel}>Refund to the customer (%)</label>
          <input
            id={`${id}-percent`}
            className={styles.numberInput}
            type="number"
            inputMode="numeric"
            min={LIMITS.PARTIAL_MIN}
            max={LIMITS.PARTIAL_MAX}
            step={1}
            value={percent}
            disabled={submitting}
            aria-invalid={Boolean(errors.percent)}
            aria-describedby={errors.percent ? `${id}-percent-error` : undefined}
            onChange={(event) => { setPercent(event.target.value); setErrors((c) => ({ ...c, percent: undefined })) }}
          />
          {errors.percent && <span id={`${id}-percent-error`} className={styles.fieldError} role="alert">{errors.percent}</span>}
        </div>
      )}

      {decides && (
        <label className={`${styles.checkbox} ${isRefund(action) ? '' : styles.checkboxDisabled}`}>
          <input type="checkbox" checked={penalty} disabled={submitting || !isRefund(action)}
            onChange={(event) => setPenalty(event.target.checked)} />
          <span>
            Apply the {LIMITS.PENALTY_PERCENT}% provider penalty
            <span className={styles.muted}>{isRefund(action) ? ' Only together with a refund.' : ' Available only with a refund.'}</span>
          </span>
        </label>
      )}

      <div className={styles.field}>
        <Textarea
          label="Internal note"
          required
          rows={4}
          value={note}
          maxLength={LIMITS.NOTE_MAX}
          disabled={submitting}
          helperText="Only admins see this note."
          error={errors.note ?? undefined}
          onChange={(event) => { setNote(event.target.value); setErrors((c) => ({ ...c, note: undefined })) }}
        />
        <span className={styles.counter}>{note.trim().length} / {LIMITS.NOTE_MAX}</span>
      </div>

      <div aria-live="assertive">{formError && <InlineMessage variant="error">{formError}</InlineMessage>}</div>

      <div className={styles.formActions}>
        <Button type="submit" loading={submitting} disabled={submitting}>
          {submitLabel ?? (mode === 'appeal' ? 'Submit appeal decision' : 'Submit decision')}
        </Button>
      </div>

      <ConfirmDialog
        open={confirming}
        title={mode === 'appeal' ? 'Confirm the appeal decision' : 'Confirm the decision'}
        message={summary}
        confirmLabel="Confirm"
        loading={submitting}
        onConfirm={confirm}
        onCancel={() => setConfirming(false)}
      />
    </form>
  )
}
