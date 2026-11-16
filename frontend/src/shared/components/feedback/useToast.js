import { useContext } from 'react'
import { ToastContext } from './ToastContext.js'

/**
 * const toast = useToast()
 * toast.success('Saved')
 * toast.error('Could not send the message')
 * toast.message('New message from Nimal', { action: { label: 'Open', onClick } })
 */
export function useToast() {
  const context = useContext(ToastContext)
  if (!context) {
    throw new Error('useToast must be used inside <ToastProvider>')
  }
  return context
}
