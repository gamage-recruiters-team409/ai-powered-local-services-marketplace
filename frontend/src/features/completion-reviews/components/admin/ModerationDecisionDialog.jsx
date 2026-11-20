import { useState } from 'react'

import { Button, Modal, Select, Textarea } from '../../../../shared/components/forms'
import CompletionIcon from '../CompletionIcons'
import ModalTitle from '../ModalTitle'
import { REMOVAL_REASONS } from '../../utils/moderationUtils'

const NOTE_MAX = 500

const COPY = {
  REMOVE: {
    title: 'Remove this review?',
    icon: 'alert',
    info: 'The review is hidden from the public and the provider, the customer sees the reason, open reports are closed and the ratings are recalculated.',
    confirm: 'Remove review',
    variant: 'destructive',
  },
  PUBLISH: {
    title: 'Publish this review?',
    icon: 'checkCircle',
    info: 'The review becomes public and counts in the provider and listing ratings.',
    confirm: 'Publish review',
    variant: 'primary',
  },
  KEEP: {
    title: 'Keep this review?',
    icon: 'checkCircle',
    info: 'The review stays published and its open reports are closed as dismissed.',
    confirm: 'Keep review',
    variant: 'primary',
  },
}

/**
 * S16 – reason for a moderation decision. kind: 'REMOVE' | 'PUBLISH' | 'KEEP'.
 * Remove needs a reason (shown to the customer); the details note is internal and optional.
 * The parent mounts it only while open (with key={kind}), so every opening starts with an empty form.
 */
export default function ModerationDecisionDialog({ kind, isOpen, onClose, onSubmit, loading = false, error = null }) {
  const [reasonCode, setReasonCode] = useState('')
  const [note, setNote] = useState('')
  const [reasonError, setReasonError] = useState(null)

  if (!kind) {
    return null
  }
  const copy = COPY[kind]
  const isRemove = kind === 'REMOVE'

  const submit = () => {
    if (isRemove && !reasonCode) {
      setReasonError('Please choose a reason')
      return
    }
    onSubmit({ reasonCode: isRemove ? reasonCode : undefined, note: note.trim() })
  }

  return (
    <Modal
      isOpen={isOpen}
      onClose={loading ? undefined : onClose}
      closeOnBackdrop={!loading}
      className="sc-modal sc-modal--narrow"
      title={<ModalTitle icon={copy.icon}>{copy.title}</ModalTitle>}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading}>Cancel</Button>
          <Button variant={copy.variant} onClick={submit} loading={loading}>{copy.confirm}</Button>
        </>
      }
    >
      {isRemove && (
        <Select
          label="Reason"
          required
          placeholder="Choose a reason"
          options={REMOVAL_REASONS}
          value={reasonCode}
          onChange={(event) => {
            setReasonCode(event.target.value)
            setReasonError(null)
          }}
          error={reasonError}
          helperText="Shown to the customer and saved in the moderation history."
          disabled={loading}
        />
      )}
      <Textarea
        label="Details (optional)"
        rows={3}
        maxLength={NOTE_MAX}
        value={note}
        onChange={(event) => setNote(event.target.value)}
        helperText="Internal note for the moderation history."
        disabled={loading}
      />
      <p className="sc-callout sc-callout--info">
        <CompletionIcon name="info" />
        <span>{copy.info}</span>
      </p>
      {error && (
        <p className="sc-callout sc-callout--error" role="alert">
          <CompletionIcon name="alert" />
          <span>{error}</span>
        </p>
      )}
    </Modal>
  )
}
