import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { VariantIcon } from './variants.jsx'
import Spinner from './Spinner.jsx'
import './feedback.css'

const FOCUSABLE =
  'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
const MAX_REASON = 500

function DialogBody({
  title,
  message,
  confirmLabel,
  cancelLabel,
  variant,
  requireReason,
  reasonLabel,
  reasonRequired,
  loading,
  onConfirm,
  onCancel,
}) {
  const [reason, setReason] = useState('')
  const dialogRef = useRef(null)
  const titleId = useId()
  const textId = useId()
  const reasonId = useId()

  const destructive = variant === 'destructive'
  const trimmed = reason.trim()
  const canConfirm = !loading && (!requireReason || !reasonRequired || trimmed.length > 0)

  useEffect(() => {
    const previous = document.activeElement
    const node = dialogRef.current
    const target = node.querySelector('[data-autofocus]') ?? node.querySelector(FOCUSABLE)
    target?.focus()
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
      previous?.focus?.()
    }
  }, [])

  const handleKeyDown = (event) => {
    if (event.key === 'Escape' && !loading) {
      event.stopPropagation()
      onCancel()
      return
    }
    if (event.key !== 'Tab') return
    const items = [...dialogRef.current.querySelectorAll(FOCUSABLE)].filter(
      (el) => !el.disabled,
    )
    if (items.length === 0) return
    const first = items[0]
    const last = items[items.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  const iconVariant = destructive ? 'error' : 'info'
  // Safe default focus: the reason box, otherwise Cancel for destructive, else Confirm.
  const focusReason = requireReason
  const focusCancel = !requireReason && destructive
  const focusConfirm = !requireReason && !destructive

  return (
    <div
      className="fb-overlay"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !loading) onCancel()
      }}
    >
      <div
        ref={dialogRef}
        className={`fb-dialog fb-v-${iconVariant}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={message ? textId : undefined}
        onKeyDown={handleKeyDown}
      >
        <div className="fb-dialog-head">
          <VariantIcon variant={iconVariant} className="fb-dialog-icon" />
          <div>
            <h2 id={titleId} className="fb-dialog-title">
              {title}
            </h2>
            {message && (
              <p id={textId} className="fb-dialog-text">
                {message}
              </p>
            )}
          </div>
        </div>

        {requireReason && (
          <div className="fb-field">
            <label htmlFor={reasonId} className="fb-label">
              {reasonLabel}
              {reasonRequired ? ' (required)' : ' (optional)'}
            </label>
            <textarea
              id={reasonId}
              className="fb-textarea"
              value={reason}
              maxLength={MAX_REASON}
              onChange={(event) => setReason(event.target.value)}
              data-autofocus={focusReason ? '' : undefined}
              disabled={loading}
            />
            <span className="fb-counter">
              {reason.length}/{MAX_REASON}
            </span>
          </div>
        )}

        <div className="fb-dialog-actions">
          <button
            type="button"
            className="fb-btn fb-btn-outline"
            onClick={onCancel}
            disabled={loading}
            data-autofocus={focusCancel ? '' : undefined}
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            className={`fb-btn ${destructive ? 'fb-btn-danger' : 'fb-btn-primary'}`}
            onClick={() => onConfirm(requireReason ? trimmed : undefined)}
            disabled={!canConfirm}
            data-autofocus={focusConfirm ? '' : undefined}
          >
            {loading && <Spinner size={16} label="" />}
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * Confirmation dialog.
 * variant: 'default' | 'destructive'
 * requireReason: shows a reason box; onConfirm receives the reason text.
 * reasonRequired: confirm stays disabled until a reason is typed.
 */
export default function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  variant = 'default',
  requireReason = false,
  reasonLabel = 'Reason',
  reasonRequired = true,
  loading = false,
  onConfirm,
  onCancel,
}) {
  if (!open) return null
  return createPortal(
    <DialogBody
      title={title}
      message={message}
      confirmLabel={confirmLabel}
      cancelLabel={cancelLabel}
      variant={variant}
      requireReason={requireReason}
      reasonLabel={reasonLabel}
      reasonRequired={reasonRequired}
      loading={loading}
      onConfirm={onConfirm}
      onCancel={onCancel}
    />,
    document.body,
  )
}
