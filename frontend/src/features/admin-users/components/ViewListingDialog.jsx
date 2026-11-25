import { useEffect, useId, useState } from 'react'

import { Skeleton } from '../../../shared/components/feedback'
import { Button } from '../../../shared/components/forms'
import { AnnouncementDialog } from '../../admin-announcements/components/AnnouncementDialog'
import { getUserListing, isRequestCancelled } from '../services/adminUsersService'
import { isContactReason, splitContacts } from '../utils/contactHighlight'
import { parseApiError } from '../utils/errors'
import { formatDate, formatLkr } from '../utils/format'
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
 * Read-only for every role: facts, status, the reason when flagged, and the description. There is no note and no
 * decision here; approving or rejecting a flagged listing is done in the moderators' listing workflow.
 */
function ListingDetails({ userId, providerName, listing: summary, onClose }) {
  const descriptionId = useId()
  const [result, setResult] = useState({ loading: true, section: null, error: null })

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
  const flagged = status === 'FLAGGED' || status === 'REJECTED'
  const reason = flagged ? detail?.moderationReason || flagText(flags) : ''
  const highlight = isContactReason(detail?.moderationReason, flags)

  return (
    <AnnouncementDialog
      open
      title="View listing details"
      icon="eye"
      onClose={onClose}
      describedBy={descriptionId}
      footer={
        <Button variant="secondary" size="small" onClick={onClose}>
          Close
        </Button>
      }
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
            {detail?.lastUpdatedAt && (
              <div>
                <dt>Last updated</dt>
                <dd>{formatDate(detail.lastUpdatedAt)}</dd>
              </div>
            )}
            {flagged && detail?.moderatedAt && (
              <div>
                <dt>Flagged since</dt>
                <dd>{formatDate(detail.moderatedAt)}</dd>
              </div>
            )}
          </dl>
        </div>
      </div>

      {reason && (
        <div className={styles.reasonBox} role="note">
          <UsersIcon name="alertTriangle" size={15} />
          <div>
            <strong>Reason flagged</strong>
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

      <p className={styles.readOnlyNote}>
        <UsersIcon name="lock" size={13} />
        View only. Flagged listings are approved or rejected by moderators.
      </p>
    </AnnouncementDialog>
  )
}

/** Mounted only while open, so each listing loads fresh. */
export function ViewListingDialog({ open, ...props }) {
  return open ? <ListingDetails {...props} /> : null
}
