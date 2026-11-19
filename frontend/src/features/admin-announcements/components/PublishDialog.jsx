import { useId } from 'react'

import { Button } from '../../../shared/components/forms'
import { AUDIENCE_META, PIN_HOURS } from '../config/announcementConfig'
import { formatDateTime, timeZoneLabel } from '../utils/datetime'
import { AnnIcon } from './AnnIcon'
import { AnnouncementDialog } from './AnnouncementDialog'
import styles from '../announcements.module.css'

/**
 * Publish confirmation. Shows what will happen: title, audience (label only, no recipient estimate: there is no API
 * for it), timing (now or the scheduled local time with its time zone) and the delivery options.
 *
 * @param {{ title: string, audience: string, scheduledAt: string|null, sendPush: boolean, pinToHeader: boolean }} summary
 */
export function PublishDialog({ open, summary, publishing, onCancel, onConfirm }) {
  const infoId = useId()
  if (!summary) return null
  const scheduled = Boolean(summary.scheduledAt)
  const options = []
  if (summary.sendPush) options.push('Push notification alert')
  if (summary.pinToHeader) options.push(`Pinned to the user portal header for ${PIN_HOURS} hours`)

  return (
    <AnnouncementDialog
      open={open}
      title="Publish announcement"
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
            {scheduled ? 'Schedule' : 'Publish'}
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
            <dt>Delivery options</dt>
            <dd>{options.length > 0 ? options.join(' · ') : 'No push alert, not pinned'}</dd>
          </div>
        </div>
      </dl>
      <p id={infoId} className={styles.dialogInfo}>
        <AnnIcon name="info" size={15} />
        {scheduled ? 'This will be sent at the scheduled time.' : 'This will be sent to the selected users.'}
      </p>
    </AnnouncementDialog>
  )
}
