import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import Spinner from '../../../shared/components/feedback/Spinner';
import Alert from '../../../shared/components/feedback/Alert';
import * as authApi from '../api/authApi';

function SuccessIcon() {
  return (
    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 6L9 17l-5-5" />
    </svg>
  );
}

function WarningIcon() {
  return (
    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="10" />
      <line x1="12" y1="8" x2="12" y2="12" />
      <line x1="12" y1="16" x2="12.01" y2="16" />
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

export default function VerifyEmailPage() {
  const [searchParams] = useSearchParams();
  const [token] = useState(() => readTokenFromUrl(searchParams));
  const [status, setStatus] = useState(STATUS.LOADING);
  const [message, setMessage] = useState('');
  const calledRef = useRef(false);

  // Strip the token from the URL immediately (security: prevent referrer leakage)
  useEffect(() => {
    if (typeof window !== 'undefined' && window.location.search.includes('token=')) {
      const cleanUrl = window.location.pathname;
      window.history.replaceState({}, '', cleanUrl);
    }
  }, []);

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

  const verifyToken = useCallback(async (tokenToVerify) => {
    try {
      const response = await authApi.verifyEmail(tokenToVerify);
      setMessage(response.message || 'Your email has been verified successfully!');
      setStatus(STATUS.SUCCESS);
    } catch (err) {
      setMessage(err.message || 'This link is invalid or has expired.');
      setStatus(STATUS.ERROR);
    }
  }, []);

  useEffect(() => {
    // StrictMode guard: only call once
    if (calledRef.current) return;
    calledRef.current = true;

    if (!token) {
      setMessage('No verification token found. Please check the link in your email.');
      setStatus(STATUS.ERROR);
      return;
    }

    verifyToken(token);
  }, [token, verifyToken]);

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
        <div className="auth-card__icon auth-card__icon--green">
          <SuccessIcon />
        </div>
        <h1 className="auth-card__title">Email verified!</h1>
        <p className="auth-card__description">{message}</p>
        <Link to="/login" className="auth-link auth-link--center">
          Continue to Login →
        </Link>
      </div>
    );
  }

  return (
    <div className="auth-card auth-card--narrow">
      <div className="auth-card__icon" style={{ backgroundColor: '#fef2f2', color: '#dc2626' }}>
        <WarningIcon />
      </div>
      <h1 className="auth-card__title">Verification failed</h1>
      <Alert variant="error" title={message} />
      <div style={{ marginTop: '16px' }}>
        <p style={{ color: '#6b7280', fontSize: '14px', marginBottom: '12px' }}>
          The link may have expired or already been used.
        </p>
        <Link to="/login" className="auth-link auth-link--center">
          ← Back to Login
        </Link>
      </div>
    </div>
  );
}
