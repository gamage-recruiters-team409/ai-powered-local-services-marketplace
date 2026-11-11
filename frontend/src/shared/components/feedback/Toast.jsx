import { useEffect } from 'react'
import { CloseIcon } from './icons.jsx'
import { VariantIcon } from './variants.jsx'

export default function Toast({ toast, onDismiss }) {
  const { id, variant, title, message, duration, action } = toast

  useEffect(() => {
    if (!duration) return undefined
    const timer = setTimeout(() => onDismiss(id), duration)
    return () => clearTimeout(timer)
  }, [id, duration, onDismiss])

  return (
    <div
      className={`fb-toast fb-v-${variant}`}
      role={variant === 'error' ? 'alert' : 'status'}
    >
      <VariantIcon variant={variant} className="fb-toast-icon" />
      <div className="fb-toast-body">
        {title && <p className="fb-toast-title">{title}</p>}
        <p className="fb-toast-text">{message}</p>
        {action && (
          <button
            type="button"
            className="fb-link-btn"
            onClick={() => {
              action.onClick?.()
              onDismiss(id)
            }}
          >
            {action.label}
          </button>
        )}
      </div>
      <button
        type="button"
        className="fb-icon-btn"
        aria-label="Dismiss notification"
        onClick={() => onDismiss(id)}
      >
        <CloseIcon />
      </button>
    </div>
  )
}
