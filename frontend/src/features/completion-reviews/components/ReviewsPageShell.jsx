import { Link } from 'react-router-dom'

import '../styles/completion.css'
import '../styles/reviews.css'

/**
 * Page frame for the customer review pages (S07–S09): optional breadcrumb, title and subtitle.
 * The Customer Common Layout (WP05) wraps these pages: they are registered in CUSTOMER_ACCOUNT_ROUTES.
 */
export default function ReviewsPageShell({ breadcrumb, title, subtitle, children }) {
  return (
    <section className="rv-page" aria-labelledby="rv-page-title">
      {breadcrumb && (
        <nav className="rv-breadcrumb" aria-label="Breadcrumb">
          <Link to="/customer/reviews">My Reviews</Link>
          <span aria-hidden="true">›</span>
          <span aria-current="page">{breadcrumb}</span>
        </nav>
      )}
      <h1 className="rv-title" id="rv-page-title">
        {title}
      </h1>
      {subtitle && <p className="rv-subtitle">{subtitle}</p>}
      {children}
    </section>
  )
}
