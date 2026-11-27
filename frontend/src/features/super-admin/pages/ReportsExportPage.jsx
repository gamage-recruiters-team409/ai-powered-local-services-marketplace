import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { PageError } from '../../../shared/components/feedback'
import { Button, DataTable } from '../../../shared/components/forms'
import { AdminPage, Icon } from '../../../shared/layouts/admin'
import { getReport, exportReportCsv, isRequestCancelled } from '../services/superAdminService'
import { downloadBlob } from '../../support-operations/utils/download'
import styles from '../superAdmin.module.css'

const REPORT_OPTIONS = [
  { value: 'disputes', label: 'Disputes' },
  { value: 'bookings-summary', label: 'Bookings summary' },
  { value: 'transactions', label: 'Transactions' },
  { value: 'payouts', label: 'Payouts' },
  { value: 'categories', label: 'Categories' },
  { value: 'provider-performance', label: 'Provider performance' },
]

const numberFmt = new Intl.NumberFormat('en-US')
function fmt(v) {
  if (v === null || v === undefined) return '—'
  return numberFmt.format(Number(v))
}

function todayISO() {
  return new Date().toISOString().slice(0, 10)
}
function thirtyDaysAgoISO() {
  const d = new Date()
  d.setDate(d.getDate() - 30)
  return d.toISOString().slice(0, 10)
}

const DISPUTE_COLUMNS = [
  { key: 'date', header: 'Date' },
  { key: 'opened', header: 'Opened', render: (v) => fmt(v) },
  { key: 'resolved', header: 'Resolved', render: (v) => fmt(v) },
  { key: 'underReview', header: 'Under Review', render: (v) => fmt(v) },
  { key: 'fullRefund', header: 'Full Refund', render: (v) => fmt(v) },
  { key: 'partialRefund', header: 'Partial Refund', render: (v) => fmt(v) },
  { key: 'noRefund', header: 'No Refund', render: (v) => fmt(v) },
  { key: 'avgResolutionHours', header: 'Avg Resolution', render: (v) => (v != null ? `${Number(v).toFixed(1)}h` : '—') },
]

function UnavailablePlaceholder({ reason, icon = 'reports' }) {
  return (
    <div className={styles.placeholder}>
      <Icon name={icon} size={22} />
      <p>{reason || 'This report will appear when the data source is connected.'}</p>
    </div>
  )
}

export default function ReportsExportPage() {
  const [params, setParams] = useSearchParams()

  const reportType = params.get('type') || 'disputes'
  const from = params.get('from') || thirtyDaysAgoISO()
  const to = params.get('to') || todayISO()
  const page = parseInt(params.get('page') || '0', 10)

  const [result, setResult] = useState({ loading: true, data: null, error: null })
  const [exporting, setExporting] = useState(false)

  const updateParams = useCallback(
    (updates) => {
      setParams((prev) => {
        const next = new URLSearchParams(prev)
        Object.entries(updates).forEach(([k, v]) => {
          if (v === null || v === undefined || v === '') next.delete(k)
          else next.set(k, String(v))
        })
        return next
      })
    },
    [setParams],
  )

  const handlePreview = useCallback(() => {
    updateParams({ type: reportType, from, to, page: 0 })
  }, [reportType, from, to, updateParams])

  // Fetch report on param change
  useEffect(() => {
    const controller = new AbortController()
    getReport(reportType, { from, to, page, size: 20 }, controller.signal)
      .then((data) => setResult({ loading: false, data, error: null }))
      .catch((err) => {
        if (!isRequestCancelled(err)) {
          const msg = err?.response?.data?.message || err?.message || 'Failed to load report.'
          const status = err?.response?.status ?? 0
          setResult({ loading: false, data: null, error: { status, message: msg } })
        }
      })
    return () => controller.abort()
  }, [reportType, from, to, page])

  const handleExport = useCallback(async () => {
    setExporting(true)
    try {
      const { blob, filename } = await exportReportCsv(reportType, { from, to })
      downloadBlob(blob, filename)
    } catch (err) {
      const msg = err?.response?.data?.message || err?.message || 'Export failed.'
      alert(msg)
    } finally {
      setExporting(false)
    }
  }, [reportType, from, to])

  const data = result.data
  const isAvailable = data?.available !== false

  return (
    <AdminPage
      title="Reports"
      subtitle="Preview platform reports"
      plain
    >
      {/* Controls */}
      <div className={styles.reportControls}>
        <div className={styles.controlGroup}>
          <label htmlFor="report-type">Report</label>
          <select
            id="report-type"
            value={reportType}
            onChange={(e) => updateParams({ type: e.target.value, page: 0 })}
          >
            {REPORT_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>

        <div className={styles.controlGroup}>
          <label htmlFor="report-from">From date</label>
          <input
            id="report-from"
            type="date"
            value={from}
            onChange={(e) => updateParams({ from: e.target.value })}
          />
        </div>

        <div className={styles.controlGroup}>
          <label htmlFor="report-to">To date</label>
          <input
            id="report-to"
            type="date"
            value={to}
            onChange={(e) => updateParams({ to: e.target.value })}
          />
        </div>

        <div className={styles.controlSpacer} />

        <div className={styles.controlActions}>
          <Button variant="primary" onClick={handlePreview} loading={result.loading} icon={<Icon name="reports" size={15} />}>
            Preview
          </Button>
          <Button
            variant="outline"
            onClick={handleExport}
            loading={exporting}
            disabled={!isAvailable || result.loading}
            icon={<Icon name="fileText" size={15} />}
          >
            Export CSV
          </Button>
        </div>
      </div>

      {/* Results */}
      {result.error ? (
        <section className={styles.card}>
          <PageError
            statusCode={result.error.status}
            title="Report could not be loaded"
            message={result.error.message}
            onRetry={handlePreview}
          />
        </section>
      ) : !isAvailable ? (
        <section className={styles.card}>
          <UnavailablePlaceholder reason={data?.reason || `Not available yet — connects once ${reportType} data source is available.`} />
        </section>
      ) : data ? (
        <section className={styles.card}>
          <div className={styles.cardHead}>
            <div>
              <h2 className={styles.cardTitle}>{REPORT_OPTIONS.find((o) => o.value === reportType)?.label || reportType} report</h2>
              <p className={styles.cardSubtitle}>{from} to {to}</p>
            </div>
          </div>

          <DataTable
            columns={DISPUTE_COLUMNS}
            data={data.rows ?? []}
            loading={result.loading}
            emptyMessage="No data for the selected range."
          />

          {/* Totals row */}
          {data.totals && (
            <div style={{ marginTop: 8, padding: '10px 14px', background: 'var(--adm-color-bg)', borderRadius: 8 }}>
              <strong>Totals: </strong>
              {Object.entries(data.totals).map(([k, v]) => (
                <span key={k} style={{ marginRight: 16 }}>{k}: <strong>{fmt(v)}</strong></span>
              ))}
            </div>
          )}

          {/* Pagination */}
          {data.totalPages > 1 && (
            <div className={styles.paginationRow}>
              <p className={styles.subtle}>
                Page {page + 1} of {data.totalPages} · {fmt(data.totalElements)} results
              </p>
              <div style={{ display: 'flex', gap: 6 }}>
                <Button variant="outline" size="small" disabled={page === 0} onClick={() => updateParams({ page: page - 1 })}>
                  Previous
                </Button>
                <Button variant="outline" size="small" disabled={page >= data.totalPages - 1} onClick={() => updateParams({ page: page + 1 })}>
                  Next
                </Button>
              </div>
            </div>
          )}
        </section>
      ) : null}
    </AdminPage>
  )
}
