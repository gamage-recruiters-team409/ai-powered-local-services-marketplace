import { useState } from 'react'

import { Button, Modal, Select, Textarea } from '../../../shared/components/forms'
import { NOTE_MIN, noteMaxForAction } from '../config/operationsConfig'
import { parseApiError } from '../utils/errors'
import styles from '../operations.module.css'

/**
 * Shared "handle case / take action" modal: case facts, an action select, a required note (10–1000 with a counter),
 * and Cancel/Save. Prevents double submit, shows inline validation and server errors (including 403/409). The actor is
 * never a form field — the backend reads it from the session.
 *
 * @param {object} props
 * @param {{ label: string, value: React.ReactNode }[]} props.facts
 * @param {{ value: string, label: string }[]} props.actions
 * @param {(action: string, note: string) => Promise<unknown>} props.onSubmit
 * @param {(action: string) => (string | null)} [props.noteForAction] extra guidance shown for the chosen action
 */
export function ActionModal({
  isOpen,
  onClose,
  title,
  facts,
  actions,
  onSubmit,
  submitLabel = 'Save',
  noteForAction,
}) {
  // The parent mounts this modal only while a case is selected, so state starts fresh on every open.
  const [action, setAction] = useState(actions[0]?.value ?? '')
  const [note, setNote] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)

  const maxNote = noteMaxForAction(action)
  const trimmed = note.trim()
  const noteInvalid = trimmed.length < NOTE_MIN || trimmed.length > maxNote
  const guidance = noteForAction ? noteForAction(action) : null

  const handleSave = async () => {
    if (submitting) return
    if (!action) {
      setError('Please choose an action.')
      return
    }
    if (noteInvalid) {
      setError(`A note of ${NOTE_MIN}–${maxNote} characters is required.`)
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await onSubmit(action, trimmed)
    } catch (err) {
      setError(parseApiError(err).message)
      setSubmitting(false)
    }
  }

  return (
    <Modal
      isOpen={isOpen}
      onClose={submitting ? () => {} : onClose}
      title={title}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button onClick={handleSave} loading={submitting} disabled={submitting}>
            {submitLabel}
          </Button>
        </>
      }
    >
      {error && (
        <p className={styles.modalError} role="alert">
          {error}
        </p>
      )}

      {facts?.length > 0 && (
        <dl className={styles.modalFacts}>
          {facts.map((fact) => (
            <div key={fact.label} className={styles.factRow}>
              <dt>{fact.label}</dt>
              <dd>{fact.value}</dd>
            </div>
          ))}
        </dl>
      )}

      <div className={styles.field}>
        <Select
          label="Action"
          options={actions}
          value={action}
          onChange={(event) => setAction(event?.target ? event.target.value : event)}
          disabled={submitting}
          required
        />
      </div>

      {guidance && <p className={styles.modalNote}>{guidance}</p>}

      <div className={styles.field}>
        <Textarea
          label="Administrative note"
          rows={4}
          value={note}
          maxLength={maxNote}
          placeholder="Enter action taken, communication notes, or next steps…"
          onChange={(event) => setNote(event.target.value)}
          disabled={submitting}
          required
        />
        <p className={styles.noteCounter}>
          {trimmed.length}/{maxNote}
        </p>
      </div>
    </Modal>
  )
}
