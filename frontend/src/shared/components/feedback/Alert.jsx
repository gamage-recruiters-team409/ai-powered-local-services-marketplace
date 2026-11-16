import { CloseIcon } from './icons.jsx'
import { VariantIcon } from './variants.jsx'
import './feedback.css'

/**
 * Page-level or inline alert.
 * variant: 'info' | 'success' | 'warning' | 'error'
 * action: { label, onClick } (optional text button under the message)
 */
export default function Alert({
  variant = 'info',
  title,
  children,
  onClose,
  action,
  inline = false,
  className = '',
}) {
  return (
    <div
      className={`fb-alert fb-v-${variant}${inline ? ' fb-alert-inline' : ''} ${className}`.trim()}
      role={variant === 'error' ? 'alert' : 'status'}
    >
      <VariantIcon variant={variant} className="fb-alert-icon" />
      <div className="fb-alert-body">
        {title && <p className="fb-alert-title">{title}</p>}
        {children && <div className="fb-alert-text">{children}</div>}
        {action && (
          <div className="fb-alert-action">
            <button type="button" className="fb-link-btn" onClick={action.onClick}>
              {action.label}
            </button>
          </div>
        )}
      </div>
      {onClose && (
        <button type="button" className="fb-icon-btn" aria-label="Dismiss" onClick={onClose}>
          <CloseIcon />
        </button>
      )}
    </div>
  )
}
