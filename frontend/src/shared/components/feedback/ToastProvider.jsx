import { useCallback, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { ToastContext } from './ToastContext.js'
import Toast from './Toast.jsx'
import './feedback.css'

const DEFAULT_DURATION = {
  success: 4000,
  info: 4000,
  message: 5000,
  warning: 6000,
  error: 8000,
}

/**
 * Wrap the app once (a single provider). Toasts stack in the corner,
 * auto-dismiss, and can be closed. Pass { duration: 0 } to keep one open.
 */
export default function ToastProvider({ children, maxToasts = 4 }) {
  const [toasts, setToasts] = useState([])
  const counter = useRef(0)

  const dismiss = useCallback((id) => {
    setToasts((list) => list.filter((item) => item.id !== id))
  }, [])

  const show = useCallback(
    (variant, message, options = {}) => {
      counter.current += 1
      const id = counter.current
      const toast = {
        id,
        variant,
        message,
        title: options.title,
        action: options.action,
        duration: options.duration ?? DEFAULT_DURATION[variant] ?? 4000,
      }
      setToasts((list) => [...list, toast].slice(-maxToasts))
      return id
    },
    [maxToasts],
  )

  const api = useMemo(
    () => ({
      show,
      dismiss,
      success: (message, options) => show('success', message, options),
      error: (message, options) => show('error', message, options),
      warning: (message, options) => show('warning', message, options),
      info: (message, options) => show('info', message, options),
      message: (message, options) => show('message', message, options),
    }),
    [show, dismiss],
  )

  return (
    <ToastContext.Provider value={api}>
      {children}
      {createPortal(
        <div className="fb-toast-region" aria-live="polite">
          {toasts.map((toast) => (
            <Toast key={toast.id} toast={toast} onDismiss={dismiss} />
          ))}
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  )
}
