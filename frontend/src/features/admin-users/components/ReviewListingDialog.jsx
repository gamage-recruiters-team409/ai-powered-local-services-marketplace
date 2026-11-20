import { useEffect, useId, useRef, useState } from 'react'

import { Skeleton, useToast } from '../../../shared/components/feedback'
import { Button } from '../../../shared/components/forms'
import { AnnouncementDialog } from '../../admin-announcements/components/AnnouncementDialog'
import { LISTING_DECISION } from '../config/usersConfig'
import { getUserListing, isRequestCancelled, reviewUserListing } from '../services/adminUsersService'
import { isContactReason, splitContacts } from '../utils/contactHighlight'
import { parseApiError } from '../utils/errors'
import { formatDate, formatLkr } from '../utils/format'
import { noteError } from '../utils/validation'
import { NoteField } from './NoteField'
import { Unavailable } from './Panel'
import { ListingStatusChip } from './UserChips'
import { UsersIcon } from './UsersIcon'
import styles from '../adminUsers.module.css'

const FLAG_LABELS = Object.freeze({
  CONTACT_DETAILS: 'Contains contact details',
  PHONE_NUMBER: 'Contains a phone number',
  EMAIL_ADDRESS: 'Contains an email address',
  EXTERNAL_LINK: 'Contains an external link',
})

function flagText(flags) {
  return (flags ?? []).map((f) => FLAG_LABELS[f] ?? String(f).replaceAll('_', ' ').toLowerCase()).join(', ')
}

/** Listing text with contact details marked; plain React text only (no HTML is ever rendered from the listing). */
function HighlightedText({ text, highlight }) {
  if (!highlight) return <p className={styles.listingText}>{text}</p>
  return (
    <p className={styles.listingText}>
      {splitContacts(text).map((segment, i) =>
        segment.contact ? (
          <mark key={i} className={styles.contactMark}>
            {segment.text}
          </mark>
        ) : (
          <span key={i}>{segment.text}</span>
        ),
      )}
    </p>
  )
}

/**
 * canReview: the backend says decisions are possible (ADMIN, and the listing service supports them).
 * readOnlyReason when not: 'unavailable' (an ADMIN, but listing decisions are not connected yet) or 'viewOnly'
 * (SUPER_ADMIN). The listing itself is always shown.
 */
function ReviewForm({ userId, providerName, listing: summary, canReview, readOnlyReason = 'viewOnly', onClose, onDone }) {
  const toast = useToast()
  const descriptionId = useId()
  const [result, setResult] = useState({ loading: true, section: null, error: null })
  const [note, setNote] = useState('')
  const [noteErr, setNoteErr] = useState(null)
  const [serverError, setServerError] = useState(null)
  const [saving, setSaving] = useState(null)
  const inFlight = useRef(false)

  useEffect(() => {
    const controller = new AbortController()
    getUserListing(userId, summary.id, controller.signal)
      .then((section) => setResult({ loading: false, section, error: null }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setResult({ loading: false, section: null, error: parseApiError(error) })
      })
    return () => controller.abort()
  }, [userId, summary.id])

  const detail = result.section?.available ? result.section.data : null
  const title = detail?.title ?? summary.title
  const status = detail?.status ?? summary.status
  const flags = detail?.moderationFlags ?? summary.moderationFlags ?? []
  const reason = detail?.hiddenReason || flagText(flags)
  const highlight = isContactReason(detail?.hiddenReason, flags)

  const decide = async (decision) => {
    if (inFlight.current) return
    const found = noteError(note, { required: true })
    setNoteErr(found)
    if (found) return
    inFlight.current = true
    setSaving(decision)
    setServerError(null)
    try {
      await reviewUserListing(userId, summary.id, { decision, note: note.trim() })
      toast.success(decision === LISTING_DECISION.MAKE_VISIBLE ? `"${title}" is visible again.` : `"${title}" stays hidden.`)
      onDone()
    } catch (error) {
      setServerError(parseApiError(error))
    } finally {
      inFlight.current = false
      setSaving(null)
    }
  }

  const busy = saving !== null
  const footer = canReview ? (
    <>
      <Button variant="secondary" size="small" onClick={onClose} disabled={busy}>
        Cancel
      </Button>
      <Button
        variant="outline"
        size="small"
        onClick={() => decide(LISTING_DECISION.KEEP_HIDDEN)}
        loading={saving === LISTING_DECISION.KEEP_HIDDEN}
        disabled={busy}
      >
        Keep hidden
      </Button>
      <Button
        size="small"
        onClick={() => decide(LISTING_DECISION.MAKE_VISIBLE)}
        loading={saving === LISTING_DECISION.MAKE_VISIBLE}
        disabled={busy}
      >
        Make visible
      </Button>
    </>
  ) : (
    <Button variant="secondary" size="small" onClick={onClose}>
      Close
    </Button>
  )

  return (
    <AnnouncementDialog
      open
      title="Review listing"
      icon="eye"
      busy={busy}
      onClose={onClose}
      describedBy={descriptionId}
      footer={footer}
    >
      <div className={styles.listingHead}>
        {detail?.imageUrl ? (
          <img src={detail.imageUrl} alt="" className={styles.listingImage} />
        ) : (
          <span className={styles.listingImagePlaceholder} aria-hidden="true">
            <UsersIcon name="image" size={22} />
          </span>
        )}
        <div className={styles.listingMeta}>
          <div className={styles.listingTitleRow}>
            <h3 className={styles.listingTitle}>{title}</h3>
            <ListingStatusChip status={status} />
          </div>
          <dl className={styles.listingFacts}>
            <div>
              <dt>Provider</dt>
              <dd>{providerName}</dd>
            </div>
            {detail?.categoryName && (
              <div>
                <dt>Category</dt>
                <dd>{detail.categoryName}</dd>
              </div>
            )}
            {detail?.basePrice !== undefined && detail?.basePrice !== null && (
              <div>
                <dt>Price</dt>
                <dd>{formatLkr(detail.basePrice)}</dd>
              </div>
            )}
            {detail?.hiddenSince && (
              <div>
                <dt>Hidden since</dt>
                <dd>{formatDate(detail.hiddenSince)}</dd>
              </div>
            )}
          </dl>
        </div>
      </div>

      {reason && (
        <div className={styles.reasonBox} role="note">
          <UsersIcon name="alertTriangle" size={15} />
          <div>
            <strong>Reason hidden</strong>
            <p>{reason}</p>
          </div>
        </div>
      )}

      <div id={descriptionId} className={styles.descriptionBox}>
        <h4>Listing description</h4>
        {result.loading ? (
          <div aria-hidden="true">
            <Skeleton height={14} />
            <Skeleton height={14} width="70%" />
          </div>
        ) : result.error ? (
          <p className={styles.dialogError} role="alert">
            {result.error.message}
          </p>
        ) : detail ? (
          <HighlightedText text={detail.description ?? ''} highlight={highlight} />
        ) : (
          <Unavailable what="Listing details" service="the listing service" reason={result.section?.reason} />
        )}
      </div>

      {serverError && (
        <p className={styles.dialogError} role="alert">
          <UsersIcon name="alertTriangle" size={15} />
          {serverError.message}
        </p>
      )}

      {canReview ? (
        <NoteField
          label="Admin note"
          value={note}
          onChange={(value) => {
            setNote(value)
            setNoteErr(null)
          }}
          error={noteErr}
          required
          disabled={busy}
          placeholder="Add administrative justification, policy decision notes, or instructions for the provider..."
          hint="Required for either decision."
        />
      ) : (
        <p className={styles.readOnlyNote}>
          <UsersIcon name={readOnlyReason === 'unavailable' ? 'info' : 'lock'} size={13} />
          {readOnlyReason === 'unavailable'
            ? 'Listing decisions are not available yet — the listing service is not connected. You can review the listing details.'
            : 'View only. Listing decisions are made by admins.'}
        </p>
      )}
    </AnnouncementDialog>
  )
}

/** Modal C. Mounted only while open, so each listing starts with a fresh form. */
export function ReviewListingDialog({ open, ...props }) {
  return open ? <ReviewForm {...props} /> : null
}
