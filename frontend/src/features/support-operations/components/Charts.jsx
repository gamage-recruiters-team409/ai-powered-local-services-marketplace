import { useId, useState } from 'react'

import { formatNumber } from '../utils/format'
import styles from '../operations.module.css'

/**
 * Vertical bar chart built from HTML/CSS (no chart library in the project). Tooltip with exact counts on hover/focus,
 * an aria-label summary and a visually hidden table for screen readers. Every operations chart is a bar chart.
 *
 * @param {{ label: string, title?: string, values: Record<string, number> }[]} data
 * @param {{ key: string, label: string, tone: 'primary' | 'danger' }[]} series
 */
export function OpsBarChart({ data, series, summary, caption, highlightLast = false, showValues = false, height = 190 }) {
  const tableId = useId()
  const [active, setActive] = useState(null)
  const max = Math.max(1, ...data.flatMap((d) => series.map((s) => d.values[s.key] ?? 0)))

  return (
    <figure className={styles.chart} aria-label={summary} role="group">
      <div className={styles.chartPlot} style={{ height }}>
        {[0.25, 0.5, 0.75, 1].map((line) => (
          <span key={line} className={styles.gridLine} style={{ bottom: `${line * 100}%` }} aria-hidden="true" />
        ))}
        {data.map((d, index) => {
          const last = highlightLast && index === data.length - 1
          const spoken = `${d.title ?? d.label}: ${series
            .map((s) => `${s.label} ${formatNumber(d.values[s.key] ?? 0)}`)
            .join(', ')}`
          return (
            <button
              key={d.label + index}
              type="button"
              aria-label={spoken}
              className={styles.barGroup}
              onMouseEnter={() => setActive(index)}
              onMouseLeave={() => setActive(null)}
              onFocus={() => setActive(index)}
              onBlur={() => setActive(null)}
            >
              {series.map((s) => {
                const value = d.values[s.key] ?? 0
                const toneClass = s.tone === 'danger' ? styles.bar_danger : ''
                return (
                  <span
                    key={s.key}
                    className={`${styles.bar} ${toneClass} ${last ? styles.barHighlight : ''}`}
                    style={{ height: `${(value / max) * 100}%` }}
                    aria-hidden="true"
                  >
                    {showValues && series.length === 1 && <span className={styles.barValue}>{formatNumber(value)}</span>}
                  </span>
                )
              })}
              {active === index && (
                <span className={styles.tooltip} aria-hidden="true">
                  <strong>{d.title ?? d.label}</strong>
                  {series.map((s) => (
                    <span key={s.key}>
                      {s.label}: {formatNumber(d.values[s.key] ?? 0)}
                    </span>
                  ))}
                </span>
              )}
            </button>
          )
        })}
      </div>
      <div className={styles.chartAxis} aria-hidden="true">
        {data.map((d, index) => (
          <span key={d.label + index} className={highlightLast && index === data.length - 1 ? styles.axisStrong : ''}>
            {d.label}
          </span>
        ))}
      </div>
      <table id={tableId} className="fb-sr-only">
        {caption && <caption>{caption}</caption>}
        <thead>
          <tr>
            <th scope="col">Category</th>
            {series.map((s) => (
              <th key={s.key} scope="col">
                {s.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((d, index) => (
            <tr key={d.label + index}>
              <th scope="row">{d.title ?? d.label}</th>
              {series.map((s) => (
                <td key={s.key}>{d.values[s.key] ?? 0}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  )
}

/**
 * Horizontal bar list (distribution tiers, top-5 rankings).
 * @param {{ label, sublabel?, value, max, pct?, tone?: 'primary'|'danger'|'dark' }[]} rows
 */
export function HBars({ rows, summary }) {
  const max = Math.max(1, ...rows.map((r) => r.max ?? r.value))
  return (
    <div className={styles.hbars} role="group" aria-label={summary}>
      {rows.map((r, index) => {
        const fillTone = r.tone === 'danger' ? styles.hbarFill_danger : r.tone === 'dark' ? styles.hbarFill_dark : ''
        const pctTone = r.tone === 'danger' ? styles.hbarPct_danger : ''
        return (
          <div key={r.label + index} className={styles.hbarRow}>
            <div className={styles.hbarTop}>
              <span className={styles.hbarName}>
                {r.label}
                {r.sublabel}
              </span>
              <span className={styles.hbarValue}>
                {formatNumber(r.value)}
                {r.pct != null && <span className={`${styles.hbarPct} ${pctTone}`}>({r.pct}%)</span>}
              </span>
            </div>
            <div className={styles.hbarTrack}>
              <div
                className={`${styles.hbarFill} ${fillTone}`}
                style={{ width: `${Math.max(2, ((r.value || 0) / max) * 100)}%` }}
              />
            </div>
          </div>
        )
      })}
    </div>
  )
}

export function Legend({ items }) {
  return (
    <ul className={styles.legend} aria-hidden="true">
      {items.map((item) => (
        <li key={item.label}>
          <span className={`${styles.legendDot} ${styles[`dot_${item.tone}`]}`} />
          {item.label}
        </li>
      ))}
    </ul>
  )
}
