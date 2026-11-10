import { InboxIcon, SearchIcon, XCircleIcon } from './icons.jsx'
import './feedback.css'

const DEFAULT_ICONS = {
  default: InboxIcon,
  search: SearchIcon,
  error: XCircleIcon,
}

/**
 * Empty state: icon, short title, one sentence, one action.
 * variant: 'default' (no data / first use) | 'search' (no results) | 'error'
 * icon: optional custom element replacing the default icon.
 */
export default function EmptyState({
  variant = 'default',
  icon,
  title,
  message,
  actionLabel,
  onAction,
  compact = false,
}) {
  const DefaultIcon = DEFAULT_ICONS[variant] ?? InboxIcon
  return (
    <div
      className={`fb-empty fb-empty-${variant}${compact ? ' fb-empty-compact' : ''}`}
    >
      <div className="fb-empty-icon">{icon ?? <DefaultIcon />}</div>
      <h2 className="fb-empty-title">{title}</h2>
      {message && <p className="fb-empty-text">{message}</p>}
      {actionLabel && (
        <button type="button" className="fb-btn fb-btn-primary" onClick={onAction}>
          {actionLabel}
        </button>
      )}
    </div>
  )
}
