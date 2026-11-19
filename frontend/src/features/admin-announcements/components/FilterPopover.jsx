import { useEffect, useId, useRef, useState } from 'react'

import { Button, Select } from '../../../shared/components/forms'
import { AUDIENCE, AUDIENCE_META, TABS } from '../config/announcementConfig'
import { AnnIcon } from './AnnIcon'
import styles from '../announcements.module.css'

const AUDIENCE_FILTER = [
  { value: '', label: 'All audiences' },
  ...Object.values(AUDIENCE).map((value) => ({ value, label: AUDIENCE_META[value].label })),
]
const STATUS_FILTER = TABS.map((tab) => ({ value: tab.id, label: tab.id === 'all' ? 'All statuses' : tab.label }))

/** Filter button with a small popover: audience and status (status is the same as the tabs). Esc closes it. */
export function FilterPopover({ audience, tabId, onAudienceChange, onTabChange, onClear }) {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const rootRef = useRef(null)
  const active = Boolean(audience) || tabId !== 'all'

  useEffect(() => {
    if (!open) return undefined
    const onDocumentClick = (event) => {
      if (rootRef.current && !rootRef.current.contains(event.target)) setOpen(false)
    }
    document.addEventListener('mousedown', onDocumentClick)
    return () => document.removeEventListener('mousedown', onDocumentClick)
  }, [open])

  return (
    <div
      className={styles.filterRoot}
      ref={rootRef}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && open) {
          event.stopPropagation()
          setOpen(false)
        }
      }}
    >
      <button
        type="button"
        className={`${styles.filterButton} ${active ? styles.filterButtonActive : ''}`}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
      >
        <AnnIcon name="filter" size={15} />
        Filter
        {active && <span className={styles.filterDot} aria-label="Filters applied" />}
      </button>
      {open && (
        <div id={panelId} className={styles.filterPanel} role="group" aria-label="Filter announcements">
          <Select
            label="Audience"
            options={AUDIENCE_FILTER}
            value={audience}
            onChange={(event) => onAudienceChange(event.target.value)}
          />
          <Select label="Status" options={STATUS_FILTER} value={tabId} onChange={(event) => onTabChange(event.target.value)} />
          <div className={styles.filterActions}>
            <Button variant="secondary" size="small" onClick={onClear} disabled={!active}>
              Clear filters
            </Button>
            <Button size="small" onClick={() => setOpen(false)}>
              Done
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
