import { useState, useEffect, useRef } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { PUBLIC_BRAND, PUBLIC_CITIES } from './publicBrand'
import styles from './PublicNavbar.module.css'

function getUserDisplayName(user) {
  if (!user) return ''
  return (
    user.fullName?.trim() ||
    user.name?.trim() ||
    user.email?.trim() ||
    'Account'
  )
}

function getUserInitials(name) {
  const parts = String(name || 'U')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
  if (parts.length >= 2) {
    return `${parts[0][0]}${parts[1][0]}`.toUpperCase()
  }
  return (parts[0] || 'U').slice(0, 2).toUpperCase()
}

function formatRoleLabel(role) {
  if (!role) return null
  if (role === 'SUPER_ADMIN' || role === 'ADMIN') return 'Admin'
  if (role === 'PROVIDER') return 'Provider'
  if (role === 'CUSTOMER') return 'Customer'
  return null
}

export function PublicNavbar({
  selectedCity = 'Colombo',
  onCityChange,
  cities = PUBLIC_CITIES,
  user = null,
  accountMenuItems = [],
  showBecomeProvider = true,
  onLogout,
}) {
  const location = useLocation()
  const [scrolled, setScrolled] = useState(false)
  const [cityMenuOpen, setCityMenuOpen] = useState(false)
  const [accountMenuOpen, setAccountMenuOpen] = useState(false)
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const cityRef = useRef(null)
  const accountRef = useRef(null)

  const displayName = getUserDisplayName(user)
  const initials = getUserInitials(displayName)
  const roleLabel = formatRoleLabel(user?.role)
  const menuItems = Array.isArray(accountMenuItems) ? accountMenuItems : []

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
      if (accountRef.current && !accountRef.current.contains(event.target)) {
        setAccountMenuOpen(false)
      }
    }
    const handleEscape = (event) => {
      if (event.key === 'Escape') {
        setCityMenuOpen(false)
        setAccountMenuOpen(false)
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

  const handleLogoutClick = async () => {
    if (loggingOut) return
    setAccountMenuOpen(false)
    setMobileMenuOpen(false)
    if (typeof onLogout !== 'function') return
    setLoggingOut(true)
    try {
      await onLogout()
    } finally {
      setLoggingOut(false)
    }
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
              onClick={() => {
                setAccountMenuOpen(false)
                setCityMenuOpen((prev) => !prev)
              }}
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

          {showBecomeProvider && (
            <Link to="/register?role=provider" className={styles.providerBtn}>
              Become a Provider
            </Link>
          )}

          {user ? (
            <div className={styles.accountDropdownWrap} ref={accountRef}>
              <button
                type="button"
                className={styles.accountTrigger}
                aria-haspopup="menu"
                aria-expanded={accountMenuOpen}
                aria-label={`Account menu: ${displayName}`}
                onClick={() => {
                  setCityMenuOpen(false)
                  setAccountMenuOpen((prev) => !prev)
                }}
              >
                <span className={styles.accountAvatar} aria-hidden="true">
                  {initials}
                </span>
                <span className={styles.accountName}>{displayName}</span>
                <svg
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  className={`${styles.chevron} ${accountMenuOpen ? styles.chevronOpen : ''}`}
                  aria-hidden="true"
                >
                  <path d="m6 9 6 6 6-6" />
                </svg>
              </button>

              {accountMenuOpen && (
                <div
                  className={styles.accountMenu}
                  role="menu"
                  aria-label="Account navigation"
                >
                  <div className={styles.accountMenuHeader}>
                    <span className={styles.accountMenuNameRow}>
                      <strong className={styles.accountMenuName}>{displayName}</strong>
                      {roleLabel && (
                        <span className={styles.accountMenuRole}>{roleLabel}</span>
                      )}
                    </span>
                    {user.email && (
                      <span className={styles.accountMenuEmail}>{user.email}</span>
                    )}
                  </div>

                  {menuItems.length > 0 && (
                    <div className={styles.accountMenuList}>
                      {menuItems.map((item) => (
                        <Link
                          key={item.id || item.to}
                          to={item.to}
                          role="menuitem"
                          className={styles.accountMenuItem}
                          onClick={() => setAccountMenuOpen(false)}
                        >
                          {item.label}
                        </Link>
                      ))}
                    </div>
                  )}

                  {typeof onLogout === 'function' && (
                    <>
                      <div className={styles.accountMenuDivider} role="separator" />
                      <button
                        type="button"
                        role="menuitem"
                        className={styles.accountLogoutBtn}
                        disabled={loggingOut}
                        onClick={handleLogoutClick}
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
                          aria-hidden="true"
                        >
                          <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                          <polyline points="16 17 21 12 16 7" />
                          <line x1="21" y1="12" x2="9" y2="12" />
                        </svg>
                        <span>{loggingOut ? 'Logging out...' : 'Log out'}</span>
                      </button>
                    </>
                  )}
                </div>
              )}
            </div>
          ) : (
            <>
              <Link to="/login" className={styles.loginLink}>
                Log in
              </Link>
              <Link to="/register" className={styles.signupBtn}>
                Sign up
              </Link>
            </>
          )}

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
            {showBecomeProvider && (
              <Link
                to="/register?role=provider"
                className={styles.mobileProviderBtn}
                onClick={() => setMobileMenuOpen(false)}
              >
                Become a Provider
              </Link>
            )}

            {user ? (
              <div className={styles.mobileAccountSection}>
                <div className={styles.mobileAccountCard}>
                  <span className={styles.accountAvatar} aria-hidden="true">
                    {initials}
                  </span>
                  <div className={styles.mobileAccountMeta}>
                    <span className={styles.accountMenuNameRow}>
                      <strong className={styles.accountMenuName}>{displayName}</strong>
                      {roleLabel && (
                        <span className={styles.accountMenuRole}>{roleLabel}</span>
                      )}
                    </span>
                    {user.email && (
                      <span className={styles.accountMenuEmail}>{user.email}</span>
                    )}
                  </div>
                </div>

                {menuItems.length > 0 && (
                  <nav className={styles.mobileAccountLinks} aria-label="Account links">
                    {menuItems.map((item) => (
                      <Link
                        key={item.id || item.to}
                        to={item.to}
                        className={styles.mobileAccountLink}
                        onClick={() => setMobileMenuOpen(false)}
                      >
                        {item.label}
                      </Link>
                    ))}
                  </nav>
                )}

                {typeof onLogout === 'function' && (
                  <button
                    type="button"
                    className={styles.mobileLogoutBtn}
                    disabled={loggingOut}
                    onClick={handleLogoutClick}
                  >
                    {loggingOut ? 'Logging out...' : 'Log out'}
                  </button>
                )}
              </div>
            ) : (
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
            )}
          </div>
        </div>
      )}
    </header>
  )
}
