import { useId, useRef, useState } from 'react'

import { useToast } from '../../../shared/components/feedback'
import { Button } from '../../../shared/components/forms'
import { AnnouncementDialog } from '../../admin-announcements/components/AnnouncementDialog'
import { SUSPENSION_REASONS } from '../config/usersConfig'
import { reactivateUser, suspendUser } from '../services/adminUsersService'
import { parseApiError } from '../utils/errors'
import { noteError } from '../utils/validation'
import { NoteField } from './NoteField'
import { UsersIcon } from './UsersIcon'
import styles from '../adminUsers.module.css'

/**
 * The server's error. 409 CONCURRENT_MODIFICATION (another admin changed the status first, nothing was changed here)
 * also offers "Reload", which closes the dialog and loads the account's current state.
 */
function ServerError({ error, onReload }) {
  if (!error) return null
  const stale = error.code === 'CONCURRENT_MODIFICATION'
  return (
    <div className={styles.dialogError} role="alert">
      <UsersIcon name="alertTriangle" size={15} />
      <span>{error.message}</span>
      {stale && onReload && (
        <Button variant="outline" size="small" onClick={onReload} className={styles.alertAction}>
          Reload
        </Button>
      )}
    </div>
  )
}

/**
 * One submit at a time: the ref blocks a second click before React re-renders the disabled button.
 * Returns [saving, run].
 */
function useSingleSubmit() {
  const [saving, setSaving] = useState(false)
  const inFlight = useRef(false)
  const run = async (task) => {
    if (inFlight.current) return
    inFlight.current = true
    setSaving(true)
    try {
      await task()
    } finally {
      inFlight.current = false
      setSaving(false)
    }
  }
  return [saving, run]
}

/** Modal A: suspend with a reason code; the note is required for "Other". */
function SuspendForm({ user, onClose, onDone, onReload }) {
  const toast = useToast()
  const reasonId = useId()
  const textId = useId()
  const [reasonCode, setReasonCode] = useState('')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState(null)
  const [saving, run] = useSingleSubmit()
  const other = reasonCode === 'OTHER'

  const submit = () => {
    const found = {
      reasonCode: reasonCode ? null : 'Choose a reason.',
      note: noteError(note, { required: other }),
    }
    setErrors(found)
    if (found.reasonCode || found.note) return
    run(async () => {
      setServerError(null)
      try {
        const result = await suspendUser(user.id, { reasonCode, note: note.trim() })
        toast.success(result.changed ? `${user.displayName} was suspended.` : `${user.displayName} is already suspended.`)
        onDone(result)
      } catch (error) {
        setServerError(parseApiError(error))
      }
    })
  }

  return (
    <AnnouncementDialog
      open
      title="Suspend account"
      icon="alertTriangle"
      tone="danger"
      busy={saving}
      onClose={onClose}
      describedBy={textId}
      footer={
        <>
          <Button variant="secondary" size="small" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button variant="destructive" size="small" onClick={submit} loading={saving} disabled={saving}>
            Suspend account
          </Button>
        </>
      }
    >
      <p id={textId} className={styles.dialogText}>
        Suspend <strong>{user.displayName}</strong>? They won&apos;t be able to book or accept bookings.
      </p>
      <ServerError error={serverError} onReload={onReload} />
      <div className={styles.field}>
        <label htmlFor={reasonId} className={styles.fieldLabel}>
          Reason
          <span className={styles.required} aria-hidden="true">
            {' *'}
          </span>
        </label>
        <select
          id={reasonId}
          className={`${styles.select} ${errors.reasonCode ? styles.inputError : ''}`}
          value={reasonCode}
          onChange={(event) => {
            setReasonCode(event.target.value)
            setErrors((e) => ({ ...e, reasonCode: null, note: null }))
          }}
          disabled={saving}
          required
          aria-invalid={errors.reasonCode ? 'true' : undefined}
          aria-describedby={errors.reasonCode ? `${reasonId}-error` : undefined}
          data-autofocus
        >
          <option value="">Select a reason</option>
          {SUSPENSION_REASONS.map((r) => (
            <option key={r.value} value={r.value}>
              {r.label}
            </option>
          ))}
        </select>
        {errors.reasonCode && (
          <p id={`${reasonId}-error`} className={styles.fieldError}>
            {errors.reasonCode}
          </p>
        )}
      </div>
      <NoteField
        label="Administrator note"
        value={note}
        onChange={(value) => {
          setNote(value)
          setErrors((e) => ({ ...e, note: null }))
        }}
        error={errors.note}
        required={other}
        disabled={saving}
        placeholder="Provide internal reasoning or context..."
        hint={other ? 'Required when the reason is Other.' : 'Optional. Visible to admins only.'}
      />
    </AnnouncementDialog>
  )
}

/** Modal B: reactivate with a required note. */
function ReactivateForm({ user, onClose, onDone, onReload }) {
  const toast = useToast()
  const textId = useId()
  const [note, setNote] = useState('')
  const [error, setError] = useState(null)
  const [serverError, setServerError] = useState(null)
  const [saving, run] = useSingleSubmit()

  const submit = () => {
    const found = noteError(note, { required: true })
    setError(found)
    if (found) return
    run(async () => {
      setServerError(null)
      try {
        const result = await reactivateUser(user.id, { note: note.trim() })
        toast.success(result.changed ? `${user.displayName} was reactivated.` : `${user.displayName} is already active.`)
        onDone(result)
      } catch (err) {
        setServerError(parseApiError(err))
      }
    })
  }

  return (
    <AnnouncementDialog
      open
      title="Reactivate account"
      icon="refresh"
      busy={saving}
      onClose={onClose}
      describedBy={textId}
      footer={
        <>
          <Button variant="secondary" size="small" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button size="small" onClick={submit} loading={saving} disabled={saving}>
            Reactivate
          </Button>
        </>
      }
    >
      <p id={textId} className={styles.dialogText}>
        Reactivate <strong>{user.displayName}</strong>? They will be able to use the platform again.
      </p>
      <ServerError error={serverError} onReload={onReload} />
      <NoteField
        label="Administrative note"
        value={note}
        onChange={(value) => {
          setNote(value)
          setError(null)
        }}
        error={error}
        required
        disabled={saving}
        placeholder="Enter reactivation context or notes..."
        hint="Stored in the audit log."
      />
    </AnnouncementDialog>
  )
}

/** Mounted only while open, so every opening starts with an empty form. */
export function SuspendDialog({ open, ...props }) {
  return open ? <SuspendForm {...props} /> : null
}

export function ReactivateDialog({ open, ...props }) {
  return open ? <ReactivateForm {...props} /> : null
}
