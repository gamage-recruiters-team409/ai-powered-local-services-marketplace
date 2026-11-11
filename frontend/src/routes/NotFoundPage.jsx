import { Link } from 'react-router-dom'

const pageStyle = {
  minHeight: '100svh',
  display: 'grid',
  placeItems: 'center',
  padding: '24px 16px',
  textAlign: 'center',
}

/** Temporary app-wide fallback until other modules add their routes (home, login, ...). */
export function NotFoundPage() {
  return (
    <main style={pageStyle}>
      <div>
        <h1>Page not found</h1>
        <p>This page isn&apos;t available yet.</p>
        <p>
          <Link to="/admin">Go to the admin portal</Link>
        </p>
      </div>
    </main>
  )
}
