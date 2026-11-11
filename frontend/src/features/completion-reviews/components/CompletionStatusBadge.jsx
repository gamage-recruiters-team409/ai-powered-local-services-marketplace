import { COMPLETION_STATUS } from '../utils/completionUtils'

/** Status pill: "• Awaiting Confirmation" etc. */
export default function CompletionStatusBadge({ status }) {
  const config = COMPLETION_STATUS[status]
  if (!config) {
    return null
  }
  return (
    <span className={`sc-badge sc-badge--${config.tone}`}>
      <span className="sc-badge__dot" aria-hidden="true" />
      {config.label}
    </span>
  )
}
