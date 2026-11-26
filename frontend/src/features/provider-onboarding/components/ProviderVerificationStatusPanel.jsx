
import { Button } from '../../../shared/components/forms'
import ProviderFeatureIcon from './ProviderFeatureIcon'
import {
  DOCUMENT_LABELS,
  displayDocuments,
  formatDocumentDate,
  isDocumentExpired,
} from '../services/providerVerificationUiUtils'
import styles from '../providerVerificationStatus.module.css'

const STATUS_META = {
  SUBMITTED: {
    title: 'Verification submitted',
    description: 'Your documents have been submitted successfully and are waiting for review.',
    badge: 'SUBMITTED', icon: 'clock', tone: 'neutral',
  },
  UNDER_REVIEW: {
    title: 'Verification under review',
    description: 'Your submitted profile and documents are currently being reviewed. You will be notified when a decision is made.',
    badge: 'UNDER REVIEW', icon: 'clock', tone: 'neutral',
  },
  APPROVED: {
    title: 'Verification approved',
    description: 'Your verification has been approved. Your provider profile is eligible for marketplace activities according to the system rules.',
    badge: 'APPROVED', icon: 'check', tone: 'success',
  },
  REJECTED: {
    title: 'Verification rejected',
    description: 'Your verification could not be approved. Review the decision and upload updated documents before submitting again.',
    badge: 'REJECTED', icon: 'close', tone: 'error',
  },
  RESUBMISSION_REQUESTED: {
    title: 'Resubmission requested',
    description: 'Your verification requires updated information or documents before it can be reviewed again.',
    badge: 'RESUBMISSION REQUESTED', icon: 'refresh', tone: 'warning',
  },
  EXPIRED: {
    title: 'Verification expired',
    description: 'Your previous verification has expired. Review your document expiry dates and submit updated evidence.',
    badge: 'EXPIRED', icon: 'alert', tone: 'warning',
  },
  SUSPENDED: {
    title: 'Verification suspended',
    description: 'Your verification has been suspended. Please contact support for assistance.',
    badge: 'SUSPENDED', icon: 'alert', tone: 'warning',
  },
}

function progressSteps(status) {
  if (status === 'EXPIRED') {
    return [
      'Profile completed', 'Previous verification approved',
      'Verification expired', 'Re-verification required',
    ]
  }

  if (status === 'SUBMITTED' || status === 'UNDER_REVIEW') {
    return ['Profile completed', 'Documents submitted', 'Admin review']
  }

  return [
    'Profile completed', 'Documents submitted', 'Admin review',
    status === 'APPROVED' ? 'Approved' :
      status === 'REJECTED' ? 'Rejected' :
        status === 'RESUBMISSION_REQUESTED' ? 'Resubmission requested' : 'Suspended',
  ]
}

function completedStepCount(status) {
  if (status === 'APPROVED') return 4
  if (status === 'SUBMITTED' || status === 'UNDER_REVIEW') return 2
  if (status === 'EXPIRED') return 2
  return 3
}

/** Local-only icon matching the resubmission design. */
function ResubmissionRefreshIcon() {
  return (
    <svg
      width="32"
      height="32"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M20 11a8.1 8.1 0 0 0-15.5-2" />
      <path d="M4 4v5h5" />
      <path d="M4 13a8.1 8.1 0 0 0 15.5 2" />
      <path d="M20 20v-5h-5" />
    </svg>
  )
}

function DocumentRow({ document, asOfDate, showExpiry = false }) {
  const expired = isDocumentExpired(document, asOfDate)

  return (
    <div className={styles.documentItem}>
      <span
        className={`${styles.documentIcon} ${expired ? styles.iconExpired : ''}`}
        aria-hidden="true"
      >
        <ProviderFeatureIcon name="document" size={20} />
      </span>

      <div className={styles.documentInfo}>
        <strong>
          {DOCUMENT_LABELS[document.documentType] ?? 'Verification document'}
        </strong>
        <small>{document.originalFileName || 'Filename unavailable'}</small>
        <small>Uploaded {formatDocumentDate(document.uploadedAt)}</small>
      </div>

      {showExpiry && (
        <div className={styles.expiryInfo}>
          <span
            className={`${styles.docState} ${
              expired
                ? styles.docExpired
                : document.expiryDate
                  ? ''
                  : styles.docUnknown
            }`}
          >
            {expired
              ? 'EXPIRED'
              : document.expiryDate
                ? 'VALID BY DATE'
                : 'DATE NOT RECORDED'}
          </span>

          <small>
            {document.expiryDate
              ? `Expires ${formatDocumentDate(document.expiryDate)}`
              : 'No expiry date supplied'}
          </small>
        </div>
      )}
    </div>
  )
}

/**
 * Status and document data come from provider endpoints.
 * Only actual document metadata is rendered.
 */
export default function ProviderVerificationStatusPanel({
  verification,
  documents = [],
  asOfDate = '',
  onEditDocuments,
  onViewProfile,
}) {
  const status = verification?.status || 'NOT_SUBMITTED'
  const meta = STATUS_META[status]

  if (!meta) return null

  const uploadedDocuments = displayDocuments(documents)
  const hasDecisionReason = Boolean(verification?.decisionReason?.trim())

  const canUpdate = [
    'REJECTED',
    'RESUBMISSION_REQUESTED',
    'EXPIRED',
  ].includes(status)

  const steps = progressSteps(status)
  const completed = completedStepCount(status)

  const progressFill = `${
    Math.round(
      100 * Math.max(0, completed - 1) /
      Math.max(1, steps.length - 1)
    )
  }%`

  const requiresReplacement = uploadedDocuments.filter(
    (doc) => isDocumentExpired(doc, asOfDate)
  )

  const notExpired = uploadedDocuments.filter(
    (doc) => !isDocumentExpired(doc, asOfDate)
  )

  return (
    <div className={styles.page}>
      <section className={styles.hero} aria-label="Verification status">
        <div className={styles.heroTop}>
          <span
            className={`${styles.heroIcon} ${styles[meta.tone]}`}
            aria-hidden="true"
          >
            {status === 'RESUBMISSION_REQUESTED' ? (
              <ResubmissionRefreshIcon />
            ) : (
              <ProviderFeatureIcon name={meta.icon} size={30} />
            )}
          </span>

          <div className={styles.heroText}>
            <h2>{meta.title}</h2>
            <p>{meta.description}</p>
          </div>

          <span className={`${styles.badge} ${styles[meta.tone]}`}>
            {meta.badge}
          </span>
        </div>

        <div
          className={styles.progress}
          data-step-count={steps.length}
          style={{ '--verification-progress': progressFill }}
          aria-label="Verification progress"
        >
          {steps.map((step, index) => (
            <div className={styles.step} key={step}>
              <span
                className={`${styles.stepCircle} ${
                  index < completed ? styles.complete : ''
                } ${index === completed ? styles.next : ''}`}
              >
                {index < completed ? (
                  <ProviderFeatureIcon name="check" size={16} />
                ) : (
                  index + 1
                )}
              </span>
              <strong>{step}</strong>
            </div>
          ))}
        </div>

        {status === 'SUBMITTED' && (
          <div className={styles.notice}>
            <strong>What happens next?</strong>
            <p>
              An authorised reviewer will assess your submitted documents.
              You will be notified when a decision is made or more
              information is required.
            </p>
          </div>
        )}

        {status === 'UNDER_REVIEW' && (
          <div className={styles.notice}>
            <strong>Your verification is currently being reviewed.</strong>
            <p>No action is required from you at this time.</p>
          </div>
        )}

        {status === 'EXPIRED' && (
          <div className={`${styles.notice} ${styles.warningNotice}`}>
            <strong>Re-verification required</strong>
            <p>
              Your verified badge is unavailable while verification is
              expired. Submit updated documents to begin a new review.
              Existing confirmed bookings continue according to marketplace
              rules.
            </p>

            {verification?.gracePeriodEndsAt && (
              <p>
                Grace period ends:{' '}
                {formatDocumentDate(verification.gracePeriodEndsAt)}
              </p>
            )}
          </div>
        )}

        {status === 'REJECTED' && (
          <div className={`${styles.notice} ${styles.errorNotice}`}>
            <strong>Your verification could not be approved.</strong>
            <p>
              Review the recorded reason below before uploading new documents.
            </p>
          </div>
        )}

        {status === 'RESUBMISSION_REQUESTED' && (
          <div className={`${styles.notice} ${styles.warningNotice}`}>
            <strong>Additional changes are required.</strong>
            <p>
              Read the recorded reason and upload updated supporting
              documents before resubmitting.
            </p>
          </div>
        )}
      </section>

      {['REJECTED', 'RESUBMISSION_REQUESTED', 'SUSPENDED'].includes(status) && (
        <section id="verification-decision" className={styles.details}>
          <h2>
            {status === 'REJECTED'
              ? 'Rejection details'
              : status === 'RESUBMISSION_REQUESTED'
                ? 'Requested changes'
                : 'Suspension details'}
          </h2>

          <div className={styles.detailRow}>
            <strong>Reason</strong>
            <p>
              {hasDecisionReason
                ? verification.decisionReason
                : 'A detailed reason is not available in your verification record.'}
            </p>
          </div>

          {verification?.reviewedAt && (
            <div className={styles.detailRow}>
              <strong>Last review</strong>
              <p>{formatDocumentDate(verification.reviewedAt)}</p>
            </div>
          )}
        </section>
      )}

      {status === 'EXPIRED' ? (
        <>
          <section className={styles.documents}>
            <h2>Documents requiring replacement</h2>
            <p>
              These uploaded documents have passed their recorded expiry dates.
            </p>

            <div className={styles.documentList}>
              {requiresReplacement.length ? (
                requiresReplacement.map((doc, index) => (
                  <DocumentRow
                    key={doc.id || `expired-${index}`}
                    document={doc}
                    asOfDate={asOfDate}
                    showExpiry
                  />
                ))
              ) : (
                <div className={styles.empty}>
                  No expired document can be identified from the available
                  upload metadata. Your verification is still marked expired;
                  please review your documents and submit updated evidence.
                </div>
              )}
            </div>
          </section>

          {notExpired.length > 0 && (
            <section className={styles.documents}>
              <h2>Documents not expired by recorded date</h2>
              <p>
                These documents have not reached a recorded expiry date,
                or no expiry date was provided.
              </p>

              <div className={styles.documentList}>
                {notExpired.map((doc, index) => (
                  <DocumentRow
                    key={doc.id || `valid-${index}`}
                    document={doc}
                    asOfDate={asOfDate}
                    showExpiry
                  />
                ))}
              </div>
            </section>
          )}

          <div className={styles.notice}>
            <strong>Your documents remain private.</strong>
            <p>
              Only you and authorised verification reviewers can access
              verification information through permitted services.
            </p>
          </div>
        </>
      ) : (
        <section className={styles.documents}>
          <h2>Submitted documents</h2>
          <p>
            Documents recorded for your provider account.
            Only documents actually uploaded appear here.
          </p>

          <div className={styles.documentList}>
            {uploadedDocuments.length ? (
              uploadedDocuments.map((doc, index) => (
                <DocumentRow
                  key={doc.id || `uploaded-${index}`}
                  document={doc}
                  asOfDate={asOfDate}
                />
              ))
            ) : (
              <div className={styles.empty}>
                No uploaded verification documents were returned.
              </div>
            )}
          </div>

          <small className={styles.privateNote}>
            Document filenames and dates are shown here.
            Secure file previews are not available through the current
            provider API.
          </small>
        </section>
      )}

      <div className={styles.actions}>
        {canUpdate ? (
          <>
            <Button variant="outline" onClick={onViewProfile}>
              View profile
            </Button>
            <Button onClick={onEditDocuments}>
              {status === 'EXPIRED'
                ? 'Start re-verification'
                : 'Update documents'}
            </Button>
          </>
        ) : (
          <Button variant="outline" onClick={onViewProfile}>
            View profile
          </Button>
        )}
      </div>
    </div>
  )
}
