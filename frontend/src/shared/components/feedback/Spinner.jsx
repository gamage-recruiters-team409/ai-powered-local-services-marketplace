import './feedback.css'

/** Spinner. Use size={16} inside a button. label="" hides the screen-reader text. */
export default function Spinner({ size = 24, label = 'Loading' }) {
  return (
    <span
      className="fb-spinner"
      style={{ '--fb-spinner-size': `${size}px` }}
      role={label ? 'status' : undefined}
      aria-label={label || undefined}
      aria-hidden={label ? undefined : true}
    />
  )
}
