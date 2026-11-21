import { useId } from 'react'

import { Button } from '../../../shared/components/forms'
import { AUDIENCE_META, DELIVERY_PENDING_NOTE, isScheduledPublish, PIN_HOURS } from '../config/announcementConfig'
import { formatDateTime, timeZoneLabel } from '../utils/datetime'
import { AnnIcon } from './AnnIcon'
import { AnnouncementDialog } from './AnnouncementDialog'
import styles from '../announcements.module.css'

/**
 * Publish confirmation. Shows what will happen: title, audience (label only, no recipient estimate: there is no API
 * for it), timing and the requested delivery options.
 *
 * Timing follows the backend rule exactly: only a scheduled time MORE than 1 minute ahead is a scheduled publication;
 * anything else is "Publish now" (the form already rejects such times, this is the last line of defence).
 * User delivery is not connected yet, so the dialog says the announcement is saved and its status changes, and that
 * nothing is sent to users.
 *
 * @param {{ title: string, audience: string, scheduledAt: string|null, sendPush: boolean, pinToHeader: boolean }} summary
 */
export function PublishDialog({ open, summary, publishing, onCancel, onConfirm, now }) {
  const infoId = useId()
  if (!summary) return null
  const scheduled = isScheduledPublish(summary.scheduledAt, now ?? new Date())
  const options = []
  if (summary.sendPush) options.push('Push notification alert')
  if (summary.pinToHeader) options.push(`Pin to the user portal header for ${PIN_HOURS} hours`)

  return (
    <AnnouncementDialog
      open={open}
      title={scheduled ? 'Schedule announcement' : 'Publish announcement now'}
      icon="megaphone"
      busy={publishing}
      onClose={onCancel}
      describedBy={infoId}
      footer={
        <>
          <Button variant="secondary" size="small" onClick={onCancel} disabled={publishing}>
            Cancel
          </Button>
          <Button size="small" onClick={onConfirm} loading={publishing} icon={<AnnIcon name="send" size={15} />}>
            {scheduled ? 'Schedule' : 'Publish now'}
          </Button>
        </>
      }
    >
      <dl className={styles.summaryBox}>
        <div className={styles.summaryTitle}>
          <dt>Announcement title</dt>
          <dd>{summary.title}</dd>
        </div>
        <div className={styles.summaryRow}>
          <AnnIcon name="users" size={16} />
          <div>
            <dt>Target audience</dt>
            <dd>{AUDIENCE_META[summary.audience]?.label ?? summary.audience}</dd>
          </div>
        </div>
        <div className={styles.summaryRow}>
          <AnnIcon name="clock" size={16} />
          <div>
            <dt>Timing</dt>
            <dd>
              {scheduled
                ? `Scheduled: ${formatDateTime(summary.scheduledAt)} (${timeZoneLabel(new Date(summary.scheduledAt))})`
                : 'Publish now'}
            </dd>
          </div>
        </div>
        <div className={styles.summaryRow}>
          <AnnIcon name="bell" size={16} />
          <div>
            <dt>Requested delivery options</dt>
            <dd>{options.length > 0 ? options.join(' · ') : 'No push alert, not pinned'}</dd>
          </div>
        </div>
      </dl>
      <div id={infoId} className={styles.dialogInfo}>
        <AnnIcon name="info" size={15} />
        <p>
          {scheduled
            ? 'The announcement is saved as Scheduled and changes to Published at the scheduled time.'
            : 'The announcement is saved and its status changes to Published now.'}{' '}
          {DELIVERY_PENDING_NOTE}
        </p>
      </div>
    </AnnouncementDialog>
  )
}
