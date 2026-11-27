import { useEffect, useRef, useState } from 'react'
import { TRUST_METRICS } from '../data/landingContent'
import { useInViewOnce } from '../hooks/useSpringScrollProgress'
import styles from '../styles/publicLanding.module.css'

function formatMetricNumber(metric, value) {
  if (metric.isOdometer) {
    return `${metric.prefix}${Math.round(value).toLocaleString('en-LK')}${metric.suffix}`
  }
  return `${metric.prefix}${Math.round(value)}${metric.suffix}`
}

/**
 * True variable-width tapered calligraphic emerald crescent (thick lush curve along the
 * bottom-left arc tapering smoothly to razor-sharp tips at top and right).
 */
function TaperedCrescentArc({ id, active }) {
  return (
    <svg
      className={`${styles.taperedArcSvg} ${active ? styles.taperedRevealed : ''}`}
      viewBox="0 0 264 134"
      fill="none"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={`crescent-grad-${id}`} x1="6%" y1="15%" x2="96%" y2="80%">
          <stop offset="0%" stopColor="#14b8a6" />
          <stop offset="45%" stopColor="#059669" />
          <stop offset="100%" stopColor="#065f46" />
        </linearGradient>
      </defs>
      <path
        d="M 104 7 C 46 9, 10 42, 13 79 C 16 113, 62 132, 126 130 C 184 128, 232 98, 254 48 C 232 88, 184 114, 127 116 C 69 118, 29 101, 27 74 C 25 45, 55 16, 104 7 Z"
        fill={`url(#crescent-grad-${id})`}
      />
    </svg>
  )
}

/**
 * True variable-width tapered emerald underline swoosh (thick left-center tapering cleanly
 * into a fine sharp right tip).
 */
function TaperedUnderlineSwoosh({ id, active }) {
  return (
    <svg
      className={`${styles.taperedUnderlineSvg} ${active ? styles.taperedRevealed : ''}`}
      viewBox="0 0 212 18"
      fill="none"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={`underline-grad-${id}`} x1="0%" y1="50%" x2="100%" y2="50%">
          <stop offset="0%" stopColor="#065f46" />
          <stop offset="55%" stopColor="#0d9488" />
          <stop offset="100%" stopColor="#14b8a6" />
        </linearGradient>
      </defs>
      <path
        d="M 8 7 C 64 10, 134 10, 206 5 C 142 14, 70 16, 10 14 C 5 13.6, 4 7.4, 8 7 Z"
        fill={`url(#underline-grad-${id})`}
      />
    </svg>
  )
}

function TaperedMetricItem({ metric, animate, index }) {
  const prefersReduced =
    typeof window !== 'undefined' &&
    Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)').matches)
  const [displayValue, setDisplayValue] = useState(0)

  useEffect(() => {
    if (!animate || prefersReduced || metric.target === 0) return undefined

    const durationMs = metric.isOdometer ? 1400 : 1000
    const startTime = performance.now()
    let rafId = null

    const tick = (now) => {
      const elapsed = now - startTime
      const t = Math.min(elapsed / durationMs, 1)
      const eased = 1 - Math.pow(1 - t, 3)
      setDisplayValue(metric.target * eased)
      if (t < 1) {
        rafId = window.requestAnimationFrame(tick)
      } else {
        setDisplayValue(metric.target)
      }
    }

    rafId = window.requestAnimationFrame(tick)
    return () => {
      if (rafId !== null) window.cancelAnimationFrame(rafId)
    }
  }, [animate, prefersReduced, metric])

  const renderedValue =
    !animate || prefersReduced || metric.target === 0 ? metric.target : displayValue
  const isArcStyle = index === 1 || index === 3
  const isEscrowHighlight = metric.isOdometer

  return (
    <article
      className={`${styles.taperedMetricItem} ${isArcStyle ? styles.taperedMetricArcItem : ''}`}
      style={{ '--metric-delay': `${index * 110}ms` }}
    >
      {isArcStyle && <TaperedCrescentArc id={metric.id} active={animate || prefersReduced} />}

      <div className={styles.taperedMetricContent}>
        <span
          className={`${styles.taperedMetricValue} ${isEscrowHighlight ? styles.taperedMetricValueTeal : ''}`}
        >
          {formatMetricNumber(metric, renderedValue)}
        </span>
        <span className={styles.taperedMetricLabel}>{metric.label}</span>
        {!isArcStyle && (
          <TaperedUnderlineSwoosh id={metric.id} active={animate || prefersReduced} />
        )}
      </div>
    </article>
  )
}

export function TrustMetricsStrip() {
  const sectionRef = useRef(null)
  const inView = useInViewOnce(sectionRef, 0.2)

  return (
    <section
      ref={sectionRef}
      className={styles.taperedTrustSection}
      aria-label="Platform trust metrics"
    >
      <div className={styles.taperedDiagonalAccent} aria-hidden="true" />
      <div className={styles.sectionContainer}>
        <div className={styles.taperedMetricsGrid}>
          {TRUST_METRICS.map((metric, index) => (
            <TaperedMetricItem
              key={metric.id}
              metric={metric}
              animate={inView}
              index={index}
            />
          ))}
        </div>
      </div>
    </section>
  )
}
