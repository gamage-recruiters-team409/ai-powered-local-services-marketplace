import { useId, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { InlineMessage } from '../../../shared/components/feedback'
import { Button, Textarea } from '../../../shared/components/forms'
import { LOGIN_PATH } from '../../disputes-admin/services/authBridge'
import { submitDisputeResponse } from '../services/disputesApi'
import { parseApiError } from '../utils/disputeErrors'
import { MAX_RESPONSE_LENGTH, validateResponseText } from '../utils/responseValidation'
import { CardHeading } from './DisputeParts'
import { EvidencePicker } from './EvidencePicker'
import { pendingUploads, uploadPickedFiles } from '../utils/evidenceUpload'
import styles from '../disputes.module.css'

/**
 * "Your response" form for whichever party must respond (the provider, or the customer when the provider raised
 * the dispute). Shared by both dispute screens. Disabled with a "Response window closed" message when `canRespond`
 * is false. Chosen evidence files are uploaded right after the response is saved (the dispute must exist in its
 * responded state first); `onSubmitted(updated, { failedUploads })` reports files that could not be uploaded.
 */
export function DisputeResponseForm({ disputeId, canRespond, closedMessage, onSubmitted, onWindowClosed }) {
  const navigate = useNavigate()
  const location = useLocation()
  const titleId = useId()
  const [text, setText] = useState('')
  const [fieldError, setFieldError] = useState(null)
  const [formError, setFormError] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const [files, setFiles] = useState([])
  const inFlight = useRef(false)

  if (!canRespond) {
    return (
      <section className={styles.card} aria-labelledby={titleId}>
        <CardHeading id={titleId} icon="response" title="Your response" />
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
      const toUpload = pendingUploads(files).length
      const uploaded = toUpload > 0 ? await uploadPickedFiles(disputeId, files, setFiles) : []
      const evidenceFiles = [...(updated.evidenceFiles ?? []), ...uploaded]
      onSubmitted({ ...updated, evidenceFiles }, { failedUploads: toUpload - uploaded.length })
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
      <CardHeading id={titleId} icon="response" title="Your response" />
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

        <EvidencePicker files={files} onChange={setFiles} disabled={submitting} />

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
