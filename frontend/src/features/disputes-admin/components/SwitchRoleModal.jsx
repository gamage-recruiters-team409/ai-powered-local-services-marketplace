import { useEffect, useId, useRef } from 'react'

import { Icon } from '../../../shared/layouts/admin'
import styles from '../adminFeature.module.css'
import { RoleAreaCards } from './RoleAreaCards'

/** Native <dialog>: focus is trapped, Esc closes, and focus returns to the opener. */
export function SwitchRoleModal({ open, onClose }) {
  const dialogRef = useRef(null)
  const titleId = useId()
  const textId = useId()

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) {
      return
    }
    if (open && !dialog.open) {
      dialog.showModal()
    } else if (!open && dialog.open) {
      dialog.close()
    }
  }, [open])

  // Close only for clicks on the backdrop, i.e. outside the dialog box. A click on the dialog's own
  // scrollbar also has the dialog as its target, so check the position, not just the target.
  const handleBackdropClick = (event) => {
    const dialog = dialogRef.current
    if (!dialog || event.target !== dialog) {
      return
    }
    const box = dialog.getBoundingClientRect()
    const inside =
      event.clientX >= box.left && event.clientX <= box.right && event.clientY >= box.top && event.clientY <= box.bottom
    if (!inside) {
      onClose()
    }
  }

  return (
    <dialog
      ref={dialogRef}
      className={styles.dialog}
      aria-labelledby={titleId}
      aria-describedby={textId}
      onClose={onClose}
      onClick={handleBackdropClick}
    >
      <div className={styles.dialogInner}>
        <div className={styles.dialogHeader}>
          <h2 id={titleId} className={styles.dialogTitle}>
            Switch role
          </h2>
          <button type="button" className={styles.closeButton} aria-label="Close" onClick={onClose}>
            <Icon name="close" size={20} />
          </button>
        </div>
        <p id={textId} className={styles.dialogText}>
          Choose which area to open. View only areas are read-only, and locked areas aren&apos;t available to your role.
        </p>
        {open && <RoleAreaCards compact onSelect={onClose} />}
      </div>
    </dialog>
  )
}
