import { useState, useEffect, useRef } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { PUBLIC_BRAND, PUBLIC_CITIES } from './publicBrand'
import styles from './PublicNavbar.module.css'

export function PublicNavbar({
  selectedCity = 'Colombo',
  onCityChange,
  cities = PUBLIC_CITIES,
}) {
  const location = useLocation()
  const [scrolled, setScrolled] = useState(false)
  const [cityMenuOpen, setCityMenuOpen] = useState(false)
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const cityRef = useRef(null)

  useEffect(() => {
    const onScroll = () => {
      setScrolled(window.scrollY > 18)
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (cityRef.current && !cityRef.current.contains(event.target)) {
        setCityMenuOpen(false)
      }
    }
    const handleEscape = (event) => {
      if (event.key === 'Escape') {
        setCityMenuOpen(false)
        setMobileMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    document.addEventListener('keydown', handleEscape)
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
      document.removeEventListener('keydown', handleEscape)
    }
  }, [])

  const handleSelectCity = (cityId) => {
    onCityChange?.(cityId)
    setCityMenuOpen(false)
  }

  return (
    <header
      className={`${styles.header} ${scrolled ? styles.headerScrolled : ''}`}
      data-scrolled={scrolled ? 'true' : 'false'}
    >
      <div className={styles.inner}>
        {/* Left: Brand + Primary Links */}
        <div className={styles.leftGroup}>
          <Link to="/" className={styles.brandLink} aria-label="LocalServe Home">
            <span className={styles.logoMark} aria-hidden="true">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <path
                  d="M14.7 6.3a3.5 3.5 0 0 0-4.9 4.9L6.1 14.9a1.5 1.5 0 0 0 2.1 2.1l3.7-3.7a3.5 3.5 0 0 0 4.9-4.9l-2.1 2.1-1.4-1.4 1.4-2.8z"
                  fill="#ffffff"
                />
              </svg>
            </span>
            <span className={styles.wordmark}>
              <span className={styles.wordmarkLocal}>{PUBLIC_BRAND.nameStart}</span>
              <span className={styles.wordmarkServe}>{PUBLIC_BRAND.nameEnd}</span>
            </span>
          </Link>

          <nav className={styles.desktopNav} aria-label="Primary navigation">
            {PUBLIC_BRAND.navLinks.map((item) => {
              const isActive = location.pathname === item.to
              return (
                <Link
                  key={item.to}
                  to={item.to}
                  className={`${styles.navLink} ${isActive ? styles.navLinkActive : ''}`}
                  aria-current={isActive ? 'page' : undefined}
                >
                  {item.label}
                </Link>
              )
            })}
          </nav>
        </div>

        {/* Right: City Dropdown + Provider & Auth Actions */}
        <div className={styles.rightGroup}>
          <div className={styles.cityDropdownWrap} ref={cityRef}>
            <button
              type="button"
              className={styles.cityTrigger}
              aria-haspopup="listbox"
              aria-expanded={cityMenuOpen}
              aria-label={`Selected city: ${selectedCity}`}
              onClick={() => setCityMenuOpen((prev) => !prev)}
            >
              <svg
                width="15"
                height="15"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                className={styles.pinIcon}
                aria-hidden="true"
              >
                <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z" />
                <circle cx="12" cy="10" r="3" />
              </svg>
              <span>{selectedCity}</span>
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.2"
                strokeLinecap="round"
                strokeLinejoin="round"
                className={`${styles.chevron} ${cityMenuOpen ? styles.chevronOpen : ''}`}
                aria-hidden="true"
              >
                <path d="m6 9 6 6 6-6" />
              </svg>
            </button>

            {cityMenuOpen && (
              <ul
                className={styles.cityMenu}
                role="listbox"
                aria-label="Select city"
              >
                {cities.map((city) => {
                  const active = city.id === selectedCity
                  return (
                    <li key={city.id} role="option" aria-selected={active}>
                      <button
                        type="button"
                        className={`${styles.cityOption} ${active ? styles.cityOptionActive : ''}`}
                        onClick={() => handleSelectCity(city.id)}
                      >
                        <span>{city.label}</span>
                        <span className={styles.cityOptionMeta}>{city.region}</span>
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>

          <Link to="/register?role=provider" className={styles.providerBtn}>
            Become a Provider
          </Link>
          <Link to="/login" className={styles.loginLink}>
            Log in
          </Link>
          <Link to="/register" className={styles.signupBtn}>
            Sign up
          </Link>

          <button
            type="button"
            className={styles.mobileMenuBtn}
            aria-label={mobileMenuOpen ? 'Close navigation menu' : 'Open navigation menu'}
            aria-expanded={mobileMenuOpen}
            onClick={() => setMobileMenuOpen((prev) => !prev)}
          >
            {mobileMenuOpen ? (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            ) : (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
                <path d="M4 7h16M4 12h16M4 17h16" />
              </svg>
            )}
          </button>
        </div>
      </div>

      {/* Mobile Drawer */}
      {mobileMenuOpen && (
        <div className={styles.mobileDrawer}>
          <nav className={styles.mobileNavLinks} aria-label="Mobile navigation">
            {PUBLIC_BRAND.navLinks.map((item) => (
              <Link
                key={item.to}
                to={item.to}
                className={styles.mobileNavLink}
                onClick={() => setMobileMenuOpen(false)}
              >
                {item.label}
              </Link>
            ))}
          </nav>

          <div className={styles.mobileCitySection}>
            <span className={styles.mobileCityLabel}>Service City</span>
            <div className={styles.mobileCityPills}>
              {cities.map((city) => (
                <button
                  key={city.id}
                  type="button"
                  className={`${styles.mobileCityPill} ${city.id === selectedCity ? styles.mobileCityPillActive : ''}`}
                  onClick={() => {
                    handleSelectCity(city.id)
                    setMobileMenuOpen(false)
                  }}
                >
                  {city.label}
                </button>
              ))}
            </div>
          </div>

          <div className={styles.mobileActions}>
            <Link
              to="/register?role=provider"
              className={styles.mobileProviderBtn}
              onClick={() => setMobileMenuOpen(false)}
            >
              Become a Provider
            </Link>
            <div className={styles.mobileAuthRow}>
              <Link
                to="/login"
                className={styles.mobileLoginBtn}
                onClick={() => setMobileMenuOpen(false)}
              >
                Log in
              </Link>
              <Link
                to="/register"
                className={styles.mobileSignupBtn}
                onClick={() => setMobileMenuOpen(false)}
              >
                Sign up
              </Link>
            </div>
          </div>
        </div>
      )}
    </header>
  )
}
