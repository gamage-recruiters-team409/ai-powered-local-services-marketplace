import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'

import { EmptyState, PageError, SkeletonTable, useToast } from '../../../shared/components/feedback'
import { Button, DataTable, Pagination } from '../../../shared/components/forms'
import { AdminPage } from '../../../shared/layouts/admin'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import { AnnIcon } from '../components/AnnIcon'
import { AudienceChip, StatusChip } from '../components/Chips'
import { DeleteDialog } from '../components/DeleteDialog'
import { FilterPopover } from '../components/FilterPopover'
import { SummaryCards } from '../components/SummaryCards'
import { announcementPaths, deliveryLine, LIMITS, STATUS, TABS } from '../config/announcementConfig'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import {
  deleteAnnouncement,
  getAnnouncementSummary,
  isRequestCancelled,
  listAnnouncements,
} from '../services/announcementService'
import { formatDate, formatDateTime, timeZoneLabel } from '../utils/datetime'
import { parseApiError } from '../utils/errors'
import styles from '../announcements.module.css'

const BREADCRUMBS = ['Management', 'Announcements']

const EMPTY_COPY = {
  all: { title: 'No announcements yet', message: 'Create your first announcement to inform customers and providers.' },
  published: { title: 'Nothing published yet', message: 'Published announcements will appear here.' },
  scheduled: { title: 'No scheduled announcements', message: 'Announcements set for a later time will appear here.' },
  drafts: { title: 'No drafts', message: 'Announcements saved as drafts will appear here.' },
}

function scheduleText(item) {
  if (item.status === STATUS.PUBLISHED) return formatDateTime(item.publishedAt)
  if (item.status === STATUS.SCHEDULED) return formatDateTime(item.scheduledAt)
  return item.updatedAt ? `Edited ${formatDate(item.updatedAt)}` : ''
}

/** Tabs with counts; arrow keys move between them (WAI-ARIA tabs pattern). */
function StatusTabs({ tabId, counts, onChange }) {
  const refs = useRef([])
  const onKeyDown = (event, index) => {
    const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
    if (!step) return
    event.preventDefault()
    const next = (index + step + TABS.length) % TABS.length
    refs.current[next]?.focus()
    onChange(TABS[next].id)
  }
  return (
    <div className={styles.tabs} role="tablist" aria-label="Announcement status">
      {TABS.map((tab, index) => {
        const selected = tab.id === tabId
        return (
          <button
            key={tab.id}
            ref={(el) => {
              refs.current[index] = el
            }}
            type="button"
            role="tab"
            id={`ann-tab-${tab.id}`}
            aria-selected={selected}
            aria-controls="ann-tabpanel"
            tabIndex={selected ? 0 : -1}
            className={`${styles.tab} ${selected ? styles.tabSelected : ''}`}
            onClick={() => onChange(tab.id)}
            onKeyDown={(event) => onKeyDown(event, index)}
          >
            {tab.label}
            <span className={styles.tabCount}>{counts ? counts[tab.countKey] : '–'}</span>
          </button>
        )
      })}
    </div>
  )
}

/** Screen 1: announcements list with summary, tabs, search, filter, table and pagination. */
export function AnnouncementsListPage() {
  const { canEdit } = useAreaAccess()
  const toast = useToast()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const tabId = TABS.some((tab) => tab.id === params.get('tab')) ? params.get('tab') : 'all'
  const tab = TABS.find((item) => item.id === tabId)

  const [search, setSearch] = useState('')
  const q = useDebouncedValue(search.trim(), 300)
  const [audience, setAudience] = useState('')
  const [page, setPage] = useState(1)
  const [reloadKey, setReloadKey] = useState(0)
  const [result, setResult] = useState({ key: null, data: null, error: null })
  const [summary, setSummary] = useState(null)
  const [toDelete, setToDelete] = useState(null)
  const [deleting, setDeleting] = useState(false)

  const requestKey = `${tab.status}|${audience}|${q}|${page}|${reloadKey}`
  const loading = result.key !== requestKey

  useEffect(() => {
    const controller = new AbortController()
    listAnnouncements(
      { status: tab.status ?? undefined, audience: audience || undefined, q: q || undefined, page: page - 1, size: LIMITS.PAGE_SIZE },
      controller.signal,
    )
      .then((data) => setResult({ key: requestKey, data, error: null }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setResult({ key: requestKey, data: null, error: parseApiError(error) })
      })
    return () => controller.abort()
  }, [tab.status, audience, q, page, reloadKey, requestKey])

  useEffect(() => {
    const controller = new AbortController()
    getAnnouncementSummary(controller.signal)
      .then(setSummary)
      .catch(() => {
        // The cards just show "–"; the list has its own error state.
      })
    return () => controller.abort()
  }, [reloadKey])

  const reload = () => setReloadKey((key) => key + 1)

  const changeTab = (id) => {
    setPage(1)
    setParams(id === 'all' ? {} : { tab: id }, { replace: true })
  }

  const clearFilters = () => {
    setAudience('')
    setSearch('')
    changeTab('all')
  }

  const confirmDelete = async () => {
    const target = toDelete
    setDeleting(true)
    try {
      await deleteAnnouncement(target.id, target.version)
      toast.success(`"${target.title}" was deleted.`)
      if ((result.data?.content?.length ?? 0) <= 1 && page > 1) setPage((p) => p - 1)
    } catch (error) {
      toast.error(parseApiError(error).message)
    } finally {
      setDeleting(false)
      setToDelete(null)
      reload()
    }
  }

  const data = loading ? null : result.data
  const total = data?.totalElements ?? 0
  const from = total === 0 ? 0 : (page - 1) * LIMITS.PAGE_SIZE + 1
  const to = Math.min(page * LIMITS.PAGE_SIZE, total)
  const filtered = Boolean(q) || Boolean(audience)

  const columns = useMemo(
    () => [
      {
        key: 'title',
        header: 'Title & delivery details',
        render: (_, item) => (
          <div className={styles.titleCell}>
            <Link to={announcementPaths.view(item.id)} className={styles.titleLink}>
              {item.title}
            </Link>
            <span className={styles.subtle}>{deliveryLine(item)}</span>
          </div>
        ),
      },
      { key: 'audience', header: 'Target audience', render: (value) => <AudienceChip audience={value} /> },
      {
        key: 'status',
        header: 'Status & schedule',
        render: (value, item) => (
          <div className={styles.statusCell}>
            <StatusChip status={value} />
            <span className={styles.subtle}>{scheduleText(item)}</span>
          </div>
        ),
      },
    ],
    [],
  )

  const rowActions = (item) => {
    const editable = canEdit
    const deletable = canEdit && item.status !== STATUS.PUBLISHED
    return (
      <div className={styles.rowActions}>
        {item.status === STATUS.PUBLISHED && (
          <Link to={announcementPaths.view(item.id)} className={styles.actionLink} aria-label={`View ${item.title}`}>
            View
          </Link>
        )}
        {!editable && item.status !== STATUS.PUBLISHED && (
          <Link to={announcementPaths.view(item.id)} className={styles.actionLink} aria-label={`View ${item.title}`}>
            View
          </Link>
        )}
        {editable && (
          <Link to={announcementPaths.edit(item.id)} className={styles.actionLink} aria-label={`Edit ${item.title}`}>
            Edit
          </Link>
        )}
        {deletable && (
          <button
            type="button"
            className={styles.actionDelete}
            aria-label={`Delete ${item.title}`}
            onClick={() => setToDelete(item)}
          >
            Delete
          </button>
        )}
      </div>
    )
  }

  const emptyCopy = filtered
    ? { title: 'No announcements match', message: 'Try another search or clear the filters.' }
    : EMPTY_COPY[tabId]

  return (
    <AdminPage
      title="Announcements"
      subtitle="Broadcast platform notices, policy updates, and maintenance alerts to users."
      breadcrumbs={BREADCRUMBS}
      plain
      actions={
        <div className={styles.headerActions}>
          <SummaryCards summary={summary} />
          {canEdit && (
            <Button onClick={() => navigate(announcementPaths.create())} icon={<AnnIcon name="plus" size={16} />}>
              New announcement
            </Button>
          )}
        </div>
      }
    >
      <section className={styles.card} aria-labelledby="ann-list-title">
        <div className={styles.cardHeader}>
          <div>
            <h2 id="ann-list-title" className={styles.cardTitle}>
              Past announcements
              <span className={styles.recordedBadge}>{summary ? `${summary.total} recorded` : '…'}</span>
            </h2>
            <p className={styles.cardSubtitle}>
              Manage platform updates, scheduled announcements, and draft notifications. Times shown in {timeZoneLabel()}.
            </p>
          </div>
          <div className={styles.toolbarRight}>
            <label className={styles.searchBox}>
              <AnnIcon name="search" size={15} />
              <span className="fb-sr-only">Search by title</span>
              <input
                type="search"
                value={search}
                maxLength={LIMITS.SEARCH}
                placeholder="Search by title..."
                onChange={(event) => {
                  setSearch(event.target.value)
                  setPage(1)
                }}
              />
            </label>
            <FilterPopover
              audience={audience}
              tabId={tabId}
              onAudienceChange={(value) => {
                setAudience(value)
                setPage(1)
              }}
              onTabChange={changeTab}
              onClear={clearFilters}
            />
          </div>
        </div>

        <StatusTabs tabId={tabId} counts={summary?.tabs} onChange={changeTab} />

        <div id="ann-tabpanel" role="tabpanel" aria-labelledby={`ann-tab-${tabId}`} className={styles.tabPanel}>
          {result.error && !loading ? (
            <PageError
              statusCode={result.error.status}
              title="Announcements could not be loaded"
              message={result.error.message}
              onRetry={reload}
            />
          ) : (
            <DataTable
              className={styles.table}
              columns={columns}
              data={data?.content ?? []}
              actions={rowActions}
              loading={loading}
              loadingContent={<SkeletonTable rows={LIMITS.PAGE_SIZE} columns={4} />}
              emptyContent={
                <EmptyState
                  variant={filtered ? 'search' : 'default'}
                  title={emptyCopy.title}
                  message={emptyCopy.message}
                  actionLabel={filtered ? 'Clear filters' : canEdit ? 'New announcement' : undefined}
                  onAction={filtered ? clearFilters : canEdit ? () => navigate(announcementPaths.create()) : undefined}
                  compact
                />
              }
            />
          )}
        </div>

        {!result.error && total > 0 && (
          <div className={styles.paginationRow}>
            <p className={styles.subtle} aria-live="polite">
              Showing {from}–{to} of {total} announcements
            </p>
            <Pagination
              currentPage={page}
              totalPages={data?.totalPages ?? 1}
              onPageChange={setPage}
              disabled={loading}
            />
          </div>
        )}
      </section>

      <DeleteDialog
        open={Boolean(toDelete)}
        announcement={toDelete}
        deleting={deleting}
        onCancel={() => setToDelete(null)}
        onConfirm={confirmDelete}
      />
    </AdminPage>
  )
}
