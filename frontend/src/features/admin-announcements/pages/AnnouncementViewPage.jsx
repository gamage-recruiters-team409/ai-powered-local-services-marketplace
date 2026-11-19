import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { PageError, SkeletonCard, useToast } from '../../../shared/components/feedback'
import { Button } from '../../../shared/components/forms'
import { AdminPage } from '../../../shared/layouts/admin'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import { AnnIcon } from '../components/AnnIcon'
import { AudienceChip, InfoChip, StatusChip } from '../components/Chips'
import { DeleteDialog } from '../components/DeleteDialog'
import { MarkdownContent } from '../components/MarkdownContent'
import {
  announcementPaths,
  DELIVERY_PENDING_NOTE,
  PIN_HOURS,
  shortReference,
  STATUS,
} from '../config/announcementConfig'
import { deleteAnnouncement, getAnnouncement, isRequestCancelled } from '../services/announcementService'
import { formatDateTime, timeZoneLabel } from '../utils/datetime'
import { parseApiError, STALE_CODES } from '../utils/errors'
import styles from '../announcements.module.css'

const BREADCRUMBS = ['Management', { label: 'Announcements', to: announcementPaths.list() }, 'View announcement']

function dateInfo(a) {
  if (a.status === STATUS.PUBLISHED) return { label: 'Published on', value: formatDateTime(a.publishedAt) }
  if (a.status === STATUS.SCHEDULED) return { label: 'Scheduled for', value: formatDateTime(a.scheduledAt) }
  return { label: 'Last edited', value: formatDateTime(a.updatedAt) }
}

/** What the admin asked for. Worded as requests: nothing is delivered until notifications are integrated. */
function deliveryChips(a) {
  const chips = []
  if (a.sendPush) chips.push({ icon: 'bell', text: 'Push alert requested' })
  if (a.pinToHeader) {
    chips.push(
      a.pinExpiresAt
        ? { icon: 'pin', text: `Header pin requested until ${formatDateTime(a.pinExpiresAt)}` }
        : { icon: 'pin', text: `Header pin requested for ${PIN_HOURS} hours after publishing` },
    )
  }
  if (chips.length === 0) chips.push({ icon: 'info', text: 'No push alert, not pinned' })
  return chips
}

/** Delivery status line. Never claims delivery: the backend delivery port is a stub and reports NOT_APPLIED. */
function deliveryStatus(a) {
  if (a.status === STATUS.PUBLISHED && a.deliveryState === 'NOT_APPLIED') {
    return `Published, not delivered to users. ${DELIVERY_PENDING_NOTE}`
  }
  return DELIVERY_PENDING_NOTE
}

/** Screen 3: one announcement, message rendered safely from markdown. No delivery statistics (no data yet). */
export function AnnouncementViewPage() {
  const { id } = useParams()
  const { canEdit } = useAreaAccess()
  const toast = useToast()
  const navigate = useNavigate()
  const [loadKey, setLoadKey] = useState(0)
  const [result, setResult] = useState({ key: null, data: null, error: null })
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const requestKey = `${id}|${loadKey}`
  const loading = result.key !== requestKey

  useEffect(() => {
    const controller = new AbortController()
    getAnnouncement(id, controller.signal)
      .then((data) => setResult({ key: requestKey, data, error: null }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setResult({ key: requestKey, data: null, error: parseApiError(error) })
      })
    return () => controller.abort()
  }, [id, requestKey])

  const confirmDelete = async () => {
    setDeleting(true)
    try {
      await deleteAnnouncement(id, result.data.version)
      toast.success(`"${result.data.title}" was deleted.`)
      navigate(announcementPaths.list())
    } catch (error) {
      const parsed = parseApiError(error)
      toast.error(parsed.message)
      setDeleteOpen(false)
      if (STALE_CODES.includes(parsed.code)) setLoadKey((key) => key + 1)
    } finally {
      setDeleting(false)
    }
  }

  if (loading) {
    return (
      <AdminPage title="View announcement" breadcrumbs={BREADCRUMBS} plain>
        <SkeletonCard />
      </AdminPage>
    )
  }

  if (result.error) {
    const notFound = result.error.status === 404
    return (
      <AdminPage title="View announcement" breadcrumbs={BREADCRUMBS} plain>
        <PageError
          statusCode={result.error.status}
          title={notFound ? 'Announcement not found' : 'The announcement could not be loaded'}
          message={result.error.message}
          onRetry={notFound ? undefined : () => setLoadKey((key) => key + 1)}
        />
        <Link to={announcementPaths.list()} className={styles.backLink}>
          <AnnIcon name="arrowLeft" size={15} /> Back to announcements
        </Link>
      </AdminPage>
    )
  }

  const a = result.data
  const date = dateInfo(a)
  const deletable = canEdit && a.status !== STATUS.PUBLISHED

  return (
    <AdminPage
      title={
        <span className={styles.viewTitle}>
          View announcement <span className={styles.refChip}>{shortReference(a.id)}</span>
        </span>
      }
      subtitle={`Times shown in ${timeZoneLabel()}`}
      breadcrumbs={BREADCRUMBS}
      actions={<StatusChip status={a.status} />}
      plain
    >
      <article className={styles.viewCard} aria-labelledby="ann-view-title" data-status={a.status}>
        <div className={styles.viewAccent} aria-hidden="true" />
        <header className={styles.viewHeader}>
          <span className={styles.viewIcon}>
            <AnnIcon name="megaphone" size={18} />
          </span>
          <h2 id="ann-view-title" className={styles.viewHeading}>
            {a.title}
          </h2>
        </header>

        <dl className={styles.infoStrip}>
          <div>
            <dt>
              <AnnIcon name="users" size={12} />
              Audience
            </dt>
            <dd>
              <AudienceChip audience={a.audience} />
            </dd>
          </div>
          <div>
            <dt>
              <AnnIcon name="calendar" size={12} />
              {date.label}
            </dt>
            <dd>{date.value || '—'}</dd>
          </div>
          <div>
            <dt>
              <AnnIcon name="userCircle" size={12} />
              {a.status === STATUS.PUBLISHED ? 'Published by' : 'Created by'}
            </dt>
            <dd className={styles.byline}>
              <span className={styles.avatar} aria-hidden="true">
                A
              </span>
              Admin
            </dd>
          </div>
        </dl>

        <MarkdownContent text={a.message} className={styles.viewMessage} />

        <div className={styles.deliveryChips} aria-label="Requested delivery options">
          {deliveryChips(a).map((chip) => (
            <InfoChip key={chip.text} icon={chip.icon}>
              {chip.text}
            </InfoChip>
          ))}
        </div>

        <p className={styles.trackingNote}>
          <AnnIcon name="info" size={15} />
          <span>
            {deliveryStatus(a)} Delivery tracking will be available when notifications are connected.
          </span>
        </p>
      </article>

      <div className={styles.formFooter}>
        <Link to={announcementPaths.list()} className={styles.backLink}>
          <AnnIcon name="arrowLeft" size={15} /> Back to announcements
        </Link>
        <div className={styles.footerButtons}>
          {deletable && (
            <Button variant="outline" onClick={() => setDeleteOpen(true)} icon={<AnnIcon name="trash" size={15} />}>
              Delete
            </Button>
          )}
          {canEdit && (
            <Button onClick={() => navigate(announcementPaths.edit(a.id))} icon={<AnnIcon name="pencil" size={15} />}>
              Edit announcement
            </Button>
          )}
        </div>
      </div>

      <DeleteDialog
        open={deleteOpen}
        announcement={a}
        deleting={deleting}
        onCancel={() => setDeleteOpen(false)}
        onConfirm={confirmDelete}
      />
    </AdminPage>
  )
}
