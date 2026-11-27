import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'

import { PageError } from '../../../shared/components/feedback'
import { Button, DataTable } from '../../../shared/components/forms'
import { AdminPage, Icon } from '../../../shared/layouts/admin'
import { areaPath } from '../../disputes-admin/config/adminAreas'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import { OpsBarChart, HBars } from '../components/Charts'
import { CaseStatusChip, Chip, DaysOverdueChip } from '../components/Chips'
import { SummaryCards } from '../components/SummaryCards'
import { UnavailableSection } from '../components/Placeholder'
import { getDashboard, isRequestCancelled } from '../services/operationsService'
import { parseApiError } from '../utils/errors'
import { formatDate, shortId } from '../utils/format'
import styles from '../operations.module.css'

const DAY_SERIES = [{ key: 'count', label: 'Late cases', tone: 'primary' }]
const DIST = [
  { key: 'oneDay', label: '1 Day', tone: 'primary' },
  { key: 'twoDays', label: '2 Days', tone: 'primary' },
  { key: 'threeDays', label: '3 Days', tone: 'danger' },
  { key: 'fourPlusDays', label: '4+ Days', tone: 'danger' },
]

function sectionValue(section) {
  return section?.available ? section.data : undefined
}

export default function SupportDashboardPage() {
  const { area } = useAreaAccess() ?? {}
  const [reloadKey, setReloadKey] = useState(0)
  const [result, setResult] = useState({ key: null, data: null, error: null, at: null })
  const loading = result.key !== reloadKey

  useEffect(() => {
    const controller = new AbortController()
    getDashboard(controller.signal)
      .then((data) => setResult({ key: reloadKey, data, error: null, at: new Date() }))
      .catch((error) => {
        if (!isRequestCancelled(error)) setResult({ key: reloadKey, data: null, error: parseApiError(error), at: null })
      })
    return () => controller.abort()
  }, [reloadKey])

  const d = loading ? null : result.data
  const overduePath = areaPath(area ?? 'SUPPORT_ADMIN', 'overdue-bookings')

  const mostOverdue = sectionValue(d?.mostOverdue) ?? []
  const columns = useMemo(
    () => [
      {
        key: 'ref',
        header: 'Booking',
        render: (_, r) => (
          <div className={styles.refCell}>
            <span className={styles.refId}>#{r.ref}</span>
            <span className={styles.subtle}>{r.serviceTitle || '—'}</span>
          </div>
        ),
      },
      { key: 'customerId', header: 'Customer', render: (v) => <span title={v}>{shortId(v)}</span> },
      { key: 'providerId', header: 'Provider', render: (v) => <span title={v}>{shortId(v)}</span> },
      { key: 'serviceDate', header: 'Service date', render: (v) => formatDate(v) },
      { key: 'daysOverdue', header: 'Days overdue', render: (v) => <DaysOverdueChip days={v} /> },
      { key: 'caseStatus', header: 'Status', render: (v) => <CaseStatusChip status={v} /> },
    ],
    [],
  )

  const lastUpdated = result.at
    ? result.at.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })
    : null

  return (
    <AdminPage
      title={
        <>
          Dashboard{' '}
          <Chip variant="neutral" plain>
            View only
          </Chip>
        </>
      }
      subtitle="Overview of operational alerts, delay metrics and unresolved bookings."
      actions={
        <Button variant="outline" onClick={() => setReloadKey((k) => k + 1)} loading={loading} icon={<Icon name="switch" size={15} />}>
          Refresh
        </Button>
      }
      plain
    >
      {lastUpdated && (
        <p className={styles.subtle} style={{ marginTop: '-6px', marginBottom: 14 }}>
          Last updated: {lastUpdated}
        </p>
      )}

      {result.error ? (
        <section className={styles.card}>
          <PageError statusCode={result.error.status} title="Dashboard could not be loaded" message={result.error.message} onRetry={() => setReloadKey((k) => k + 1)} />
        </section>
      ) : (
        <>
          <SummaryCards
            loading={loading}
            cards={[
              { label: 'Overdue bookings', value: sectionValue(d?.overdueCount), available: d?.overdueCount?.available ?? true, reason: d?.overdueCount?.reason, icon: 'clock' },
              { label: 'Late cases', value: sectionValue(d?.lateCount), available: d?.lateCount?.available ?? true, reason: d?.lateCount?.reason, icon: 'calendar' },
              { label: 'Flagged users', value: sectionValue(d?.flaggedUsersCount), available: d?.flaggedUsersCount?.available ?? true, reason: d?.flaggedUsersCount?.reason, icon: 'alertCircle', tone: 'danger' },
            ]}
          />

          <div className={styles.twoCol}>
            <section className={styles.card} aria-labelledby="dash-late">
              <div className={styles.cardHead}>
                <div>
                  <h2 id="dash-late" className={styles.cardTitle}>
                    Late cases per day
                  </h2>
                  <p className={styles.cardSubtitle}>Daily incident trend for the last 14 days</p>
                </div>
              </div>
              {loading ? (
                <div className={styles.chartSkeleton} aria-hidden="true" />
              ) : d?.latePerDay?.available ? (
                <OpsBarChart
                  highlightLast
                  showValues
                  series={DAY_SERIES}
                  summary="Late cases per day for the last 14 days"
                  caption="Late cases per day"
                  data={(d.latePerDay.data ?? []).map((row, index, arr) => ({
                    label: index === arr.length - 1 ? 'Today' : `D${index + 1}`,
                    title: row.date,
                    values: { count: row.count },
                  }))}
                />
              ) : (
                <UnavailableSection reason={d?.latePerDay?.reason} icon="calendar" />
              )}
            </section>

            <section className={styles.card} aria-labelledby="dash-dist">
              <div className={styles.cardHead}>
                <div>
                  <h2 id="dash-dist" className={styles.cardTitle}>
                    Overdue bookings by days
                  </h2>
                  <p className={styles.cardSubtitle}>Distribution across active delay tiers</p>
                </div>
              </div>
              {loading ? (
                <div className={styles.chartSkeleton} aria-hidden="true" />
              ) : d?.overdueDistribution?.available ? (
                <HBars
                  summary="Overdue bookings by number of days overdue"
                  rows={DIST.map((t) => ({ label: t.label, value: d.overdueDistribution.data?.[t.key] ?? 0, tone: t.tone }))}
                />
              ) : (
                <UnavailableSection reason={d?.overdueDistribution?.reason} icon="clock" />
              )}
            </section>
          </div>

          <section className={styles.card} aria-labelledby="dash-recent">
            <div className={styles.cardHead}>
              <div>
                <h2 id="dash-recent" className={styles.cardTitle}>
                  Recent overdue bookings
                </h2>
                <p className={styles.cardSubtitle}>Unresolved bookings exceeding expected completion windows</p>
              </div>
              <Link to={overduePath} className="shared-btn shared-btn--outline shared-btn--small">
                View all
              </Link>
            </div>
            {d && !d.mostOverdue?.available ? (
              <UnavailableSection reason={d.mostOverdue?.reason} icon="clock" />
            ) : (
              <DataTable
                columns={columns}
                data={mostOverdue}
                loading={loading}
                emptyMessage="No overdue bookings right now."
              />
            )}
          </section>
        </>
      )}
    </AdminPage>
  )
}
