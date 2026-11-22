import { Link } from 'react-router-dom'
import { PUBLIC_BRAND } from './publicBrand'
import styles from './PublicFooter.module.css'

export function PublicFooter() {
  return (
    <footer className={styles.footer}>
      <div className={styles.inner}>
        <div className={styles.leftGroup}>
          <Link to="/" className={styles.wordmark} aria-label="LocalServe Home">
            <span className={styles.wordmarkLocal}>{PUBLIC_BRAND.nameStart}</span>
            <span className={styles.wordmarkServe}>{PUBLIC_BRAND.nameEnd}</span>
          </Link>

          <nav className={styles.links} aria-label="Footer navigation">
            {PUBLIC_BRAND.footerLinks.map((item) => (
              <a key={item.label} href={item.href} className={styles.link}>
                {item.label}
              </a>
            ))}
          </nav>
        </div>

        <p className={styles.copyright}>{PUBLIC_BRAND.copyright}</p>
      </div>
    </footer>
  )
}
