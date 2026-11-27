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
    description: 'Refund and penalty rules for cancellations',
    fields: [
      { key: 'refund_full_pct', label: 'Full refund', unit: '%', desc: 'Refund percentage for cancellations within the full refund window' },
      { key: 'refund_partial_pct', label: 'Partial refund', unit: '%', desc: 'Refund percentage for cancellations within the partial refund window' },
      { key: 'refund_none_window_hours', label: 'No-refund window', unit: 'hours', desc: 'Cancellations within this many hours before start receive no refund' },
      { key: 'refund_partial_window_hours', label: 'Partial refund window', unit: 'hours', desc: 'Cancellations within this many hours before start receive partial refund' },
      { key: 'repeat_cancellation_threshold', label: 'Repeat cancellation threshold', unit: 'cancellations', desc: 'Number of cancellations before the account is flagged' },
      { key: 'provider_cancellation_refund_pct', label: 'Provider cancellation refund', unit: '%', desc: 'Refund percentage when provider cancels (locked at 100%)', locked: true },
    ],
    projection: 'refund-tiers',
  },
  {
    key: 'customer',
    label: 'Customer',
    description: 'Customer account behaviour limits',
    fields: [
      { key: 'max_active_bookings', label: 'Maximum active bookings', unit: '', desc: 'Most bookings a customer can have open simultaneously' },
    ],
    projection: null,
  },
  {
    key: 'dispute',
    label: 'Dispute',
    description: 'Dispute timeline and escalation rules',
    fields: [
      { key: 'dispute_raise_window_hours', label: 'Dispute raise window', unit: 'hours', desc: 'Hours after booking completion within which a dispute can be raised' },
      { key: 'dispute_response_cutoff_hours', label: 'Response cutoff', unit: 'hours', desc: 'Hours given to the other party to respond to a dispute' },
      { key: 'dispute_appeal_window_hours', label: 'Appeal window', unit: 'hours', desc: 'Hours after resolution within which a decision can be appealed' },
    ],
    projection: 'dispute-timeline',
  },
  {
    key: 'penalty',
    label: 'Penalty',
    description: 'Provider penalty configuration',
    fields: [
      { key: 'provider_penalty_pct', label: 'Provider penalty', unit: '%', desc: 'Percentage deducted from provider earnings when a penalty is applied' },
    ],
    projection: null,
  },
  {
    key: 'ai_thresholds',
    label: 'AI thresholds',
    description: 'AI scoring thresholds for dispute triage',
    fields: [
      { key: 'ai_flag_threshold', label: 'AI flag threshold', unit: '', desc: 'Score above which a dispute is flagged for review', step: 0.01 },
      { key: 'ai_high_risk_threshold', label: 'AI high risk threshold', unit: '', desc: 'Score above which a dispute is marked high risk', step: 0.01 },
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
    const full = values.refund_full_pct ?? 100
    const partial = values.refund_partial_pct ?? 50
    return (
      <div className={styles.projection}>
        <div className={styles.projectionTitle}>
          <span>REFUND TIERS</span>
          <span className={styles.projectionSubtitle}>Cancellation refund levels</span>
        </div>
        <div className={styles.tierBar}>
          <div className={styles.tierFull} style={{ width: `${full}%` }} />
          <div className={styles.tierPartial} style={{ width: `${partial}%` }} />
          <div className={styles.tierNone} style={{ flex: 1 }} />
        </div>
        <div className={styles.tierLabels}>
          <span>Full {full}%</span>
          <span>Partial {partial}%</span>
          <span>None 0%</span>
        </div>
      </div>
    )
  }

  if (type === 'dispute-timeline') {
    const raise = values.dispute_raise_window_hours ?? 72
    const response = values.dispute_response_cutoff_hours ?? 48
    const appeal = values.dispute_appeal_window_hours ?? 48
    return (
      <div className={styles.projection}>
        <div className={styles.projectionTitle}>
          <span>DISPUTE TIMELINE</span>
          <span className={styles.projectionSubtitle}>Time windows for dispute lifecycle</span>
        </div>
        <div className={styles.timeline}>
          <div className={styles.timelineStep}>
            <span className={styles.timelineDot}>1</span>
            <span className={styles.timelineLabel}>Raise window</span>
            <span className={styles.timelineSub}>{raise}h</span>
          </div>
          <div className={styles.timelineLine} />
          <div className={styles.timelineStep}>
            <span className={styles.timelineDot}>2</span>
            <span className={styles.timelineLabel}>Response cutoff</span>
            <span className={styles.timelineSub}>{response}h</span>
          </div>
          <div className={styles.timelineLine} />
          <div className={styles.timelineStep}>
            <span className={styles.timelineDot}>3</span>
            <span className={styles.timelineLabel}>Appeal window</span>
            <span className={styles.timelineSub}>{appeal}h</span>
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
          <span>Normal [0–{flag}]</span>
          <span>Flagged [{flag}–{high}]</span>
          <span>High risk [{high}–1.0]</span>
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
              {g.label}
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
                    <p className={styles.fieldName}>{f.label}</p>
                    <p className={styles.fieldDesc}>{f.desc}</p>
                  </div>
                  <div className={styles.fieldInput}>
                    <input
                      type="number"
                      step={f.step || 1}
                      value={formValues[f.key] ?? ''}
                      onChange={(e) => handleFieldChange(f.key, f.step ? parseFloat(e.target.value) : parseInt(e.target.value, 10))}
                      disabled={f.locked}
                      aria-label={f.label}
                    />
                    {f.unit && <span className={styles.fieldUnit}>{f.unit}</span>}
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
                  {lastChangedBy
                    ? `Last changed by ${lastChangedBy} on ${lastChangedAt ? new Date(lastChangedAt).toLocaleDateString() : '—'}`
                    : 'No changes recorded yet.'}
                </p>
                <div className={styles.footerActions}>
                  <Button variant="outline" onClick={handleReset} disabled={!hasChanges || saving}>
                    Reset
                  </Button>
                  <Button variant="primary" onClick={() => setShowConfirm(true)} disabled={!hasChanges || saving} loading={saving} icon={<Icon name="shield" size={14} />}>
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
