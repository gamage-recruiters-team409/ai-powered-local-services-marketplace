import { useId, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { InlineMessage } from '../../../shared/components/feedback'
import { Button, Textarea } from '../../../shared/components/forms'
import { LOGIN_PATH } from '../../disputes-admin/services/authBridge'
import { submitDisputeResponse } from '../services/disputesApi'
import { parseApiError } from '../utils/disputeErrors'
import { MAX_RESPONSE_LENGTH, validateResponseText } from '../utils/responseValidation'
import styles from '../disputes.module.css'

/**
 * "Your response" form. Disabled with a "Response window closed" message when `canRespond` is false.
 * Sends only the text: there is no evidence upload endpoint yet.
 */
export function ProviderResponseForm({ disputeId, canRespond, closedMessage, onSubmitted, onWindowClosed }) {
  const navigate = useNavigate()
  const location = useLocation()
  const titleId = useId()
  const [text, setText] = useState('')
  const [fieldError, setFieldError] = useState(null)
  const [formError, setFormError] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const inFlight = useRef(false)

  if (!canRespond) {
    return (
      <section className={styles.card} aria-labelledby={titleId}>
        <h2 id={titleId} className={`${styles.cardTitle} ${styles.cardHeader}`}>Your response</h2>
        <InlineMessage variant="info">{closedMessage}</InlineMessage>
      </section>
    )
  }

  const length = text.trim().length

  const handleSubmit = async (event) => {
    event.preventDefault()
    if (inFlight.current) {
      return
    }
    const problem = validateResponseText(text)
    setFieldError(problem)
    setFormError(null)
    if (problem) {
      return
    }

    inFlight.current = true
    setSubmitting(true)
    try {
      const updated = await submitDisputeResponse(disputeId, text.trim())
      onSubmitted(updated)
    } catch (error) {
      const parsed = parseApiError(error)
      if (parsed.kind === 'unauthorized') {
        navigate(LOGIN_PATH, { replace: true, state: { from: `${location.pathname}${location.search}` } })
        return
      }
      if (parsed.kind === 'validation' && parsed.fieldErrors.text) {
        setFieldError(parsed.fieldErrors.text)
      } else {
        setFormError(parsed.message)
      }
      if (parsed.kind === 'window-closed') {
        onWindowClosed?.()
      }
    } finally {
      inFlight.current = false
      setSubmitting(false)
    }
  }

  return (
    <section className={styles.card} aria-labelledby={titleId}>
      <h2 id={titleId} className={`${styles.cardTitle} ${styles.cardHeader}`}>Your response</h2>
      <form className={styles.form} onSubmit={handleSubmit} noValidate aria-busy={submitting}>
        <Textarea
          label="Tell your side"
          required
          rows={7}
          value={text}
          maxLength={MAX_RESPONSE_LENGTH}
          disabled={submitting}
          placeholder="Explain what happened, steps taken during the service, and any relevant context…"
          error={fieldError ?? undefined}
          onChange={(event) => {
            setText(event.target.value)
            if (fieldError) {
              setFieldError(null)
            }
          }}
        />
        <p className={`${styles.counter} ${length > MAX_RESPONSE_LENGTH ? styles.counterOver : ''}`} aria-live="polite">
          {length} / {MAX_RESPONSE_LENGTH}
        </p>

        <div>
          <p className={styles.fieldLabel}>Add evidence (optional)</p>
          <div className={styles.dropzone} aria-disabled="true">
            <span aria-hidden="true">⇪</span>
            <span>Evidence upload coming soon</span>
            <span>For now, describe any photos or documents in your response.</span>
          </div>
        </div>

        <div aria-live="assertive">
          {formError && <InlineMessage variant="error">{formError}</InlineMessage>}
        </div>

        <div className={styles.actions}>
          <Button variant="secondary" onClick={() => navigate(-1)} disabled={submitting}>
            Cancel
          </Button>
          <Button type="submit" loading={submitting} disabled={submitting}>
            {submitting ? 'Submitting…' : 'Submit response'}
          </Button>
        </div>
      </form>
    </section>
  )
}
