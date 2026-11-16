import { VariantIcon } from './variants.jsx'
import './feedback.css'

/** Small inline message: variant 'error' | 'success' | 'warning' | 'info'. */
export function InlineMessage({ variant = 'info', id, children }) {
  if (!children) return null
  return (
    <p
      id={id}
      className={`fb-inline fb-v-${variant}`}
      role={variant === 'error' ? 'alert' : 'status'}
    >
      <VariantIcon variant={variant} />
      <span>{children}</span>
    </p>
  )
}

/** Field-level error. Link it with aria-describedby={id} on the input. */
export function FieldError({ id, children }) {
  return (
    <InlineMessage variant="error" id={id}>
      {children}
    </InlineMessage>
  )
}

/** Success panel shown after saving or sending something. */
export function SuccessPanel({ title, message, actionLabel, onAction }) {
  return (
    <div className="fb-panel fb-v-success" role="status">
      <VariantIcon variant="success" className="fb-panel-icon" />
      <h2 className="fb-panel-title">{title}</h2>
      {message && <p className="fb-panel-text">{message}</p>}
      {actionLabel && (
        <button type="button" className="fb-btn fb-btn-primary" onClick={onAction}>
          {actionLabel}
        </button>
      )}
    </div>
  )
}

/**
 * Page-level error (network, server, or access). statusCode is optional,
 * for example 403, 404 or 500. Final error pages are still to be confirmed.
 */
export function PageError({
  statusCode,
  title = 'Something went wrong',
  message = 'We could not load this page. Please try again.',
  onRetry,
  retryLabel = 'Try again',
}) {
  return (
    <div className="fb-panel fb-v-error" role="alert">
      <VariantIcon variant="error" className="fb-panel-icon" />
      {statusCode && <span className="fb-panel-code">Error {statusCode}</span>}
      <h2 className="fb-panel-title">{title}</h2>
      <p className="fb-panel-text">{message}</p>
      {onRetry && (
        <button type="button" className="fb-btn fb-btn-primary" onClick={onRetry}>
          {retryLabel}
        </button>
      )}
    </div>
  )
}
