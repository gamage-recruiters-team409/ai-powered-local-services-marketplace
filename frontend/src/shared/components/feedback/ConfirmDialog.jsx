import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { VariantIcon } from './variants.jsx'
import Spinner from './Spinner.jsx'
import './feedback.css'

const FOCUSABLE =
  'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
const MAX_REASON = 500

function getFocusable(root) {
  return [...root.querySelectorAll(FOCUSABLE)].filter(
    (el) => !el.disabled && el.getAttribute('aria-hidden') !== 'true',
  )
}

/**
 * Makes everything else in <body> inert (not clickable, not focusable, hidden
 * from assistive technology) while the modal is open. Returns a function that
 * restores each element to exactly the state it had before.
 */
function isolateBackground(keep) {
  const changed = []
  for (const el of [...document.body.children]) {
    if (el === keep || el.tagName === 'SCRIPT') continue
    changed.push({
      el,
      hadInert: el.hasAttribute('inert'),
      ariaHidden: el.getAttribute('aria-hidden'),
    })
    el.setAttribute('inert', '')
    el.setAttribute('aria-hidden', 'true')
  }
  return () => {
    for (const { el, hadInert, ariaHidden } of changed) {
      if (!hadInert) el.removeAttribute('inert')
      if (ariaHidden === null) el.removeAttribute('aria-hidden')
      else el.setAttribute('aria-hidden', ariaHidden)
    }
  }
}

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
  const overlayRef = useRef(null)
  const dialogRef = useRef(null)
  const titleId = useId()
  const textId = useId()
  const reasonId = useId()

  const destructive = variant === 'destructive'
  const trimmed = reason.trim()
  const canConfirm = !loading && (!requireReason || !reasonRequired || trimmed.length > 0)

  useEffect(() => {
    const overlay = overlayRef.current
    const dialog = dialogRef.current
    const previous = document.activeElement

    // 1. Background isolation: nothing outside the dialog can be reached.
    const restoreBackground = isolateBackground(overlay)
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    // 2. Safe initial focus (falls back to the dialog itself).
    const target = dialog.querySelector('[data-autofocus]') ?? getFocusable(dialog)[0] ?? dialog
    target.focus()

    // 3. Safety net: if focus ever lands outside (for example a browser
    //    without `inert` support), pull it straight back in.
    const keepFocusInside = (event) => {
      if (!overlay.contains(event.target)) {
        ;(getFocusable(dialog)[0] ?? dialog).focus()
      }
    }
    document.addEventListener('focusin', keepFocusInside)

    return () => {
      document.removeEventListener('focusin', keepFocusInside)
      restoreBackground()
      document.body.style.overflow = previousOverflow
      previous?.focus?.()
    }
  }, [])

  const handleKeyDown = (event) => {
    if (event.key === 'Escape') {
      if (!loading) {
        event.stopPropagation()
        onCancel()
      }
      return
    }
    if (event.key !== 'Tab') return
    const dialog = dialogRef.current
    const items = getFocusable(dialog)
    if (items.length === 0) {
      event.preventDefault()
      dialog.focus()
      return
    }
    const first = items[0]
    const last = items[items.length - 1]
    const active = document.activeElement
    if (event.shiftKey && (active === first || active === dialog)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && active === last) {
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
      ref={overlayRef}
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
        aria-busy={loading || undefined}
        tabIndex={-1}
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
              readOnly={loading}
            />
            <span className="fb-counter">
              {reason.length}/{MAX_REASON}
            </span>
          </div>
        )}

        {loading && (
          <span className="fb-sr-only" role="status">
            Please wait
          </span>
        )}

        <div className="fb-dialog-actions">
          <button
            type="button"
            className="fb-btn fb-btn-outline"
            onClick={() => {
              if (!loading) onCancel()
            }}
            aria-disabled={loading || undefined}
            data-autofocus={focusCancel ? '' : undefined}
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            className={`fb-btn ${destructive ? 'fb-btn-danger' : 'fb-btn-primary'}`}
            onClick={() => {
              if (canConfirm) onConfirm(requireReason ? trimmed : undefined)
            }}
            aria-disabled={!canConfirm || undefined}
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
