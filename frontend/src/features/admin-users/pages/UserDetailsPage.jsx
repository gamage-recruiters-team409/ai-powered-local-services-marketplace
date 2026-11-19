import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'

import { PageError, Skeleton } from '../../../shared/components/feedback'
import { Button } from '../../../shared/components/forms'
import { AdminPage } from '../../../shared/layouts/admin'
import { categoryLabel } from '../../disputes/config/disputeStatus'
import { ReactivateDialog, SuspendDialog } from '../components/AccountDialogs'
import { BarChart, DonutChart } from '../components/Charts'
import { Panel, PanelSkeleton, Unavailable } from '../components/Panel'
import { ReviewListingDialog } from '../components/ReviewListingDialog'
import {
  AdminActionChip,
  Avatar,
  BookingStatusChip,
  Chip,
  DisputeStatusChip,
  ListingStatusChip,
  ProChip,
  RoleChip,
  UserStatusChip,
  VerifiedChip,
} from '../components/UserChips'
import { UsersIcon } from '../components/UsersIcon'
import { reasonLabel, ROLE, shortId, shortUserId, userPaths } from '../config/usersConfig'
import { getUser, isRequestCancelled } from '../services/adminUsersService'
import { parseApiError } from '../utils/errors'
import { formatDate, formatDateTime, formatLkr, formatMonth, formatNumber, percent, plural } from '../utils/format'
import styles from '../adminUsers.module.css'

const SERVICE = Object.freeze({
  bookings: 'the booking service',
  reviews: 'the review service',
  disputes: 'the dispute service',
  listings: 'the listing service',
  audit: 'the audit log',
})

const shown = (section) => section && section.reason !== 'NOT_APPLICABLE'

function NotAvailable() {
  return (
    <>
      <span className={styles.metricValue} aria-hidden="true">
        —
      </span>
      <span className={styles.notAvailable}>Not available yet</span>
    </>
  )
}

function MetricCard({ label, icon, section, children }) {
  return (
    <article className={styles.metricCard}>
      <div className={styles.metricTop}>
        <h3 className={styles.metricLabel}>{label}</h3>
        <span className={styles.metricIcon} aria-hidden="true">
          <UsersIcon name={icon} size={15} />
        </span>
      </div>
      {section?.available ? children(section.data) : <NotAvailable />}
    </article>
  )
}

function ProfileCard({ user }) {
  const p = user.profile
  const provider = p.role === ROLE.PROVIDER
  const facts = [
    { label: 'User ID', value: shortUserId(p.id, p.role) },
    { label: 'Member since', value: formatDate(p.memberSince) },
    p.lastActive?.available ? { label: 'Last active', value: formatDateTime(p.lastActive.data) } : null,
    provider && p.serviceArea?.available
      ? { label: 'Service area', value: [p.serviceArea.data.city, p.serviceArea.data.district].filter(Boolean).join(', ') }
      : null,
  ].filter((f) => f && f.value)

  return (
    <section className={styles.profileCard} aria-label="Profile">
      <Avatar name={p.displayName} size="lg" />
      <div className={styles.profileMain}>
        <div className={styles.profileNameRow}>
          <h2 className={styles.profileName}>{p.displayName || 'Unnamed account'}</h2>
          <UserStatusChip status={p.status} />
          {p.pro?.available && p.pro.data === true && <ProChip />}
        </div>
        <div className={styles.profileChips}>
          <RoleChip role={p.role} />
          {provider && p.providerVerified?.available && p.providerVerified.data === true && <VerifiedChip />}
          {p.emailVerified && (
            <Chip tone="neutral" icon="mail">
              Email verified
            </Chip>
          )}
        </div>
        <ul className={styles.contactList}>
          {p.email && (
            <li>
              <UsersIcon name="mail" size={13} />
              {p.email}
            </li>
          )}
          {p.phone && (
            <li>
              <UsersIcon name="phone" size={13} />
              {p.phone}
            </li>
          )}
        </ul>
      </div>
      <dl className={styles.profileFacts}>
        {facts.map((f) => (
          <div key={f.label}>
            <dt>{f.label}</dt>
            <dd>{f.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

function Metrics({ user }) {
  const provider = user.profile.role === ROLE.PROVIDER
  return (
    <div className={styles.metricGrid}>
      <MetricCard label="Total bookings" icon="calendar" section={user.bookingStats}>
        {(d) => <span className={styles.metricValue}>{formatNumber(d.total)}</span>}
      </MetricCard>
      <MetricCard label="Completion rate" icon="checkCircle" section={user.bookingStats}>
        {(d) => {
          const rate = percent(d.completed, d.total)
          return (
            <>
              <span className={styles.metricValue}>{rate === null ? '—' : `${rate}%`}</span>
              {rate !== null && (
                <span className={styles.progress} aria-hidden="true">
                  <span style={{ width: `${rate}%` }} />
                </span>
              )}
              <span className={styles.metricSub}>{formatNumber(d.completed)} completed</span>
            </>
          )
        }}
      </MetricCard>
      {provider && (
        <MetricCard label="Avg. rating" icon="star" section={user.rating}>
          {(d) =>
            d.reviewCount > 0 ? (
              <>
                <span className={styles.metricValue}>
                  {d.averageRating.toFixed(1)}
                  <small> / 5.0</small>
                </span>
                <span className={styles.metricSub}>{plural(d.reviewCount, 'review')}</span>
              </>
            ) : (
              <>
                <span className={styles.metricValue}>—</span>
                <span className={styles.metricSub}>No reviews yet</span>
              </>
            )
          }
        </MetricCard>
      )}
      <MetricCard label="Cancellations" icon="xCircle" section={user.cancellations}>
        {(d) => (
          <>
            <span className={styles.metricValue}>{formatNumber(d.total)}</span>
            <span className={styles.metricSub}>{formatNumber(d.last30Days)} in the last 30 days</span>
          </>
        )}
      </MetricCard>
      <MetricCard label="Disputes" icon="alertCircle" section={user.disputes}>
        {(d) => (
          <>
            <span className={`${styles.metricValue} ${d.open > 0 ? styles.metricDanger : ''}`}>
              {formatNumber(d.lifetime)}
              <small> lifetime</small>
            </span>
            <span className={styles.metricSub}>{plural(d.open, 'open case')}</span>
          </>
        )}
      </MetricCard>
    </div>
  )
}

function BookingsPerMonth({ section }) {
  if (!section.available) {
    return <Unavailable what="Bookings per month" service={SERVICE.bookings} reason={section.reason} />
  }
  const months = section.data
  const sum = months.reduce((total, m) => total + m.bookings, 0)
  const average = months.length ? sum / months.length : 0
  return (
    <>
      <BarChart
        data={months.map((m) => ({ label: formatMonth(m.start), title: m.month, values: { bookings: m.bookings } }))}
        series={[{ key: 'bookings', label: 'Bookings', tone: 'primary' }]}
        highlightLast
        caption="Bookings per month"
        summary={`Bookings per month, last ${months.length} months: ${formatNumber(sum)} in total`}
      />
      <p className={styles.chartFoot}>
        Average monthly load: <strong>{average.toFixed(1)} bookings</strong>
      </p>
    </>
  )
}

function BookingOutcomes({ section }) {
  if (!section.available) {
    return <Unavailable what="Booking outcomes" service={SERVICE.bookings} reason={section.reason} />
  }
  const d = section.data
  const cancelled = d.cancelledByCustomer + d.cancelledByProvider
  const total = d.completed + cancelled + d.disputed + d.otherClosed
  const success = percent(d.completed, total)
  const segments = [
    { key: 'completed', label: 'Completed', value: d.completed, tone: 'primary' },
    { key: 'cancelled', label: 'Cancelled', value: cancelled, tone: 'warning' },
    { key: 'disputed', label: 'Disputed', value: d.disputed, tone: 'danger' },
  ]
  return (
    <div className={styles.outcomes}>
      <DonutChart
        segments={segments}
        centerValue={success === null ? '—' : `${success}%`}
        centerLabel="success"
        summary={`Booking outcomes: ${segments.map((s) => `${s.label} ${s.value}`).join(', ')}`}
      />
      <ul className={styles.outcomeList}>
        {segments.map((s) => (
          <li key={s.key}>
            <span className={`${styles.legendDot} ${styles[`seg_${s.tone}`]}`} aria-hidden="true" />
            <span className={styles.outcomeLabel}>{s.label}</span>
            <strong>{formatNumber(s.value)}</strong>
            <span className={styles.subtle}>{percent(s.value, total) === null ? '—' : `${percent(s.value, total)}%`}</span>
          </li>
        ))}
        <li className={styles.outcomeTotal}>{plural(total, 'order')} total</li>
      </ul>
    </div>
  )
}

function HiddenListings({ section, onReview }) {
  if (!section.available) {
    return <Unavailable what="Hidden listings" service={SERVICE.listings} reason={section.reason} />
  }
  const items = section.data.recentFlagged
  if (items.length === 0) {
    return <p className={styles.emptyLine}>No flagged listings.</p>
  }
  return (
    <ul className={styles.rowList}>
      {items.map((l) => (
        <li key={l.id}>
          <div>
            <p className={styles.rowTitle}>{l.title}</p>
            <p className={styles.subtle}>
              Service ID {shortId(l.id)}
              {l.moderationFlags?.length > 0 && ` · Flagged: ${l.moderationFlags.join(', ').replaceAll('_', ' ').toLowerCase()}`}
            </p>
          </div>
          <div className={styles.rowEnd}>
            <ListingStatusChip status={l.status} />
            <Button variant="outline" size="small" onClick={() => onReview(l)} aria-label={`Review ${l.title}`}>
              Review
            </Button>
          </div>
        </li>
      ))}
    </ul>
  )
}

function QueuedBookings({ section }) {
  if (!section.available) {
    return <Unavailable what="Queued bookings" service={SERVICE.bookings} reason={section.reason} />
  }
  if (section.data.length === 0) return <p className={styles.emptyLine}>No upcoming bookings.</p>
  return (
    <ul className={styles.rowList}>
      {section.data.map((b) => (
        <li key={b.bookingId}>
          <div>
            <p className={styles.rowTitle}>
              {shortId(b.bookingId)}
              {b.serviceTitle && ` · ${b.serviceTitle}`}
            </p>
            <p className={styles.subtle}>
              {[b.counterpartyName, formatDateTime(b.scheduledStart)].filter(Boolean).join(' · ')}
            </p>
          </div>
          <div className={styles.rowEnd}>
            <BookingStatusChip status={b.status} />
            {b.amount !== null && b.amount !== undefined && <strong className={styles.amount}>{formatLkr(b.amount)}</strong>}
          </div>
        </li>
      ))}
    </ul>
  )
}

function RecentDisputes({ section }) {
  if (!section.available) {
    return <Unavailable what="Recent disputes" service={SERVICE.disputes} reason={section.reason} />
  }
  const rows = section.data.recent
  if (rows.length === 0) return <p className={styles.emptyLine}>No disputes on this account.</p>
  return (
    <div className={styles.tableWrap}>
      <table className={styles.simpleTable}>
        <thead>
          <tr>
            <th scope="col">Case ID</th>
            <th scope="col">Booking</th>
            <th scope="col">Reason</th>
            <th scope="col">Side</th>
            <th scope="col">Status</th>
            <th scope="col">Raised</th>
            <th scope="col">
              <span className="fb-sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((d) => (
            <tr key={d.id}>
              <td className={styles.mono}>{shortId(d.id)}</td>
              <td className={styles.mono}>{shortId(d.bookingId)}</td>
              <td>{categoryLabel(d.category)}</td>
              <td>{d.side === 'PROVIDER' ? 'Provider' : 'Customer'}</td>
              <td>
                <DisputeStatusChip status={d.status} />
              </td>
              <td>{formatDate(d.createdAt)}</td>
              <td>
                <span title="Admin dispute case page coming soon">
                  <button type="button" className={styles.linkButton} disabled aria-describedby={`dsp-soon-${d.id}`}>
                    View
                  </button>
                  <span id={`dsp-soon-${d.id}`} className="fb-sr-only">
                    Admin dispute case page coming soon
                  </span>
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function AdminActions({ section }) {
  if (!section.available) {
    return <Unavailable what="Admin actions" service={SERVICE.audit} reason={section.reason} />
  }
  if (section.data.length === 0) return <p className={styles.emptyLine}>No admin actions on this account yet.</p>
  return (
    <div className={styles.tableWrap}>
      <table className={styles.simpleTable}>
        <thead>
          <tr>
            <th scope="col">Date</th>
            <th scope="col">Admin</th>
            <th scope="col">Action</th>
            <th scope="col">Note</th>
          </tr>
        </thead>
        <tbody>
          {section.data.map((a) => {
            const name = a.actorName || (a.actorId ? 'Admin' : 'System')
            const reason = reasonLabel(a.reasonCode)
            return (
              <tr key={a.auditLogId}>
                <td>{formatDate(a.createdAt)}</td>
                <td>
                  <span className={styles.adminCell}>
                    <Avatar name={name} size="sm" />
                    {name}
                  </span>
                </td>
                <td>
                  <AdminActionChip action={a.action} />
                  {a.outcome && a.outcome !== 'SUCCESS' && (
                    <span className={styles.subtle}> ({a.outcome.toLowerCase()})</span>
                  )}
                </td>
                <td className={styles.noteCell}>
                  {reason && <strong>{reason}</strong>}
                  {reason && a.note && ' · '}
                  {a.note}
                  {!reason && !a.note && <span className={styles.subtle}>—</span>}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function DetailsSkeleton() {
  return (
    <div className={styles.detailsSkeleton} aria-busy="true" aria-label="Loading account">
      <div className={styles.profileCard}>
        <Skeleton variant="circle" width="64px" height="64px" />
        <PanelSkeleton rows={3} />
      </div>
      <div className={styles.metricGrid}>
        {Array.from({ length: 5 }, (_, i) => (
          <div key={i} className={styles.metricCard}>
            <PanelSkeleton rows={2} />
          </div>
        ))}
      </div>
      <div className={styles.twoCol}>
        <div className={styles.panel}>
          <PanelSkeleton rows={5} />
        </div>
        <div className={styles.panel}>
          <PanelSkeleton rows={5} />
        </div>
      </div>
    </div>
  )
}

/** Screen 2: account details of one customer or provider. */
export function UserDetailsPage() {
  const { id } = useParams()
  const location = useLocation()
  const listLink = userPaths.list(location.state?.listSearch?.replace(/^\?/, '') ?? '')
  const [reloadKey, setReloadKey] = useState(0)
  const [result, setResult] = useState({ key: null, data: null, error: null })
  const [dialog, setDialog] = useState(null)
  const [reviewing, setReviewing] = useState(null)
  const requestKey = `${id}|${reloadKey}`
  const loading = result.key !== requestKey

  useEffect(() => {
    const controller = new AbortController()
    getUser(id, controller.signal)
      .then((data) => setResult({ key: requestKey, data, error: null }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setResult({ key: requestKey, data: null, error: parseApiError(error) })
      })
    return () => controller.abort()
  }, [id, requestKey])

  const reload = () => setReloadKey((k) => k + 1)
  const breadcrumbs = ['Operations', { label: 'Users', to: listLink }, 'Account details']
  const user = result.data

  if (result.error && !loading) {
    const notFound = result.error.status === 404
    return (
      <AdminPage title="Account details" breadcrumbs={breadcrumbs} plain>
        <PageError
          statusCode={result.error.status}
          title={notFound ? 'User not found' : 'The account could not be loaded'}
          message={notFound ? 'This account does not exist or is not a customer or provider account.' : result.error.message}
          onRetry={notFound ? undefined : reload}
        />
        <Link to={listLink} className={styles.backLink}>
          <UsersIcon name="arrowLeft" size={15} /> Back to users
        </Link>
      </AdminPage>
    )
  }

  const name = user?.profile.displayName || 'this account'
  const actions = user?.actions ?? {}
  const headerAction = user && (
    <>
      {actions.canSuspend && (
        <Button variant="outline" className={styles.dangerOutline} onClick={() => setDialog('suspend')} icon={<UsersIcon name="ban" size={15} />}>
          Suspend account
        </Button>
      )}
      {actions.canReactivate && (
        <Button onClick={() => setDialog('reactivate')} icon={<UsersIcon name="refresh" size={15} />}>
          Reactivate account
        </Button>
      )}
    </>
  )

  return (
    <AdminPage
      title="Account details"
      subtitle={user ? `Detailed account overview, metrics, listings, and dispute history for ${name}` : undefined}
      breadcrumbs={breadcrumbs}
      actions={headerAction}
      plain
    >
      {loading || !user ? (
        <DetailsSkeleton />
      ) : (
        <div className={styles.details}>
          <ProfileCard user={user} />
          <Metrics user={user} />

          <div className={styles.twoCol}>
            <Panel title="Bookings per month" subtitle="Bookings over the last 6 calendar months" icon="barChart">
              <BookingsPerMonth section={user.bookingsPerMonth} />
            </Panel>
            <Panel title="Booking outcomes" subtitle="Completed, cancelled and disputed orders" icon="checkCircle">
              <BookingOutcomes section={user.bookingOutcomes} />
            </Panel>
          </div>

          <div className={styles.twoCol}>
            {shown(user.hiddenListings) && (
              <Panel
                title="Hidden listings"
                icon="eyeOff"
                count={user.hiddenListings.available ? user.hiddenListings.data.flagged : undefined}
              >
                <HiddenListings section={user.hiddenListings} onReview={setReviewing} />
              </Panel>
            )}
            <Panel
              title="Queued bookings"
              icon="clock"
              count={user.queuedBookings.available ? user.queuedBookings.data.length : undefined}
            >
              <QueuedBookings section={user.queuedBookings} />
            </Panel>
          </div>

          <Panel title="Recent disputes" icon="alertCircle">
            <RecentDisputes section={user.disputes} />
          </Panel>

          <Panel
            title="Admin actions on this account"
            icon="shield"
            action={<span className={styles.immutable}>Immutable administrative audit log</span>}
          >
            <AdminActions section={user.adminActions} />
          </Panel>

          <Link to={listLink} className={styles.backLink}>
            <UsersIcon name="arrowLeft" size={15} /> Back to users
          </Link>
        </div>
      )}

      {user && (
        <>
          <SuspendDialog
            open={dialog === 'suspend'}
            user={user.profile}
            onClose={() => setDialog(null)}
            onDone={() => {
              setDialog(null)
              reload()
            }}
          />
          <ReactivateDialog
            open={dialog === 'reactivate'}
            user={user.profile}
            onClose={() => setDialog(null)}
            onDone={() => {
              setDialog(null)
              reload()
            }}
          />
          <ReviewListingDialog
            open={Boolean(reviewing)}
            userId={user.profile.id}
            providerName={user.profile.displayName}
            listing={reviewing ?? { id: '' }}
            canReview={Boolean(actions.canReviewListings)}
            onClose={() => setReviewing(null)}
            onDone={() => {
              setReviewing(null)
              reload()
            }}
          />
        </>
      )}
    </AdminPage>
  )
}
