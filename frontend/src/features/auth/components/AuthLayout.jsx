import { Link, Outlet } from 'react-router-dom';

import '../auth.css';

function LogoIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z" />
      <path d="M8 12l3 3 5-5" />
    </svg>
  );
}

export default function AuthLayout() {
  return (
    <div className="auth-layout">
      <header className="auth-header">
        <Link to="/" className="auth-header__brand" aria-label="LocalServe home">
          <span className="auth-header__logo">
            <LogoIcon />
          </span>
          <span className="auth-header__title">
            Local<span>Serve</span>
          </span>
        </Link>
        <Link to="/" className="auth-header__home-link">
          Back to Home
        </Link>
      </header>
      <main className="auth-content">
        <Outlet />
      </main>
    </div>
  );
}
