import './feedback.css'

/** Basic skeleton block. variant: 'text' | 'rect' | 'circle'. */
export function Skeleton({ variant = 'text', width = '100%', height }) {
  const h = height ?? (variant === 'text' ? 14 : variant === 'circle' ? 40 : 80)
  const w = variant === 'circle' ? h : width
  return (
    <span
      className={`fb-skeleton${variant === 'circle' ? ' fb-skeleton-circle' : ''}`}
      style={{ width: w, height: h }}
      aria-hidden="true"
    />
  )
}

/** List skeleton: avatar plus two lines per row (for example a conversation list). */
export function SkeletonList({ rows = 5 }) {
  return (
    <div role="status" aria-label="Loading">
      {Array.from({ length: rows }, (_, index) => (
        <div className="fb-skel-row" key={index}>
          <Skeleton variant="circle" height={44} />
          <div className="fb-skel-col">
            <Skeleton width="40%" />
            <Skeleton width="80%" height={12} />
          </div>
        </div>
      ))}
    </div>
  )
}

/** Card skeleton. */
export function SkeletonCard() {
  return (
    <div className="fb-skel-card" role="status" aria-label="Loading">
      <Skeleton variant="rect" height={120} />
      <Skeleton width="60%" />
      <Skeleton width="90%" height={12} />
      <Skeleton width="45%" height={12} />
    </div>
  )
}

/** Table skeleton. */
export function SkeletonTable({ rows = 5, columns = 4 }) {
  return (
    <div role="status" aria-label="Loading">
      {Array.from({ length: rows }, (_, index) => (
        <div
          className="fb-skel-table-row"
          key={index}
          style={{ gridTemplateColumns: `repeat(${columns}, 1fr)` }}
        >
          {Array.from({ length: columns }, (_, col) => (
            <Skeleton key={col} width={col === 0 ? '70%' : '90%'} />
          ))}
        </div>
      ))}
    </div>
  )
}
