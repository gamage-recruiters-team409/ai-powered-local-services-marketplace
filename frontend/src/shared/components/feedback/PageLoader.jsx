import Spinner from './Spinner.jsx'
import './feedback.css'

/** Full-page or full-panel loading state. */
export default function PageLoader({ label = 'Loading...' }) {
  return (
    <div className="fb-page-loader">
      <Spinner size={40} label="" />
      <span role="status">{label}</span>
    </div>
  )
}
