import { WifiOffIcon } from './icons.jsx'
import Spinner from './Spinner.jsx'
import Alert from './Alert.jsx'
import './feedback.css'

/**
 * Connection banner for chat and notifications.
 * status: 'online' (renders nothing) | 'reconnecting' | 'offline'
 */
export default function ConnectionBanner({ status = 'online', message }) {
  if (status === 'online') return null

  if (status === 'reconnecting') {
    return (
      <div className="fb-banner" role="status">
        <Spinner size={16} label="" />
        <span>{message ?? 'Connection lost. Trying to reconnect...'}</span>
      </div>
    )
  }

  return (
    <div className="fb-banner fb-banner-offline" role="alert">
      <WifiOffIcon />
      <span>{message ?? 'You are offline. Check your internet connection.'}</span>
    </div>
  )
}

/** "Session expired" message with a log-in action (C8). */
export function SessionExpiredNotice({ onLogin }) {
  return (
    <Alert
      variant="warning"
      title="Your session has expired"
      action={{ label: 'Log in again', onClick: onLogin }}
    >
      For your security you were signed out. Please log in again to continue.
    </Alert>
  )
}
