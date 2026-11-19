import { useEffect, useId, useRef, useState } from 'react'
import { Link, useBlocker, useNavigate, useParams } from 'react-router-dom'

import { Alert, ConfirmDialog, PageError, SkeletonCard, useToast } from '../../../shared/components/feedback'
import { Button, Checkbox, Input, Select } from '../../../shared/components/forms'
import { AdminPage } from '../../../shared/layouts/admin'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import { AnnIcon } from '../components/AnnIcon'
import { StatusChip } from '../components/Chips'
import { MarkdownEditor } from '../components/MarkdownEditor'
import { PublishDialog } from '../components/PublishDialog'
import { ViewOnlyAccess } from '../components/ViewOnlyAccess'
import {
  announcementPaths,
  AUDIENCE,
  AUDIENCE_OPTIONS,
  DELIVERY_PENDING_NOTE,
  INTENT,
  LIMITS,
  PIN_HOURS,
  STATUS,
  tabForStatus,
} from '../config/announcementConfig'
import { createAnnouncement, getAnnouncement, isRequestCancelled, updateAnnouncement } from '../services/announcementService'
import { formatDateTime, localInputToUtcIso, timeZoneLabel, toLocalInputValue } from '../utils/datetime'
import { parseApiError, STALE_CODES } from '../utils/errors'
import { validateAnnouncement } from '../utils/validation'
import styles from '../announcements.module.css'

const EMPTY_FORM = Object.freeze({
  title: '',
  audience: AUDIENCE.ALL_USERS,
  scheduledLocal: '',
  message: '',
  sendPush: true,
  pinToHeader: false,
})

function toForm(announcement) {
  return {
    title: announcement.title ?? '',
    audience: announcement.audience ?? AUDIENCE.ALL_USERS,
    scheduledLocal: toLocalInputValue(announcement.scheduledAt),
    message: announcement.message ?? '',
    sendPush: Boolean(announcement.sendPush),
    pinToHeader: Boolean(announcement.pinToHeader),
  }
}

/** Backend field names -> form field names (scheduledAt is the datetime input). */
function mapFieldErrors(fieldErrors) {
  const errors = {}
  for (const [field, message] of Object.entries(fieldErrors)) {
    errors[field === 'scheduledAt' ? 'scheduledAt' : field] = message
  }
  return errors
}

function LockedNote() {
  return (
    <span className={styles.lockedNote}>
      <AnnIcon name="lock" size={12} /> Locked after publishing
    </span>
  )
}

/** New / edit form. Loads the announcement first when editing. */
function AnnouncementForm({ id }) {
  const isEdit = Boolean(id)
  const toast = useToast()
  const navigate = useNavigate()
  const formId = useId()

  const [loadResult, setLoadResult] = useState({ key: null, data: null, error: null })
  const [loadKey, setLoadKey] = useState(0)
  const [form, setForm] = useState(EMPTY_FORM)
  const [initial, setInitial] = useState(EMPTY_FORM)
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState(null)
  const [saving, setSaving] = useState(null)
  const [publishOpen, setPublishOpen] = useState(false)
  const allowLeave = useRef(false)

  const requestKey = isEdit ? `${id}|${loadKey}` : null
  const loading = isEdit && loadResult.key !== requestKey

  useEffect(() => {
    if (!isEdit) return undefined
    const controller = new AbortController()
    getAnnouncement(id, controller.signal)
      .then((data) => {
        setLoadResult({ key: requestKey, data, error: null })
        setForm(toForm(data))
        setInitial(toForm(data))
      })
      .catch((error) => {
        if (!isRequestCancelled(error)) setLoadResult({ key: requestKey, data: null, error: parseApiError(error) })
      })
    return () => controller.abort()
  }, [id, isEdit, requestKey])

  const original = isEdit ? loadResult.data : null
  const isPublished = original?.status === STATUS.PUBLISHED
  // For a scheduled announcement, saving as a draft cancels the schedule: say so on the button.
  const isScheduled = original?.status === STATUS.SCHEDULED
  const currentStatus = original?.status ?? STATUS.DRAFT
  const dirty = JSON.stringify(form) !== JSON.stringify(initial)
  const busy = saving !== null

  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      dirty && !allowLeave.current && currentLocation.pathname !== nextLocation.pathname,
  )

  useEffect(() => {
    if (!dirty) return undefined
    const warn = (event) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const set = (field) => (value) => {
    setForm((current) => ({ ...current, [field]: value }))
    setErrors((current) => (current[field] ? { ...current, [field]: undefined } : current))
  }

  const focusFirstError = () => {
    requestAnimationFrame(() => {
      document.getElementById(formId)?.querySelector('[aria-invalid="true"]')?.focus()
    })
  }

  const check = (intent) => {
    const found = validateAnnouncement(form, { intent, lockedSchedule: isPublished })
    setErrors(found)
    if (Object.keys(found).length > 0) {
      focusFirstError()
      return false
    }
    return true
  }

  const body = (intent) => {
    // The version this form was loaded with: if someone changed the announcement since, the backend answers 409
    // ANNOUNCEMENT_UPDATE_CONFLICT instead of overwriting their change.
    const loadedVersion = isEdit ? { version: original.version } : {}
    if (isPublished) {
      // Only title and message may change; everything else is sent exactly as stored.
      return {
        title: form.title.trim(),
        message: form.message,
        audience: original.audience,
        scheduledAt: original.scheduledAt ?? null,
        sendPush: original.sendPush,
        pinToHeader: original.pinToHeader,
        intent: INTENT.PUBLISH,
        ...loadedVersion,
      }
    }
    return {
      title: form.title.trim(),
      message: form.message,
      audience: form.audience,
      scheduledAt: localInputToUtcIso(form.scheduledLocal),
      sendPush: form.sendPush,
      pinToHeader: form.pinToHeader,
      intent,
      ...loadedVersion,
    }
  }

  const save = async (intent) => {
    setSaving(intent)
    setServerError(null)
    try {
      const saved = isEdit ? await updateAnnouncement(id, body(intent)) : await createAnnouncement(body(intent))
      allowLeave.current = true
      if (isPublished) {
        toast.success('Changes saved.')
        navigate(announcementPaths.view(saved.id))
        return
      }
      if (saved.status === STATUS.SCHEDULED) {
        toast.success(`Announcement scheduled for ${formatDateTime(saved.scheduledAt)}.`)
      } else if (saved.status === STATUS.PUBLISHED) {
        toast.success('Announcement published. Delivery to users is pending notification integration.')
      } else {
        toast.success(isScheduled ? 'Moved to drafts. It will not be published automatically.' : 'Draft saved.')
      }
      navigate(announcementPaths.list(tabForStatus(saved.status)))
    } catch (error) {
      const parsed = parseApiError(error)
      const fieldErrors = mapFieldErrors(parsed.fieldErrors)
      setErrors(fieldErrors)
      setServerError(parsed)
      toast.error(parsed.message)
      if (Object.keys(fieldErrors).length > 0) focusFirstError()
    } finally {
      setSaving(null)
      setPublishOpen(false)
    }
  }

  const onSaveDraft = () => {
    if (check(INTENT.DRAFT)) save(INTENT.DRAFT)
  }

  const onPublish = () => {
    if (!check(INTENT.PUBLISH)) return
    if (isPublished) save(INTENT.PUBLISH)
    else setPublishOpen(true)
  }

  // Time passes while the dialog is open: a schedule that was more than 1 minute ahead may no longer be. Check again
  // so the admin never confirms "Schedule" and gets an immediately published announcement.
  const onConfirmPublish = () => {
    if (!check(INTENT.PUBLISH)) {
      setPublishOpen(false)
      return
    }
    save(INTENT.PUBLISH)
  }

  const reloadLatest = () => {
    setServerError(null)
    setErrors({})
    setLoadKey((key) => key + 1)
  }

  if (loading) {
    return (
      <AdminPage title="Edit announcement" breadcrumbs={['Management', 'Announcements', 'Edit announcement']} plain>
        <SkeletonCard />
      </AdminPage>
    )
  }

  if (isEdit && loadResult.error) {
    const notFound = loadResult.error.status === 404
    return (
      <AdminPage title="Edit announcement" breadcrumbs={['Management', 'Announcements', 'Edit announcement']} plain>
        <PageError
          statusCode={loadResult.error.status}
          title={notFound ? 'Announcement not found' : 'The announcement could not be loaded'}
          message={loadResult.error.message}
          onRetry={notFound ? undefined : () => setLoadKey((key) => key + 1)}
        />
        <Link to={announcementPaths.list()} className={styles.backLink}>
          <AnnIcon name="arrowLeft" size={15} /> Back to announcements
        </Link>
      </AdminPage>
    )
  }

  const pageLabel = isEdit ? 'Edit announcement' : 'New announcement'
  const scheduledIso = localInputToUtcIso(form.scheduledLocal)

  return (
    <AdminPage
      title={pageLabel}
      subtitle="Create, format, and schedule an official platform broadcast for users"
      breadcrumbs={[
        'Management',
        { label: 'Announcements', to: announcementPaths.list() },
        pageLabel,
      ]}
      actions={<StatusChip status={currentStatus} />}
      plain
    >
      <form
        id={formId}
        className={styles.formCard}
        noValidate
        aria-busy={busy || undefined}
        onSubmit={(event) => {
          event.preventDefault()
          onPublish()
        }}
      >
        <div className={styles.formCardHeader}>
          <div className={styles.formCardHeading}>
            <span className={styles.formCardIcon} aria-hidden="true">
              <AnnIcon name="megaphone" size={16} />
            </span>
            <div>
              <h2 className={styles.formCardTitle}>Broadcast Details &amp; Configuration</h2>
              <p className={styles.formCardHint}>Write the message, choose who receives it, and when.</p>
            </div>
          </div>
          <span className={styles.requiredHint}>Fields marked with * are required</span>
        </div>

        {serverError && !Object.keys(serverError.fieldErrors).length && (
          <Alert variant="error" title="Not saved" inline onClose={() => setServerError(null)}>
            {serverError.message}
            {isEdit && STALE_CODES.includes(serverError.code) && (
              <span className={styles.alertAction}>
                <Button variant="outline" size="small" onClick={reloadLatest} icon={<AnnIcon name="refresh" size={14} />}>
                  Reload latest version
                </Button>
                <span className={styles.subtle}>Your unsaved changes on this page will be replaced.</span>
              </span>
            )}
          </Alert>
        )}

        <div className={styles.formBody}>
          <div className={styles.titleField}>
            <Input
              label="Announcement title"
              required
              value={form.title}
              maxLength={LIMITS.TITLE + 20}
              onChange={(event) => set('title')(event.target.value)}
              placeholder="e.g. Platform Terms of Service Update - Spring 2026"
              error={errors.title}
              disabled={busy}
            />
            <span className={`${styles.counterWrap} ${styles.titleCounter}`}>
              <span className={styles.meter} aria-hidden="true">
                <span
                  className={`${styles.meterFill} ${form.title.trim().length > LIMITS.TITLE ? styles.meterOver : ''}`}
                  style={{ width: `${Math.min(100, (form.title.trim().length / LIMITS.TITLE) * 100)}%` }}
                />
              </span>
              <span
                className={`${styles.counter} ${form.title.trim().length > LIMITS.TITLE ? styles.counterOver : ''}`}
                aria-live="polite"
              >
                {form.title.trim().length} / {LIMITS.TITLE} characters
              </span>
            </span>
          </div>

          <div className={styles.twoColumns}>
            <div>
              <Select
                label="Target audience"
                required
                options={AUDIENCE_OPTIONS}
                value={form.audience}
                onChange={(event) => set('audience')(event.target.value)}
                error={errors.audience}
                disabled={busy || isPublished}
                helperText={isPublished ? undefined : 'Who will receive this announcement.'}
              />
              {isPublished && <LockedNote />}
            </div>
            <div>
              <Input
                label="Scheduled dispatch"
                type="datetime-local"
                value={form.scheduledLocal}
                min={isPublished ? undefined : toLocalInputValue(new Date())}
                onChange={(event) => set('scheduledLocal')(event.target.value)}
                error={errors.scheduledAt}
                disabled={busy || isPublished}
                helperText={
                  isPublished
                    ? undefined
                    : `Times shown in ${timeZoneLabel()}. Leave empty to publish immediately; to schedule, pick a time more than 1 minute from now.`
                }
              />
              {isPublished && <LockedNote />}
            </div>
          </div>

          <MarkdownEditor
            value={form.message}
            onChange={set('message')}
            title={form.title}
            error={errors.message}
            disabled={busy}
          />

          <fieldset className={styles.deliveryBox} disabled={busy || isPublished}>
            <legend className={styles.deliveryLegend}>
              Broadcast delivery options
              {isPublished && <LockedNote />}
            </legend>
            <div className={styles.deliveryOption} data-checked={form.sendPush || undefined}>
              <span className={styles.deliveryIcon} aria-hidden="true">
                <AnnIcon name="bell" size={16} />
              </span>
              <Checkbox
                label="Send push notification alert"
                checked={form.sendPush}
                onChange={(event) => set('sendPush')(event.target.checked)}
                helperText="Request mobile and browser alerts to the target accounts at release time."
                disabled={busy || isPublished}
              />
            </div>
            <div className={styles.deliveryOption} data-checked={form.pinToHeader || undefined}>
              <span className={styles.deliveryIcon} aria-hidden="true">
                <AnnIcon name="pin" size={16} />
              </span>
              <Checkbox
                label="Pin to user portal header"
                checked={form.pinToHeader}
                onChange={(event) => set('pinToHeader')(event.target.checked)}
                helperText={`Request a sticky dashboard banner for ${PIN_HOURS} hours after publishing.`}
                disabled={busy || isPublished}
              />
            </div>
            <p className={styles.pendingNote}>
              <AnnIcon name="info" size={14} />
              <span>{DELIVERY_PENDING_NOTE} These options are saved with the announcement.</span>
            </p>
          </fieldset>
        </div>
      </form>

      <div className={styles.formFooter}>
        <Link to={isEdit ? announcementPaths.view(id) : announcementPaths.list()} className={styles.backLink}>
          <AnnIcon name="arrowLeft" size={15} /> Cancel
        </Link>
        <div className={styles.footerButtons}>
          {!isPublished && (
            <Button
              variant="secondary"
              onClick={onSaveDraft}
              loading={saving === INTENT.DRAFT}
              disabled={busy}
              icon={<AnnIcon name="bookmark" size={15} />}
              title={isScheduled ? 'Cancels the schedule and keeps it as a draft' : undefined}
            >
              {isScheduled ? 'Move to drafts' : 'Save draft'}
            </Button>
          )}
          <Button
            type="submit"
            form={formId}
            loading={isPublished && saving === INTENT.PUBLISH}
            disabled={busy}
            icon={<AnnIcon name={isPublished ? 'check' : 'send'} size={15} />}
          >
            {isPublished ? 'Save changes' : 'Publish announcement'}
          </Button>
        </div>
      </div>

      <PublishDialog
        open={publishOpen}
        summary={{
          title: form.title.trim(),
          audience: form.audience,
          scheduledAt: scheduledIso,
          sendPush: form.sendPush,
          pinToHeader: form.pinToHeader,
        }}
        publishing={saving === INTENT.PUBLISH}
        onCancel={() => setPublishOpen(false)}
        onConfirm={onConfirmPublish}
      />

      <ConfirmDialog
        open={blocker.state === 'blocked'}
        title="Discard unsaved changes?"
        message="You have changes that are not saved. If you leave now, they will be lost."
        confirmLabel="Leave without saving"
        cancelLabel="Stay on page"
        variant="destructive"
        onConfirm={() => blocker.proceed?.()}
        onCancel={() => blocker.reset?.()}
      />
    </AdminPage>
  )
}

/** Screen 2: /new and /:id/edit. SUPER_ADMIN (read-only) gets a view-only message instead of the form. */
export function AnnouncementFormPage() {
  const { id } = useParams()
  const { canEdit } = useAreaAccess()
  if (!canEdit) {
    return <ViewOnlyAccess title={id ? 'Edit announcement' : 'New announcement'} />
  }
  return <AnnouncementForm key={id ?? 'new'} id={id} />
}
