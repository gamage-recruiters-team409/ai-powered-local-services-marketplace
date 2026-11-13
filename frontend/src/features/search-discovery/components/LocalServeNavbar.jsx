/**
 * Top Navigation Bar matching the approved LocalServe design.
 * Includes logo, primary navigation, location selector, notifications, and user avatar.
 */
export function LocalServeNavbar() {
  return (
    <header className="localserve-navbar">
      <div className="navbar-container">
        {/* Left: Brand & Navigation */}
        <div className="navbar-left">
          <a href="/" className="navbar-brand" aria-label="LocalServe Home">
            <span className="brand-logo-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" className="brand-icon-svg">
                <rect width="24" height="24" rx="6" fill="#0f766e" />
                <path
                  d="M14.7 6.3a3.5 3.5 0 0 0-4.9 4.9L6.1 14.9a1.5 1.5 0 0 0 2.1 2.1l3.7-3.7a3.5 3.5 0 0 0 4.9-4.9l-2.1 2.1-1.4-1.4 1.4-2.8z"
                  fill="#ffffff"
                />
              </svg>
            </span>
            <span className="brand-title">LocalServe</span>
          </a>

          <nav className="navbar-nav" aria-label="Primary navigation">
            <a href="/" className="nav-link">Home</a>
            <a href="/search" className="nav-link nav-link-active" aria-current="page">
              Find Services
            </a>
            <a href="#bookings" className="nav-link">My Bookings</a>
          </nav>
        </div>

        {/* Right: User & Utility items */}
        <div className="navbar-right">
          {/* Location Picker Pill */}
          <div className="location-pill" title="Selected Location">
            <svg
              className="pill-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
              <circle cx="12" cy="10" r="3" />
            </svg>
            <span className="location-text">Austin, TX</span>
            <span className="dropdown-caret">⌄</span>
          </div>

          {/* Messages */}
          <button
            type="button"
            className="nav-icon-btn"
            aria-label="Messages (2 unread)"
            title="Messages"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="utility-svg"
              aria-hidden="true"
            >
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
            <span className="nav-badge">2</span>
          </button>

          {/* Notifications */}
          <button
            type="button"
            className="nav-icon-btn"
            aria-label="Notifications (5 unread)"
            title="Notifications"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="utility-svg"
              aria-hidden="true"
            >
              <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
              <path d="M13.73 21a2 2 0 0 1-3.46 0" />
            </svg>
            <span className="nav-badge">5</span>
          </button>

          {/* User Profile Dropdown Pill */}
          <div className="user-profile-pill">
            <div className="user-avatar-circle" aria-hidden="true">NP</div>
            <span className="user-name">Nimal P.</span>
            <span className="dropdown-caret">⌄</span>
          </div>
        </div>
      </div>
    </header>
  );
}

export default LocalServeNavbar;

