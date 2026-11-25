import { useId, useState } from 'react'

import { formatNumber } from '../utils/format'
import styles from '../adminUsers.module.css'

/**
 * Bar chart built from plain HTML/CSS (no chart library in the project). Grouped bars per category, a tooltip with
 * the exact counts on hover / focus, and for screen readers an aria-label summary plus a visually hidden table.
 *
 * @param {{ label: string, title?: string, values: Record<string, number> }[]} data
 * @param {{ key: string, label: string, tone: 'primary' | 'secondary' }[]} series
 */
export function BarChart({ data, series, summary, caption, highlightLast = false, height = 180 }) {
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
              <span className={styles.bars} aria-hidden="true">
                {series.map((s) => {
                  const value = d.values[s.key] ?? 0
                  return (
                    <span
                      key={s.key}
                      className={`${styles.bar} ${styles[`bar_${s.tone}`]} ${last ? styles.barHighlight : ''}`}
                      style={{ height: `${(value / max) * 100}%` }}
                    >
                      {series.length === 1 && <span className={styles.barValue}>{formatNumber(value)}</span>}
                    </span>
                  )
                })}
              </span>
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
            <th scope="col">Period</th>
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
 * Donut chart in SVG. Segments are drawn with stroke-dasharray on one circle each.
 * @param {{ key: string, label: string, value: number, tone: string }[]} segments
 */
export function DonutChart({ segments, centerValue, centerLabel, summary }) {
  const total = segments.reduce((sum, s) => sum + s.value, 0)
  const radius = 42
  const circumference = 2 * Math.PI * radius
  let offset = 0
  return (
    <div className={styles.donut} role="img" aria-label={summary}>
      <svg viewBox="0 0 100 100" className={styles.donutSvg} aria-hidden="true">
        <circle cx="50" cy="50" r={radius} className={styles.donutTrack} />
        {total > 0 &&
          segments.map((s) => {
            const length = (s.value / total) * circumference
            const circle = (
              <circle
                key={s.key}
                cx="50"
                cy="50"
                r={radius}
                className={`${styles.donutSegment} ${styles[`seg_${s.tone}`]}`}
                strokeDasharray={`${length} ${circumference - length}`}
                strokeDashoffset={-offset}
              />
            )
            offset += length
            return circle
          })}
      </svg>
      <div className={styles.donutCenter} aria-hidden="true">
        <strong>{centerValue}</strong>
        <span>{centerLabel}</span>
      </div>
    </div>
  )
}
