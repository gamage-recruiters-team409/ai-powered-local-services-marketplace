import { Link } from 'react-router-dom'
import { PUBLIC_BRAND } from './publicBrand'
import styles from './PublicFooter.module.css'

function FooterNavItem({ item }) {
  if (item.to) {
    return (
      <Link to={item.to} className={styles.columnLink}>
        {item.label}
      </Link>
    )
  }
  return (
    <a href={item.href} className={styles.columnLink}>
      {item.label}
    </a>
  )
}

export function PublicFooter() {
  return (
    <footer className={styles.footer} aria-label="Site footer">
      <div className={styles.inner}>
        <div className={styles.columnsGrid}>
          {/* Column 1: Brand Identity & Trust Seal */}
          <div className={styles.brandCol}>
            <Link to="/" className={styles.wordmark} aria-label="LocalServe Home">
              <span className={styles.wordmarkLocal}>{PUBLIC_BRAND.nameStart}</span>
              <span className={styles.wordmarkServe}>{PUBLIC_BRAND.nameEnd}</span>
            </Link>
            <p className={styles.tagline}>{PUBLIC_BRAND.tagline}</p>
            <div className={styles.trustBadge}>
              <svg
                width="15"
                height="15"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" />
                <path d="m9 12 2 2 4-4" />
              </svg>
              <span>{PUBLIC_BRAND.trustBadge}</span>
            </div>
          </div>

          {/* Column 2: Services Directory */}
          <nav className={styles.linkCol} aria-label="Footer services">
            <h3 className={styles.columnTitle}>Services</h3>
            <ul className={styles.columnList}>
              {PUBLIC_BRAND.footerColumns.services.map((item) => (
                <li key={item.label}>
                  <FooterNavItem item={item} />
                </li>
              ))}
            </ul>
          </nav>

          {/* Column 3: Top Cities */}
          <nav className={styles.linkCol} aria-label="Footer top cities">
            <h3 className={styles.columnTitle}>Top Cities</h3>
            <ul className={styles.columnList}>
              {PUBLIC_BRAND.footerColumns.cities.map((item) => (
                <li key={item.label}>
                  <FooterNavItem item={item} />
                </li>
              ))}
            </ul>
          </nav>

          {/* Column 4: Platform & Support */}
          <div className={styles.linkCol}>
            <h3 className={styles.columnTitle}>Platform &amp; Contact</h3>
            <nav aria-label="Footer platform links">
              <ul className={styles.columnList}>
                {PUBLIC_BRAND.footerColumns.platform.map((item) => (
                  <li key={item.label}>
                    <FooterNavItem item={item} />
                  </li>
                ))}
              </ul>
            </nav>
            <div className={styles.supportBlock}>
              <a
                href={`mailto:${PUBLIC_BRAND.supportEmail}`}
                className={styles.supportEmailLink}
              >
                {PUBLIC_BRAND.supportEmail}
              </a>
              <span className={styles.supportHours}>{PUBLIC_BRAND.supportHours}</span>
            </div>
          </div>
        </div>

        <div className={styles.bottomBar}>
          <p className={styles.copyright}>{PUBLIC_BRAND.copyright}</p>
          <nav className={styles.legalLinks} aria-label="Legal navigation">
            {PUBLIC_BRAND.legalLinks.map((item) => (
              <a key={item.label} href={item.href} className={styles.legalLink}>
                {item.label}
              </a>
            ))}
          </nav>
        </div>
      </div>
    </footer>
  )
}
