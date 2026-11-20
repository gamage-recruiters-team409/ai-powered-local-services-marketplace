import { useId } from 'react'

import { Button } from '../../../shared/components/forms'
import { STATUS } from '../config/announcementConfig'
import { AnnIcon } from './AnnIcon'
import { AnnouncementDialog } from './AnnouncementDialog'
import { StatusChip } from './Chips'
import styles from '../announcements.module.css'

/** Delete confirmation for a DRAFT or SCHEDULED announcement. */
export function DeleteDialog({ open, announcement, deleting, onCancel, onConfirm }) {
  const textId = useId()
  if (!announcement) return null
  const scheduled = announcement.status === STATUS.SCHEDULED

  return (
    <AnnouncementDialog
      open={open}
      title={scheduled ? 'Delete scheduled announcement' : 'Delete draft'}
      icon="trash"
      tone="danger"
      busy={deleting}
      onClose={onCancel}
      describedBy={textId}
      footer={
        <>
          <Button variant="secondary" size="small" onClick={onCancel} disabled={deleting} data-autofocus="">
            Cancel
          </Button>
          <Button
            variant="destructive"
            size="small"
            onClick={onConfirm}
            loading={deleting}
            icon={<AnnIcon name="trash" size={15} />}
          >
            Delete
          </Button>
        </>
      }
    >
      <p id={textId} className={styles.dialogText}>
        Delete <strong>{announcement.title}</strong>? This can&apos;t be undone.
      </p>
      <p className={styles.warningBox}>
        <AnnIcon name="alertTriangle" size={16} />
        This announcement and its settings will be permanently removed.
      </p>
      <div className={styles.dialogMeta}>
        <span className={styles.dialogMetaTitle}>{announcement.title}</span>
        <StatusChip status={announcement.status} />
      </div>
    </AnnouncementDialog>
  )
}
