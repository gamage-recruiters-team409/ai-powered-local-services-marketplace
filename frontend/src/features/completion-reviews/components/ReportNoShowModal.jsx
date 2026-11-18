import { useState } from 'react'

import { Button, Modal, Textarea } from '../../../shared/components/forms'
import CompletionIcon from './CompletionIcons'
import ModalTitle from './ModalTitle'
import PhotoPicker from './PhotoPicker'
import {
  DISPUTE_RESPONSE_HOURS,
  NOTE_MAX_LENGTH,
  formatDuration,
  formatSlot,
  formatTime,
  noShowAllowedFrom,
  noShowRemainingMs,
} from '../utils/completionUtils'

const OPTION_TEXT = {
  CUSTOMER: { title: 'The provider did not arrive', hint: 'The provider has not started the service.' },
  PROVIDER: { title: 'The customer was not available', hint: 'You could not start the service at the booked time.' },
}

/**
 * S06 – "Report a no-show" (customer and provider).
 * Before slotStart + 120 minutes it shows the "too early" state with a countdown and a disabled button.
 * `onSubmit({ note, files })` – the parent sends the note and uploads evidence.
 */
export default function ReportNoShowModal({
  isOpen,
  onClose,
  onSubmit,
  booking,
  role = 'CUSTOMER',
  allowEvidence = false,
  loading = false,
  error = null,
  now = new Date(),
}) {
  const [note, setNote] = useState('')
  const [photos, setPhotos] = useState([])

  const remaining = noShowRemainingMs(booking.slotStart, now)
  const tooEarly = remaining > 0
  const allowedFrom = formatTime(noShowAllowedFrom(booking.slotStart))
  const other = role === 'PROVIDER' ? 'customer' : 'provider'
  const option = OPTION_TEXT[role] ?? OPTION_TEXT.CUSTOMER
  const hasInvalidPhotos = photos.some((photo) => photo.error)

  const handleSubmit = (event) => {
    event?.preventDefault()
    if (tooEarly || loading || hasInvalidPhotos) {
      return
    }
    onSubmit({ note: note.trim() || null, files: photos.map((photo) => photo.file) })
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
      className={`sc-modal${tooEarly ? ' sc-modal--narrow sc-modal--warning-icon' : ' sc-modal--danger-icon'}`}
      title={<ModalTitle icon={tooEarly ? 'clock' : 'flag'}>Report a no-show</ModalTitle>}
      description={tooEarly ? undefined : `Booking #${booking.ref} · ${formatSlot(booking.slotStart, booking.slotEnd, now)}`}
      footer={
        <>
          <Button variant="outline" onClick={close} disabled={loading}>{tooEarly ? 'Close' : 'Cancel'}</Button>
          <Button
            variant="destructive"
            onClick={handleSubmit}
            loading={loading}
            disabled={tooEarly || hasInvalidPhotos}
          >
            Report No-Show
          </Button>
        </>
      }
    >
      {tooEarly ? (
        <>
          <div className="sc-callout sc-callout--warning sc-callout--stacked">
            <CompletionIcon name="clock" />
            <div>
              <p>You can report a no-show from <strong>{allowedFrom}</strong> (2 hours after the start time).</p>
              <p><strong>Time left: {formatDuration(remaining)}</strong></p>
            </div>
          </div>
          <p className="sc-muted">
            {role === 'PROVIDER'
              ? `Customers may be a little late. If they are still not available, come back after ${allowedFrom}. You can also message the customer.`
              : `Providers may be a little late. If they still haven't arrived, come back after ${allowedFrom}. You can also message the provider.`}
          </p>
        </>
      ) : (
        <form className="sc-form" onSubmit={handleSubmit} noValidate>
          <div className="sc-option-card" role="radio" aria-checked="true" tabIndex={0}>
            <span className="sc-option-card__radio" aria-hidden="true" />
            <span>
              <span className="sc-option-card__title">{option.title}</span>
              <span className="sc-option-card__hint">{option.hint}</span>
            </span>
          </div>

          <Textarea
            label={<>Description <span className="sc-field-label__hint">(optional)</span></>}
            placeholder="Add details..."
            rows={3}
            maxLength={NOTE_MAX_LENGTH}
            value={note}
            onChange={(event) => setNote(event.target.value.slice(0, NOTE_MAX_LENGTH))}
            helperText={`${note.length} / ${NOTE_MAX_LENGTH}`}
            disabled={loading}
          />

          {allowEvidence && (
            <PhotoPicker items={photos} onChange={setPhotos} disabled={loading} label="Evidence" />
          )}

          <p className="sc-callout sc-callout--warning">
            <CompletionIcon name="info" />
            <span>
              The {other} has <strong>{DISPUTE_RESPONSE_HOURS} hours</strong> to respond. Payment is held until it is resolved.
            </span>
          </p>

          {error && (
            <p className="sc-callout sc-callout--error" role="alert">
              <CompletionIcon name="alert" />
              <span>{error}</span>
            </p>
          )}
        </form>
      )}
    </Modal>
  )
}
