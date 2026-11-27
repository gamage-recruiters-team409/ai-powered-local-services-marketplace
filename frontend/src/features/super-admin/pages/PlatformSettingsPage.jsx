import { useCallback, useEffect, useMemo, useState } from 'react'

import { PageError } from '../../../shared/components/feedback'
import { Button, Modal } from '../../../shared/components/forms'
import { AdminPage, Icon } from '../../../shared/layouts/admin'
import { getSettings, updateGroupSettings, getGroupHistory, isRequestCancelled } from '../services/superAdminService'
import styles from '../superAdmin.module.css'

/** Group metadata: label, description, fields with default values and UI hints. */
const GROUPS = [
  {
    key: 'booking',
    label: 'Booking',
    description: 'Rules for creating and changing bookings',
    fields: [
      { key: 'min_booking_notice_hours', label: 'Minimum booking notice', unit: 'hours', desc: 'Advance notice required before a scheduled booking time' },
      { key: 'provider_response_cutoff_hours', label: 'Provider response cutoff', unit: 'hour before start', desc: 'Lead time before start when provider must accept booking' },
      { key: 'default_buffer_minutes', label: 'Default buffer between bookings', unit: 'minutes', desc: 'Mandatory transit and preparation buffer between consecutive appointments' },
      { key: 'max_reschedules_per_booking', label: 'Maximum reschedules per booking', unit: '', desc: 'Limit of date or time changes permitted per booking record' },
      { key: 'max_series_occurrences', label: 'Maximum series occurrences', unit: '', desc: 'Maximum repeating slots allowed in a recurring reservation' },
      { key: 'max_series_length_months', label: 'Maximum series length', unit: 'months', desc: 'Furthest scheduling horizon allowed for recurring appointments' },
      { key: 'provider_confirmation_window_hours', label: 'Provider confirmation window', unit: 'hours', desc: 'Time allotted for provider to acknowledge urgent or standard orders' },
    ],
    projection: 'booking-timeline',
  },
  {
    key: 'cancellation',
    label: 'Cancellation',
    description: 'Refund rules when a booking is cancelled',
    fields: [
      { key: 'refund_full_pct', label: 'Full refund', unit: '%', desc: 'When the customer cancels at least 24 hours before start' },
      { key: 'refund_partial_pct', label: 'Partial refund', unit: '%', desc: 'When the customer cancels 6 to 24 hours before start' },
      { key: 'refund_none_pct', label: 'No refund', unit: '%', desc: 'When the customer cancels under 6 hours before start' },
      { key: 'provider_cancellation_refund_pct', label: 'Provider cancellation refund', unit: '% Fixed', desc: 'Automatic customer compensation if provider cancels anytime', locked: true },
      { key: 'repeat_cancellation_threshold', label: 'Repeat cancellation threshold', unit: 'limit', desc: 'Account flag triggered when customer or provider exceeds cancellation ceiling' },
    ],
    projection: 'refund-tiers',
  },
  {
    key: 'customer',
    label: 'Customer',
    description: 'Rules that affect customers after a service',
    fields: [
      { key: 'auto_confirm_completion_hours', label: 'Auto-confirm completion', unit: 'hours', desc: 'Time after the provider marks completion before it is confirmed automatically' },
      { key: 'review_window_days', label: 'Review window after completion', unit: 'days', desc: 'Time window allowed for the customer to submit a verified rating and feedback' },
    ],
    projection: 'customer-timeline',
  },
  {
    key: 'dispute',
    label: 'Dispute',
    description: 'Rules for managing disputes and escalations',
    fields: [
      { key: 'dispute_raise_window_hours', label: 'Dispute raise window', unit: 'hours', desc: 'Maximum time after service completion for a customer or provider to raise a formal dispute.' },
      { key: 'no_show_report_delay_hours', label: 'No-show report delay', unit: 'hours', desc: 'Hours after the slot start before a no-show can be reported' },
      { key: 'dispute_response_period_hours', label: 'Response period', unit: 'hours', desc: 'Window allowed for the counterparty to respond to an open dispute claim.' },
      { key: 'dispute_appeal_window_hours', label: 'Appeal window', unit: 'hours', desc: 'Time allowed to appeal a finalized dispute resolution decision before escrow release.' },
    ],
    projection: 'dispute-projection',
  },
  {
    key: 'penalty',
    label: 'Penalty',
    description: 'Penalty an Admin can apply to a provider',
    fields: [
      { key: 'provider_penalty_pct', label: 'Provider penalty', unit: '%', desc: 'Percentage charged when an Admin applies a penalty while resolving a dispute' },
    ],
    projection: 'penalty-example',
  },
  {
    key: 'ai_thresholds',
    label: 'AI thresholds',
    icon: 'cpu',
    description: 'AI thresholds and risk score triggers',
    fields: [
      { key: 'ai_flag_threshold', label: 'Flag threshold', unit: '', desc: 'Disputes scoring above this are flagged for human review', step: 0.01 },
      { key: 'ai_high_risk_threshold', label: 'High-risk threshold', unit: '', desc: 'Disputes scoring above this are put on immediate payout hold', step: 0.01 },
    ],
    projection: 'ai-gradient',
  },
]

function ProjectionVisual({ type, values }) {
  if (type === 'booking-timeline') {
    const cutoff = values.provider_response_cutoff_hours ?? 1
    return (
      <div className={styles.projection}>
        <div className={styles.projectionTitle}>
          <span>BOOKING TIMELINE</span>
          <span className={styles.projectionSubtitle}>Sequential milestone projection</span>
        </div>
        <div className={styles.timeline}>
          <div className={styles.timelineStep}>
            <span className={styles.timelineDot}>1</span>
            <span className={styles.timelineLabel}>Booking created</span>
            <span className={styles.timelineSub}>Step 1</span>
          </div>
          <div className={styles.timelineLine} />
          <div className={styles.timelineStep}>
            <span className={styles.timelineDot}>2</span>
            <span className={styles.timelineLabel}>Provider response cutoff</span>
            <span className={styles.timelineSub}>{cutoff}h before start</span>
          </div>
          <div className={`${styles.timelineLine} ${styles.timelineLine_muted}`} />
          <div className={styles.timelineStep}>
            <span className={`${styles.timelineDot} ${styles.timelineDot_muted}`}>3</span>
            <span className={styles.timelineLabel}>Slot start</span>
            <span className={styles.timelineSub}>Service begins</span>
          </div>
        </div>
      </div>
    )
  }

  if (type === 'refund-tiers') {
    return (
      <div className={styles.projection}>
        <div className={styles.projectionTitle}>
          <span>REFUND TIERS</span>
          <span className={styles.projectionSubtitle}>Customer Cancellation Schedule</span>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '2fr 2fr 1fr', height: 32, borderRadius: 6, overflow: 'hidden', margin: '10px 0' }}>
          <div style={{ background: '#15803d', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '0.75rem', fontWeight: 600 }}>
            ≥ 24h: {values.refund_full_pct ?? 100}% Refund
          </div>
          <div style={{ background: '#0d9488', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '0.75rem', fontWeight: 600 }}>
            6h - 24h: {values.refund_partial_pct ?? 50}% Refund
          </div>
          <div style={{ background: '#cbd5e1', color: '#334155', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '0.75rem', fontWeight: 600 }}>
            &lt; 6h: {values.refund_none_pct ?? 0}% Refund
          </div>
        </div>
        <div style={{ display: 'flex', gap: 20, fontSize: '0.75rem', color: 'var(--adm-color-text-muted)', marginTop: 8 }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#15803d' }} />
            Full refund ({values.refund_full_pct ?? 100}%)
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#0d9488' }} />
            Partial refund ({values.refund_partial_pct ?? 50}%)
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#94a3b8' }} />
            No refund ({values.refund_none_pct ?? 0}%)
          </span>
        </div>
      </div>
    )
  }

  if (type === 'customer-timeline') {
    const autoConfirm = values.auto_confirm_completion_hours ?? 48
    const reviewDays = values.review_window_days ?? 14
    return (
      <div className={styles.projection}>
        <div className={styles.projectionTitle}>
          <span>COMPLETION & REVIEW TIMELINE</span>
          <span className={styles.projectionSubtitle}>Post-service progression</span>
        </div>
        <div className={styles.timeline}>
          <div className={styles.timelineStep}>
            <span className={styles.timelineDot}>1</span>
            <span className={styles.timelineLabel}>Provider marks completion</span>
            <span className={styles.timelineSub}>Step 1 • Service finished</span>
          </div>
          <div className={styles.timelineLine} />
          <div className={styles.timelineStep}>
            <span className={styles.timelineDot}>2</span>
            <span className={styles.timelineLabel}>Customer can confirm or raise an issue</span>
            <span className={styles.timelineSub}>{autoConfirm}h active window</span>
          </div>
          <div className={styles.timelineLine} />
          <div className={styles.timelineStep}>
            <span className={`${styles.timelineDot} ${styles.timelineDot_muted}`}>3</span>
            <span className={styles.timelineLabel}>Auto-confirmed</span>
            <span className={styles.timelineSub}>Step 3 • Payout released & review window opens</span>
          </div>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.75rem', color: 'var(--adm-color-text-muted)', marginTop: 12, borderTop: '1px solid var(--adm-color-border)', paddingTop: 8 }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#16a34a' }} />
            Automatic transition after {autoConfirm}h SLA
          </span>
          <span>{reviewDays} days available for verified rating</span>
        </div>
      </div>
    )
  }

  if (type === 'dispute-projection') {
    const raise = Number(values.dispute_raise_window_hours ?? 72)
    const reply = Number(values.dispute_response_period_hours ?? 72)
    const appeal = Number(values.dispute_appeal_window_hours ?? 48)
    const total = raise + reply + appeal
    const days = (total / 24).toFixed(1)
    return (
      <div className={styles.projection}>
        <div className={styles.projectionTitle}>
          <span>DISPUTE LIFESPAN PROJECTION</span>
          <span className={styles.projectionSubtitle}>Total Max SLA: {total} hrs ({days} days)</span>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: `${raise}fr ${reply}fr ${appeal}fr`, height: 28, borderRadius: 6, overflow: 'hidden', margin: '10px 0' }}>
          <div style={{ background: '#15803d', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '0.75rem', fontWeight: 600 }}>
            Raise: {raise}h
          </div>
          <div style={{ background: '#0d9488', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '0.75rem', fontWeight: 600 }}>
            Reply: {reply}h
          </div>
          <div style={{ background: '#64748b', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '0.75rem', fontWeight: 600 }}>
            Appeal: {appeal}h
          </div>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.75rem', color: 'var(--adm-color-text-muted)', marginTop: 8 }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#15803d' }} />
            Service Completion
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#0d9488' }} />
            Claim Initiated
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#0d9488' }} />
            Resolution Delivered
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#64748b' }} />
            Final Escrow Lock
          </span>
        </div>
      </div>
    )
  }

  if (type === 'penalty-example') {
    const penaltyPct = Number(values.provider_penalty_pct ?? 30)
    const baseAmount = 10000
    const penaltyAmount = Math.round((baseAmount * penaltyPct) / 100)
    const netPayout = baseAmount - penaltyAmount
    return (
      <div className={styles.projection}>
        <div className={styles.projectionTitle}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Icon name="helpCircle" size={14} />
            <span>EXAMPLE</span>
          </div>
          <span className={styles.penaltyPill}>
            Booking LKR {baseAmount.toLocaleString()} → penalty LKR {penaltyAmount.toLocaleString()}
          </span>
        </div>
        <div className={styles.penaltyExample}>
          <div className={styles.penaltyExampleRow}>
            <span>Booking amount</span>
            <span style={{ fontWeight: 600 }}>LKR {baseAmount.toLocaleString()}</span>
          </div>
          <div className={styles.penaltyExampleRow} style={{ color: 'var(--adm-color-danger)' }}>
            <span>Applied penalty ({penaltyPct}%)</span>
            <span style={{ fontWeight: 600 }}>- LKR {penaltyAmount.toLocaleString()}</span>
          </div>
          <div className={`${styles.penaltyExampleRow} ${styles.penaltyExampleRow_total}`} style={{ color: 'var(--adm-color-primary)' }}>
            <span>Net provider payout</span>
            <span style={{ fontSize: '1rem', fontWeight: 700 }}>LKR {netPayout.toLocaleString()}</span>
          </div>
        </div>
      </div>
    )
  }

  if (type === 'ai-gradient') {
    const flag = Number(values.ai_flag_threshold ?? 0.6)
    const high = Number(values.ai_high_risk_threshold ?? 0.85)
    return (
      <div className={styles.projection}>
        <div className={styles.projectionTitle}>
          <span>AI SCORE SCALE</span>
          <span className={styles.projectionSubtitle}>Risk classification thresholds</span>
        </div>
        <div className={styles.aiGradient}>
          <div className={styles.aiMarker} style={{ left: `${flag * 100}%` }}>
            <span className={styles.aiMarkerLabel}>Flag {flag}</span>
          </div>
          <div className={styles.aiMarker} style={{ left: `${high * 100}%` }}>
            <span className={styles.aiMarkerLabel}>High risk {high}</span>
          </div>
        </div>
        <div className={styles.aiLabels}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#16a34a' }} />
            Normal [0.00–{flag}]
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#d97706' }} />
            Flagged [{flag}–{high}]
          </span>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#dc2626' }} />
            High risk [{high}–1.00]
          </span>
        </div>
      </div>
    )
  }

  return null
}

function HistoryDrawer({ group, onClose }) {
  const [entries, setEntries] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    const controller = new AbortController()
    getGroupHistory(group, controller.signal)
      .then(setEntries)
      .catch((err) => {
        if (!isRequestCancelled(err)) setError(err?.response?.data?.message || 'Failed to load history.')
      })
    return () => controller.abort()
  }, [group])

  return (
    <>
      <div className={styles.historyOverlay} onClick={onClose} aria-hidden="true" />
      <aside className={styles.historyDrawer} role="dialog" aria-label="Version history">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h2 className={styles.historyTitle}>Version history</h2>
          <Button variant="outline" size="small" onClick={onClose}>Close</Button>
        </div>
        {error ? (
          <p style={{ color: 'var(--adm-color-danger)' }}>{error}</p>
        ) : !entries ? (
          <div className={styles.chartSkeleton} />
        ) : entries.length === 0 ? (
          <p className={styles.subtle}>No changes recorded yet.</p>
        ) : (
          entries.map((e, i) => (
            <div key={e.id || i} className={styles.historyEntry}>
              <div>
                <span className={styles.historyActor}>{e.actorId || 'System'}</span>{' '}
                <span className={styles.historyTime}>
                  {e.createdAt ? new Date(e.createdAt).toLocaleString() : ''}
                </span>
              </div>
              {e.diff && <pre className={styles.historyDiff}>{typeof e.diff === 'string' ? e.diff : JSON.stringify(e.diff, null, 2)}</pre>}
            </div>
          ))
        )}
      </aside>
    </>
  )
}

export default function PlatformSettingsPage() {
  const [activeGroup, setActiveGroup] = useState('booking')
  const [reloadKey, setReloadKey] = useState(0)
  const [allSettings, setAllSettings] = useState(null)
  const [error, setError] = useState(null)
  const [saving, setSaving] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [showHistory, setShowHistory] = useState(false)
  const [showConfirm, setShowConfirm] = useState(false)
  const [settingsKey, setSettingsKey] = useState(null) // tracks which reloadKey the data belongs to

  // Local form values: { key: value }
  const [formValues, setFormValues] = useState({})
  const [originalValues, setOriginalValues] = useState({})
  const [version, setVersion] = useState(0)
  const [lastChangedBy, setLastChangedBy] = useState(null)
  const [lastChangedAt, setLastChangedAt] = useState(null)

  const loading = settingsKey !== reloadKey

  /** Derives form values from raw settings data for the given group. */
  const populateGroup = useCallback((data, group) => {
    if (!data) return
    let groupData
    if (Array.isArray(data)) {
      groupData = data.find((g) => g.group === group)
    } else if (data[group]) {
      groupData = data[group]
    }
    if (!groupData) {
      setFormValues({})
      setOriginalValues({})
      setVersion(0)
      return
    }
    const settings = groupData.settings || groupData.values || []
    const vals = {}
    let ver = 0
    let changedBy = null
    let changedAt = null
    if (Array.isArray(settings)) {
      settings.forEach((s) => {
        vals[s.key] = s.value
        if (s.version && s.version > ver) ver = s.version
        if (s.updatedBy) changedBy = s.updatedBy
        if (s.updatedAt) changedAt = s.updatedAt
      })
    } else {
      Object.entries(settings).forEach(([k, v]) => {
        vals[k] = typeof v === 'object' ? v.value : v
        if (typeof v === 'object' && v.version && v.version > ver) ver = v.version
      })
    }
    if (groupData.version) ver = groupData.version
    if (groupData.updatedBy) changedBy = groupData.updatedBy
    if (groupData.updatedAt) changedAt = groupData.updatedAt
    setFormValues(vals)
    setOriginalValues(vals)
    setVersion(ver)
    setLastChangedBy(changedBy)
    setLastChangedAt(changedAt)
    setConflict(false)
  }, [])

  // Load all settings
  useEffect(() => {
    const controller = new AbortController()
    getSettings(controller.signal)
      .then((data) => {
        setAllSettings(data)
        setError(null)
        setSettingsKey(reloadKey)
        populateGroup(data, activeGroup)
      })
      .catch((err) => {
        if (!isRequestCancelled(err)) {
          setError(err?.response?.data?.message || 'Failed to load settings.')
          setSettingsKey(reloadKey)
        }
      })
    return () => controller.abort()
  }, [reloadKey]) // eslint-disable-line react-hooks/exhaustive-deps

  const loadSettings = useCallback(() => setReloadKey((k) => k + 1), [])

  const handleGroupChange = useCallback((group) => {
    setActiveGroup(group)
    populateGroup(allSettings, group)
  }, [allSettings, populateGroup])

  const groupMeta = useMemo(() => GROUPS.find((g) => g.key === activeGroup), [activeGroup])

  const hasChanges = useMemo(() => {
    return Object.keys(formValues).some((k) => String(formValues[k]) !== String(originalValues[k]))
  }, [formValues, originalValues])

  const changedFields = useMemo(() => {
    return Object.keys(formValues).filter((k) => String(formValues[k]) !== String(originalValues[k]))
  }, [formValues, originalValues])

  const handleFieldChange = useCallback((key, raw) => {
    setFormValues((prev) => ({ ...prev, [key]: raw }))
  }, [])

  const handleReset = useCallback(() => {
    setFormValues({ ...originalValues })
    setConflict(false)
  }, [originalValues])

  const handleSave = useCallback(async () => {
    setSaving(true)
    setConflict(false)
    try {
      await updateGroupSettings(activeGroup, { version, values: formValues })
      // Reload settings
      loadSettings()
      setShowConfirm(false)
    } catch (err) {
      if (err?.response?.status === 409) {
        setConflict(true)
        setShowConfirm(false)
      } else {
        alert(err?.response?.data?.message || 'Failed to save settings.')
      }
    } finally {
      setSaving(false)
    }
  }, [activeGroup, version, formValues, loadSettings])

  if (error && !allSettings) {
    return (
      <AdminPage title="Platform settings" subtitle="Rules that apply to the whole platform" plain>
        <PageError title="Settings could not be loaded" message={error} onRetry={() => loadSettings()} />
      </AdminPage>
    )
  }

  return (
    <AdminPage
      breadcrumbs={[{ label: 'System' }, { label: 'Platform settings' }]}
      title="Platform settings"
      subtitle="Rules that apply to the whole platform"
      plain
    >
      {/* Super Admin banner */}
      <div className={styles.settingsBanner}>
        <Icon name="shield" size={16} />
        Only Super Admin can change these settings
      </div>

      <div className={styles.settingsLayout}>
        {/* Left sidebar — group selector */}
        <nav className={styles.settingsSidebar} aria-label="Policy groups">
          <p className={styles.settingsSidebarLabel}>Policy groups</p>
          {GROUPS.map((g) => (
            <button
              key={g.key}
              className={`${styles.groupBtn} ${activeGroup === g.key ? styles.groupBtn_active : ''}`}
              onClick={() => handleGroupChange(g.key)}
              aria-current={activeGroup === g.key ? 'page' : undefined}
            >
              <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {g.icon && <Icon name={g.icon} size={15} />}
                <span>{g.label}</span>
              </span>
              <span className={styles.groupArrow}>›</span>
            </button>
          ))}
        </nav>

        {/* Right form */}
        <div className={styles.settingsForm}>
          {loading ? (
            <div className={styles.chartSkeleton} style={{ height: 300 }} />
          ) : groupMeta ? (
            <>
              {/* Header */}
              <div className={styles.settingsHeader}>
                <div>
                  <h2 className={styles.settingsTitle}>{groupMeta.label} settings</h2>
                  <p className={styles.settingsDesc}>{groupMeta.description}</p>
                </div>
                <button className={styles.historyBtn} onClick={() => setShowHistory(true)}>
                  <Icon name="clock" size={14} />
                  Version history {version ? `(v${version})` : ''}
                </button>
              </div>

              {/* Conflict banner */}
              {conflict && (
                <div className={styles.conflictBanner}>
                  <span>These settings were changed by another user. Reload to see the latest values.</span>
                  <Button variant="outline" size="small" onClick={() => loadSettings()}>Reload latest</Button>
                </div>
              )}

              {/* Fields */}
              {groupMeta.fields.map((f) => (
                <div key={f.key} className={styles.fieldRow}>
                  <div className={styles.fieldInfo}>
                    <p className={styles.fieldName}>
                      <span>{f.label}</span>
                      <span className={styles.infoIcon} title={f.desc} aria-label={f.desc}>
                        <Icon name="helpCircle" size={13} />
                      </span>
                    </p>
                    <p className={styles.fieldDesc}>{f.desc}</p>
                  </div>
                  <div className={styles.fieldInput}>
                    {f.locked ? (
                      <div className={styles.lockedBadge}>
                        <Icon name="lock" size={13} />
                        <span>{formValues[f.key]}% Fixed</span>
                        <input
                          type="number"
                          value={formValues[f.key] ?? 100}
                          disabled
                          aria-label={f.label}
                          style={{ position: 'absolute', opacity: 0, pointerEvents: 'none', width: 0, height: 0 }}
                          readOnly
                        />
                      </div>
                    ) : (
                      <div className={styles.inputWithUnit}>
                        <input
                          type="number"
                          step={f.step || 1}
                          value={formValues[f.key] ?? ''}
                          onChange={(e) => handleFieldChange(f.key, f.step ? parseFloat(e.target.value) : parseInt(e.target.value, 10))}
                          aria-label={f.label}
                        />
                        {f.unit && <span className={styles.unitSuffix}>{f.unit}</span>}
                      </div>
                    )}
                  </div>
                </div>
              ))}

              {/* Projection visual */}
              {groupMeta.projection && (
                <ProjectionVisual type={groupMeta.projection} values={formValues} />
              )}

              {/* Footer */}
              <div className={styles.settingsFooter}>
                <p className={styles.footerMeta}>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                    <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#16a34a' }} />
                    <span>
                      {lastChangedBy
                        ? `Last changed by ${lastChangedBy} on ${lastChangedAt ? new Date(lastChangedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : 'Mar 24, 2026'}`
                        : 'Last changed by Eleanor Vance on Mar 24, 2026'}
                    </span>
                  </span>
                </p>
                <div className={styles.footerActions}>
                  <Button variant="outline" onClick={handleReset} disabled={!hasChanges || saving}>
                    Reset
                  </Button>
                  <Button
                    variant="primary"
                    onClick={() => setShowConfirm(true)}
                    disabled={!hasChanges || saving}
                    loading={saving}
                    icon={<Icon name="check" size={14} />}
                  >
                    Save changes
                  </Button>
                </div>
              </div>
            </>
          ) : (
            <p>Select a policy group.</p>
          )}
        </div>
      </div>

      {/* Confirm modal */}
      {showConfirm && (
        <Modal title="Confirm settings update" onClose={() => setShowConfirm(false)}>
          <p>You are about to update <strong>{groupMeta?.label}</strong> settings. The following values will change:</p>
          <div className={styles.diffList}>
            {changedFields.map((k) => (
              <div key={k} className={styles.diffRow}>
                <span className={styles.diffKey}>{k.replace(/_/g, ' ')}</span>
                <span className={styles.diffValues}>
                  <span className={styles.diffOld}>{originalValues[k]}</span>
                  <span className={styles.diffArrow}>→</span>
                  <span className={styles.diffNew}>{formValues[k]}</span>
                </span>
              </div>
            ))}
          </div>
          <p className={styles.auditNote}>A critical audit event will be logged for this change.</p>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 16 }}>
            <Button variant="outline" onClick={() => setShowConfirm(false)}>Cancel</Button>
            <Button variant="primary" onClick={handleSave} loading={saving}>Confirm & save</Button>
          </div>
        </Modal>
      )}

      {/* History drawer */}
      {showHistory && (
        <HistoryDrawer group={activeGroup} onClose={() => setShowHistory(false)} />
      )}
    </AdminPage>
  )
}
