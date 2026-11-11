import { useState } from 'react'

import { Button, Modal, Textarea } from '../../../shared/components/forms'
import CompletionIcon from './CompletionIcons'
import ModalTitle from './ModalTitle'
import PhotoPicker from './PhotoPicker'
import { AUTO_CONFIRM_HOURS, NOTE_MAX_LENGTH } from '../utils/completionUtils'

/**
 * S02 – "Mark service as completed" form: optional note (max 500) and photos.
 * `onSubmit({ completionNote, files })` is called with the valid photo files; the parent uploads and saves.
 */
export default function MarkCompletedModal({ isOpen, onClose, onSubmit, booking, loading = false, error = null }) {
  const [note, setNote] = useState('')
  const [photos, setPhotos] = useState([])

  const hasInvalidPhotos = photos.some((photo) => photo.error)

  const handleSubmit = (event) => {
    event?.preventDefault()
    if (hasInvalidPhotos || loading) {
      return
    }
    onSubmit({
      completionNote: note.trim() || null,
      files: photos.map((photo) => photo.file),
    })
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
      className="sc-modal"
      title={<ModalTitle icon="checkCircle">Mark service as completed</ModalTitle>}
      description={`Booking #${booking.ref} · ${booking.title}`}
      footer={
        <>
          <Button variant="outline" onClick={close} disabled={loading}>Cancel</Button>
          <Button variant="primary" onClick={handleSubmit} loading={loading} disabled={hasInvalidPhotos}>
            Mark Completed
          </Button>
        </>
      }
    >
      <form id="sc-mark-completed-form" className="sc-form" onSubmit={handleSubmit} noValidate>
        <Textarea
          label={<>Completion note <span className="sc-field-label__hint">(optional)</span></>}
          placeholder="Describe the work you completed..."
          rows={4}
          maxLength={NOTE_MAX_LENGTH}
          value={note}
          onChange={(event) => setNote(event.target.value.slice(0, NOTE_MAX_LENGTH))}
          helperText={`${note.length} / ${NOTE_MAX_LENGTH}`}
          disabled={loading}
        />

        <PhotoPicker items={photos} onChange={setPhotos} disabled={loading} />

        {hasInvalidPhotos && (
          <p className="sc-field-hint">Remove the photos marked in red to continue.</p>
        )}

        <p className="sc-callout sc-callout--info">
          <CompletionIcon name="clock" />
          <span>
            The customer has <strong>{AUTO_CONFIRM_HOURS} hours</strong> to confirm or raise an issue.
            After that, it is confirmed automatically.
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
