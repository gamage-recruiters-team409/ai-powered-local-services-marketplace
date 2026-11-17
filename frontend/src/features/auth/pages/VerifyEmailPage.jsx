import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import Spinner from '../../../shared/components/feedback/Spinner';
import * as authApi from '../api/authApi';

function SuccessIcon() {
  return (
    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 6L9 17l-5-5" />
    </svg>
  );
}

function WarningIcon() {
  return (
    <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
      <line x1="12" y1="9" x2="12" y2="13" />
      <line x1="12" y1="17" x2="12.01" y2="17" />
    </svg>
  );
}

const STATUS = {
  LOADING: 'loading',
  SUCCESS: 'success',
  ERROR: 'error',
};

function readTokenFromUrl(searchParams) {
  return (
    searchParams.get('token') ||
    (typeof window !== 'undefined' ? new URLSearchParams(window.location.search).get('token') : null)
  );
}

export default function VerifyEmailPage({ previewState } = {}) {
  const [searchParams] = useSearchParams();
  const [token] = useState(() => {
    if (previewState) return null;
    return readTokenFromUrl(searchParams);
  });
  const [status, setStatus] = useState(() => {
    if (previewState === 'success') return STATUS.SUCCESS;
    if (previewState === 'invalid' || previewState === 'expired') return STATUS.ERROR;
    return token ? STATUS.LOADING : STATUS.ERROR;
  });
  const [message, setMessage] = useState(() => {
    if (previewState === 'success') return 'Your account is now active.';
    if (previewState === 'invalid' || previewState === 'expired') return 'Request a new verification link.';
    return token ? '' : 'No verification token found. Please check the link in your email.';
  });
  const calledRef = useRef(false);

  // Strip the token from the URL immediately (security: prevent referrer leakage)
  useEffect(() => {
    if (previewState) return;
    if (typeof window !== 'undefined' && window.location.search.includes('token=')) {
      const cleanUrl = window.location.pathname;
      window.history.replaceState({}, '', cleanUrl);
    }
  }, [previewState]);

  // Set referrer meta tag while this page is mounted
  useEffect(() => {
    let meta = document.querySelector('meta[name="referrer"]');
    const existingContent = meta?.getAttribute('content');

    if (!meta) {
      meta = document.createElement('meta');
      meta.setAttribute('name', 'referrer');
      document.head.appendChild(meta);
    }
    meta.setAttribute('content', 'no-referrer');

    return () => {
      if (existingContent) {
        meta.setAttribute('content', existingContent);
      } else if (meta.parentNode) {
        meta.parentNode.removeChild(meta);
      }
    };
  }, []);

  useEffect(() => {
    if (previewState) return;
    // StrictMode guard: only call once
    if (calledRef.current) return;
    calledRef.current = true;

    if (!token) return;

    authApi
      .verifyEmail(token)
      .then((response) => {
        setMessage(response.message || 'Your account is now active.');
        setStatus(STATUS.SUCCESS);
      })
      .catch((err) => {
        const code = err?.code;
        if (code === 'NETWORK_ERROR' || (err?.status && err.status >= 500)) {
          setMessage('Something went wrong, please try again.');
        } else {
          setMessage('Request a new verification link.');
        }
        setStatus(STATUS.ERROR);
      });
  }, [token, previewState]);

  if (status === STATUS.LOADING) {
    return (
      <div className="auth-card auth-card--narrow">
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '16px', padding: '32px 0' }}>
          <Spinner size={32} label="Verifying your email..." />
          <p style={{ color: '#6b7280', fontSize: '14px' }}>Verifying your email...</p>
        </div>
      </div>
    );
  }

  if (status === STATUS.SUCCESS) {
    return (
      <div className="auth-card auth-card--narrow">
        <div className="auth-card__icon auth-card__icon--teal" style={{ borderRadius: '50%' }}>
          <SuccessIcon />
        </div>
        <h1 className="auth-card__title">Email verified</h1>
        <p className="auth-card__description">
          {message || 'Your account is now active.'}
        </p>
        <Link to="/login" className="shared-btn shared-btn--primary">
          Log in
        </Link>
      </div>
    );
  }

  return (
    <div className="auth-card auth-card--narrow">
      <div className="auth-card__icon auth-card__icon--squircle auth-card__icon--amber">
        <WarningIcon />
      </div>
      <h1 className="auth-card__title">This link has expired or is not valid</h1>
      <p className="auth-card__description">
        {message || 'Request a new verification link.'}
      </p>
      <Link
        to="/check-email"
        className="shared-btn shared-btn--primary"
        style={{ marginBottom: '8px' }}
      >
        Resend verification email
      </Link>
      <p className="auth-countdown-text" style={{ margin: '8px 0 16px' }}>
        You can now request a new email.
      </p>
      <hr className="auth-card__divider" style={{ margin: '16px 0' }} />
      <Link to="/login" className="auth-link auth-link--center">
        ← Back to Login
      </Link>
    </div>
  );
}
