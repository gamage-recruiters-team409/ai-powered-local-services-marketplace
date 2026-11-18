import { useState } from 'react'

import { Button, Modal, Select, Textarea } from '../../../shared/components/forms'
import CompletionIcon from './CompletionIcons'
import ModalTitle from './ModalTitle'
import PhotoPicker from './PhotoPicker'
import {
  DISPUTE_RESPONSE_HOURS,
  ISSUE_DESCRIPTION_MAX_LENGTH,
  ISSUE_DESCRIPTION_MIN_LENGTH,
  ISSUE_REASONS,
} from '../utils/completionUtils'

/**
 * S05 – "Raise an issue" form (customer).
 * Reason and description are required; the description needs at least 10 characters.
 * `onSubmit({ reason, description, files })` – the parent builds the backend reason text and uploads evidence.
 * `allowEvidence` shows the optional evidence picker (only when the API can store evidence).
 */
export default function RaiseIssueModal({
  isOpen,
  onClose,
  onSubmit,
  booking,
  allowEvidence = false,
  loading = false,
  error = null,
}) {
  const [reason, setReason] = useState('')
  const [description, setDescription] = useState('')
  const [photos, setPhotos] = useState([])
  const [submitted, setSubmitted] = useState(false)

  const trimmed = description.trim()
  const reasonError = submitted && !reason ? 'Select a reason.' : null
  let descriptionError = null
  if (submitted && !trimmed) {
    descriptionError = 'Describe the problem.'
  } else if (submitted && trimmed.length < ISSUE_DESCRIPTION_MIN_LENGTH) {
    descriptionError = `Add a little more detail (at least ${ISSUE_DESCRIPTION_MIN_LENGTH} characters).`
  }
  const hasInvalidPhotos = photos.some((photo) => photo.error)

  const handleSubmit = (event) => {
    event?.preventDefault()
    setSubmitted(true)
    if (loading || hasInvalidPhotos || !reason || trimmed.length < ISSUE_DESCRIPTION_MIN_LENGTH) {
      return
    }
    onSubmit({ reason, description: trimmed, files: photos.map((photo) => photo.file) })
  }

  const close = () => {
    if (!loading) {
      onClose()
    }
  }

  return (
    <Modal
      isOpen={isOpen}
      onClose={close}
      closeOnBackdrop={!loading}
      className="sc-modal sc-modal--danger-icon"
      title={<ModalTitle icon="flag">Raise an issue</ModalTitle>}
      description={`Booking #${booking.ref} · ${booking.title}`}
      footer={
        <>
          <Button variant="outline" onClick={close} disabled={loading}>Cancel</Button>
          <Button variant="primary" onClick={handleSubmit} loading={loading} disabled={hasInvalidPhotos}>
            Submit issue
          </Button>
        </>
      }
    >
      <form className="sc-form" onSubmit={handleSubmit} noValidate>
        <Select
          label="Reason"
          required
          placeholder="Select a reason"
          options={ISSUE_REASONS}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          error={reasonError}
          disabled={loading}
        />

        <Textarea
          label="Describe the problem"
          required
          placeholder="What went wrong? Add as much detail as you can."
          rows={4}
          maxLength={ISSUE_DESCRIPTION_MAX_LENGTH}
          value={description}
          onChange={(event) => setDescription(event.target.value.slice(0, ISSUE_DESCRIPTION_MAX_LENGTH))}
          helperText={descriptionError ? undefined : `${description.length} / ${ISSUE_DESCRIPTION_MAX_LENGTH}`}
          error={descriptionError}
          disabled={loading}
        />

        {allowEvidence && (
          <PhotoPicker items={photos} onChange={setPhotos} disabled={loading} label="Evidence" />
        )}

        <p className="sc-callout sc-callout--warning">
          <CompletionIcon name="clock" />
          <span>
            The provider&apos;s payment is held until the issue is resolved. The provider has
            {' '}<strong>{DISPUTE_RESPONSE_HOURS} hours</strong> to respond.
          </span>
        </p>

        {error && (
          <p className="sc-callout sc-callout--error" role="alert">
            <CompletionIcon name="alert" />
            <span>{error}</span>
          </p>
        )}
      </form>
    </Modal>
  )
}

/** S05 success – "Issue submitted" with the dispute reference. */
export function IssueSubmittedModal({ isOpen, onClose, disputeId, disputeUrl }) {
  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      className="sc-modal sc-modal--narrow sc-modal--centered"
      showCloseButton={false}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>Close</Button>
          {disputeUrl && (
            <a className="shared-btn shared-btn--primary shared-btn--medium sc-link-btn" href={disputeUrl}>
              View dispute
            </a>
          )}
        </>
      }
    >
      <div className="sc-result">
        <span className="sc-result__icon sc-result__icon--success"><CompletionIcon name="checkCircle" size={36} /></span>
        <h2 className="sc-result__title">Issue submitted</h2>
        <p className="sc-muted">
          {disputeId ? <>Your reference is <strong>{disputeId}</strong>. </> : null}
          We&apos;ll notify you when the provider responds.
        </p>
      </div>
    </Modal>
  )
}
