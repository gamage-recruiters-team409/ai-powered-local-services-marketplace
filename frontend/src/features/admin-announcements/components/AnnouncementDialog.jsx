import { useEffect, useId, useRef } from 'react'
import { createPortal } from 'react-dom'

import { AnnIcon } from './AnnIcon'
import styles from '../announcements.module.css'

const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'

function focusable(root) {
  return [...root.querySelectorAll(FOCUSABLE)].filter((el) => !el.disabled && el.getAttribute('aria-hidden') !== 'true')
}

/** Everything else in <body> becomes inert while the dialog is open; returns a restore function. */
function isolateBackground(keep) {
  const changed = []
  for (const el of [...document.body.children]) {
    if (el === keep || el.tagName === 'SCRIPT') continue
    changed.push({ el, hadInert: el.hasAttribute('inert'), ariaHidden: el.getAttribute('aria-hidden') })
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

function DialogBody({ title, icon, tone, busy, onClose, children, footer, describedBy }) {
  const overlayRef = useRef(null)
  const dialogRef = useRef(null)
  const titleId = useId()
  const closeRef = useRef(onClose)
  const busyRef = useRef(busy)
  useEffect(() => {
    closeRef.current = onClose
    busyRef.current = busy
  })

  useEffect(() => {
    const overlay = overlayRef.current
    const dialog = dialogRef.current
    const previous = document.activeElement
    const restore = isolateBackground(overlay)
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    ;(dialog.querySelector('[data-autofocus]') ?? focusable(dialog)[0] ?? dialog).focus()

    const keepFocusInside = (event) => {
      if (!overlay.contains(event.target)) (focusable(dialog)[0] ?? dialog).focus()
    }
    document.addEventListener('focusin', keepFocusInside)
    return () => {
      document.removeEventListener('focusin', keepFocusInside)
      restore()
      document.body.style.overflow = overflow
      previous?.focus?.()
    }
  }, [])

  const handleKeyDown = (event) => {
    if (event.key === 'Escape') {
      event.stopPropagation()
      if (!busyRef.current) closeRef.current()
      return
    }
    if (event.key !== 'Tab') return
    const items = focusable(dialogRef.current)
    if (items.length === 0) {
      event.preventDefault()
      return
    }
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

  return (
    <div
      ref={overlayRef}
      className={styles.overlay}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !busy) onClose()
      }}
    >
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={describedBy}
        aria-busy={busy || undefined}
        tabIndex={-1}
        onKeyDown={handleKeyDown}
      >
        <div className={styles.dialogHead}>
          <span className={`${styles.dialogIcon} ${tone === 'danger' ? styles.dialogIconDanger : ''}`}>
            <AnnIcon name={icon} size={18} />
          </span>
          <h2 id={titleId} className={styles.dialogTitle}>
            {title}
          </h2>
          <button
            type="button"
            className={styles.dialogClose}
            aria-label="Close dialog"
            onClick={() => {
              if (!busy) onClose()
            }}
            aria-disabled={busy || undefined}
          >
            <AnnIcon name="close" size={18} />
          </button>
        </div>
        <div className={styles.dialogBody}>{children}</div>
        <div className={styles.dialogFooter}>{footer}</div>
      </div>
    </div>
  )
}

/**
 * Accessible modal for the publish and delete confirmations: role="dialog" with a label, focus moved inside and
 * trapped, Esc and the X close it (not while busy), the page behind is inert, and focus returns to the opener.
 * Same behaviour as the shared ConfirmDialog, which cannot hold the summary box and chips these dialogs need.
 */
export function AnnouncementDialog({ open, ...props }) {
  if (!open) return null
  return createPortal(<DialogBody {...props} />, document.body)
}
