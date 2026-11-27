import { useId, useRef, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom'

import { ConfirmDialog, InlineMessage } from '../../../shared/components/feedback'
import { Button, Textarea } from '../../../shared/components/forms'
import { LOGIN_PATH } from '../../disputes-admin/services/authBridge'
import { CardHeading, Breadcrumb } from '../components/DisputeParts'
import { DisputeIcon } from '../components/DisputeIcon'
import { EvidencePicker } from '../components/EvidencePicker'
import { pendingUploads, uploadPickedFiles } from '../utils/evidenceUpload'
import { categoryLabel, DISPUTE_LIMITS, PROVIDER_RAISE_CATEGORIES } from '../config/disputeStatus'
import { fetchMyDisputes, raiseDispute } from '../services/disputesApi'
import { parseApiError } from '../utils/disputeErrors'
import { shortReference } from '../utils/format'
import styles from '../disputes.module.css'

const MY_DISPUTES_PATH = '/provider/disputes'
const BOOKING_ID = /^[A-Za-z0-9_-]{1,64}$/

const CATEGORY_HINTS = Object.freeze({
  PROPERTY_DAMAGE: 'Your equipment or property was damaged during the booking.',
  CUSTOMER_BEHAVIOUR: 'The customer behaved in an abusive, unsafe or unacceptable way.',
  NON_PAYMENT: 'The customer did not pay what was agreed.',
  OTHER: 'Something else went wrong with this booking.',
})

/**
 * Provider "Raise a dispute" (/provider/disputes/new?bookingId=...). The booking comes from the link on the
 * provider's booking screen; without it the page explains where to start. Evidence files are uploaded right after the
 * dispute is created, then the provider lands on the new dispute. No-show is reported from the booking (WP08).
 */
export function ProviderRaiseDisputePage() {
  const [params] = useSearchParams()
  const bookingId = params.get('bookingId') ?? ''

  if (!BOOKING_ID.test(bookingId)) {
    return (
      <div className={styles.page}>
        <Breadcrumb items={[{ label: 'Dashboard', to: '/provider' }, { label: 'My Disputes', to: MY_DISPUTES_PATH },
          { label: 'Raise a dispute' }]}
        />
        <h1 className={styles.title}>Raise a dispute</h1>
        <section className={styles.card}>
          <InlineMessage variant="info">
            Open this page from the booking you want to dispute (Bookings, then the booking, then "Raise dispute"), so we
            know which booking it is about.
          </InlineMessage>
          <div className={styles.actions}>
            <Link to={MY_DISPUTES_PATH} className={styles.primaryLink}>Back to My Disputes</Link>
          </div>
        </section>
      </div>
    )
  }
  return <RaiseForm bookingId={bookingId} />
}

function RaiseForm({ bookingId }) {
  const navigate = useNavigate()
  const location = useLocation()
  const groupId = useId()
  const [category, setCategory] = useState('')
  const [description, setDescription] = useState('')
  const [files, setFiles] = useState([])
  const [errors, setErrors] = useState({})
  const [formError, setFormError] = useState(null)
  const [existingId, setExistingId] = useState(undefined)
  const [confirming, setConfirming] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [unauthorized, setUnauthorized] = useState(false)
  const inFlight = useRef(false)
  const trimmed = description.trim()

  if (unauthorized) {
    return <Navigate to={LOGIN_PATH} replace state={{ from: `${location.pathname}${location.search}` }} />
  }

  function validate() {
    const next = {}
    if (!category) {
      next.category = 'Choose what the dispute is about.'
    }
    if (trimmed.length === 0) {
      next.description = 'Describe what happened.'
    } else if (trimmed.length < DISPUTE_LIMITS.DESCRIPTION_MIN) {
      next.description = `Write at least ${DISPUTE_LIMITS.DESCRIPTION_MIN} characters.`
    } else if (trimmed.length > DISPUTE_LIMITS.DESCRIPTION_MAX) {
      next.description = `Keep it to ${DISPUTE_LIMITS.DESCRIPTION_MAX} characters or fewer.`
    }
    return next
  }

  function requestSubmit(event) {
    event.preventDefault()
    const next = validate()
    setErrors(next)
    setFormError(null)
    setExistingId(undefined)
    if (Object.keys(next).length === 0) {
      setConfirming(true)
    }
  }

  async function findExisting() {
    try {
      const page = await fetchMyDisputes({ page: 0, size: 100 })
      return page.content?.find((dispute) => dispute.bookingId === bookingId)?.id ?? null
    } catch {
      return null
    }
  }

  async function submit() {
    if (inFlight.current) return
    inFlight.current = true
    setSubmitting(true)
    try {
      const created = await raiseDispute({ bookingId, category, description: trimmed })
      const toUpload = pendingUploads(files).length
      const uploaded = toUpload > 0 ? await uploadPickedFiles(created.id, files, setFiles) : []
      navigate(`${MY_DISPUTES_PATH}/${encodeURIComponent(created.id)}`, {
        state: { justRaised: true, failedUploads: toUpload - uploaded.length },
      })
    } catch (error) {
      const parsed = parseApiError(error)
      setConfirming(false)
      if (parsed.kind === 'unauthorized') {
        setUnauthorized(true)
        return
      }
      if (parsed.kind === 'validation') {
        setErrors({ category: parsed.fieldErrors.category, description: parsed.fieldErrors.description })
        setFormError(parsed.message)
      } else if (parsed.kind === 'already-exists') {
        setFormError(parsed.message)
        setExistingId(await findExisting())
      } else if (parsed.kind === 'forbidden') {
        setFormError('You can only raise a dispute for your own bookings.')
      } else {
        setFormError(parsed.message)
      }
    } finally {
      inFlight.current = false
      setSubmitting(false)
    }
  }

  return (
    <div className={styles.page}>
      <Breadcrumb items={[{ label: 'Dashboard', to: '/provider' }, { label: 'My Disputes', to: MY_DISPUTES_PATH },
        { label: 'Raise a dispute' }]}
      />
      <div className={styles.titleGroup}>
        <h1 className={styles.title}>Raise a dispute</h1>
      </div>
      <p className={styles.muted}>
        Tell us what went wrong. The customer gets 72 hours to respond, then an admin reviews both sides.
      </p>

      <div className={styles.grid}>
        <div className={styles.column}>
          <section className={styles.card} aria-labelledby="raise-form-title">
            <CardHeading id="raise-form-title" icon="issue" title="Dispute details" />
            <form className={styles.form} onSubmit={requestSubmit} noValidate aria-busy={submitting}>
              <div>
                <p className={styles.fieldLabel}>Booking</p>
                <p className={styles.bookingRef}>
                  <DisputeIcon name="booking" size={16} /> {shortReference('B', bookingId)}
                  <span className={styles.muted}> ({bookingId})</span>
                </p>
              </div>

              <fieldset className={styles.radioGroup} aria-describedby={errors.category ? `${groupId}-error` : undefined}>
                <legend className={styles.fieldLabel}>What is the dispute about? <span aria-hidden="true">*</span></legend>
                {PROVIDER_RAISE_CATEGORIES.map((value) => (
                  <label key={value} className={`${styles.radioOption} ${category === value ? styles.radioOptionActive : ''}`}>
                    <input
                      type="radio"
                      name={`${groupId}-category`}
                      value={value}
                      checked={category === value}
                      disabled={submitting}
                      onChange={() => {
                        setCategory(value)
                        setErrors((current) => ({ ...current, category: undefined }))
                      }}
                    />
                    <span>
                      <strong>{categoryLabel(value)}</strong>
                      <span className={styles.muted}>{CATEGORY_HINTS[value]}</span>
                    </span>
                  </label>
                ))}
                {errors.category && <span id={`${groupId}-error`} className={styles.pickedError} role="alert">{errors.category}</span>}
              </fieldset>

              <div>
                <Textarea
                  label="What happened?"
                  required
                  rows={6}
                  value={description}
                  maxLength={DISPUTE_LIMITS.DESCRIPTION_MAX}
                  disabled={submitting}
                  placeholder="Describe what happened, when, and what you would like the admin to look at…"
                  error={errors.description ?? undefined}
                  onChange={(event) => {
                    setDescription(event.target.value)
                    if (errors.description) setErrors((current) => ({ ...current, description: undefined }))
                  }}
                />
                <p className={styles.counter} aria-live="polite">{trimmed.length} / {DISPUTE_LIMITS.DESCRIPTION_MAX}</p>
              </div>

              <EvidencePicker files={files} onChange={setFiles} disabled={submitting} />

              <div aria-live="assertive">
                {formError && (
                  <InlineMessage variant="error">
                    {formError}
                    {existingId && (
                      <> <Link to={`${MY_DISPUTES_PATH}/${encodeURIComponent(existingId)}`}>View the existing dispute</Link></>
                    )}
                    {existingId === null && <> <Link to={MY_DISPUTES_PATH}>Go to My Disputes</Link></>}
                  </InlineMessage>
                )}
              </div>

              <div className={styles.actions}>
                <Button variant="secondary" onClick={() => navigate(-1)} disabled={submitting}>Cancel</Button>
                <Button type="submit" loading={submitting} disabled={submitting}>Submit dispute</Button>
              </div>
            </form>
          </section>
        </div>
        <div className={styles.column}>
          <section className={styles.card} aria-labelledby="raise-next-title">
            <CardHeading id="raise-next-title" icon="steps" title="What happens next" />
            <ol className={styles.plainSteps}>
              <li>The customer is notified and has 72 hours to respond.</li>
              <li>An admin reviews both sides and the evidence.</li>
              <li>Either side can appeal the decision once, within 48 hours.</li>
            </ol>
            <div className={styles.note}>
              <span className={styles.noteIcon}><DisputeIcon name="info" /></span>
              <div><strong>Payment</strong>Payment handling for this booking will follow the dispute outcome.</div>
            </div>
          </section>
        </div>
      </div>

      <ConfirmDialog
        open={confirming}
        title="Submit this dispute?"
        message="The customer will be asked to respond. You can add more evidence while the dispute is open."
        confirmLabel="Submit dispute"
        loading={submitting}
        onConfirm={submit}
        onCancel={() => setConfirming(false)}
      />
    </div>
  )
}
