import { Link } from 'react-router-dom'

import { Skeleton, SkeletonCard } from '../../../shared/components/feedback'
import { Button } from '../../../shared/components/forms'
import styles from '../disputes.module.css'

/** Loading placeholder shaped like the dispute screen. */
export function DisputeSkeleton() {
  return (
    <div className={styles.page} aria-busy="true">
      <p className={styles.srOnly} role="status">Loading dispute…</p>
      <Skeleton width="220px" />
      <Skeleton width="320px" height={28} />
      <Skeleton variant="rect" height={44} />
      <div className={styles.grid}>
        <div className={styles.column}>
          <SkeletonCard />
          <SkeletonCard />
        </div>
        <SkeletonCard />
      </div>
    </div>
  )
}

function StateCard({ title, message, children }) {
  return (
    <div className={styles.page}>
      <section className={`${styles.card} ${styles.state}`} aria-labelledby="dispute-state-title">
        <h1 id="dispute-state-title">{title}</h1>
        <p>{message}</p>
        {children}
      </section>
    </div>
  )
}

export function DisputeErrorState({ message, onRetry }) {
  return (
    <StateCard title="Something went wrong" message={message}>
      {onRetry && <Button onClick={onRetry}>Try again</Button>}
    </StateCard>
  )
}

export function DisputeNotFound({ backTo = '/' }) {
  return (
    <StateCard
      title="Dispute not found"
      message="We could not find this dispute. It may have been removed, or the link may be wrong."
    >
      <Link to={backTo}>Go back</Link>
    </StateCard>
  )
}

export function DisputeAccessDenied() {
  return (
    <StateCard
      title="Access denied"
      message="You do not have access to this dispute. Only the customer and provider of the booking can open it."
    />
  )
}
