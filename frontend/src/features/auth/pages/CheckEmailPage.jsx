import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';

import Input from '../../../shared/components/forms/Input';
import Alert from '../../../shared/components/feedback/Alert';
import * as authApi from '../api/authApi';
import { validateEmail } from '../utils/validators';

const COOLDOWN_SECONDS = 60;
const COOLDOWN_STORAGE_KEY = 'auth_resend_cooldown_end';

function EnvelopeIcon() {
  return (
    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="2" y="4" width="20" height="16" rx="2" />
      <path d="M22 4L12 13 2 4" />
    </svg>
  );
}

function ResendIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M1 4v6h6" />
      <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
    </svg>
  );
}

export default function CheckEmailPage() {
  const location = useLocation();
  const stateEmail = location.state?.email || '';

  const [email, setEmail] = useState(stateEmail);
  const [showEmailInput, setShowEmailInput] = useState(!stateEmail);
  const [remaining, setRemaining] = useState(0);
  const [resending, setResending] = useState(false);
  const [alert, setAlert] = useState(null);
  const intervalRef = useRef(null);

  // Restore cooldown from sessionStorage
  useEffect(() => {
    try {
      const stored = sessionStorage.getItem(COOLDOWN_STORAGE_KEY);
      if (stored) {
        const endTime = parseInt(stored, 10);
        const diff = Math.ceil((endTime - Date.now()) / 1000);
        if (diff > 0) {
          setRemaining(diff);
        } else {
          sessionStorage.removeItem(COOLDOWN_STORAGE_KEY);
        }
      }
    } catch (err) {
      // Ignore storage error
    }
  }, []);

  // Countdown timer
  useEffect(() => {
    if (remaining <= 0) {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
      return;
    }

    intervalRef.current = setInterval(() => {
      setRemaining((prev) => {
        if (prev <= 1) {
          clearInterval(intervalRef.current);
          intervalRef.current = null;
          try {
            sessionStorage.removeItem(COOLDOWN_STORAGE_KEY);
          } catch (err) {
            // Ignore
          }
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
    };
  }, [remaining > 0]); // eslint-disable-line react-hooks/exhaustive-deps

  const startCooldown = useCallback(() => {
    const endTime = Date.now() + COOLDOWN_SECONDS * 1000;
    setRemaining(COOLDOWN_SECONDS);
    try {
      sessionStorage.setItem(COOLDOWN_STORAGE_KEY, String(endTime));
    } catch (err) {
      // Ignore
    }
  }, []);

  const handleResend = useCallback(async () => {
    setAlert(null);

    const targetEmail = email.trim();
    if (!targetEmail) {
      setShowEmailInput(true);
      return;
    }

    const emailErr = validateEmail(targetEmail);
    if (emailErr) {
      setAlert({ variant: 'error', message: emailErr });
      return;
    }

    setResending(true);
    try {
      await authApi.resendVerification(targetEmail);
      startCooldown();
      setAlert({ variant: 'success', message: 'Verification email sent!' });
    } catch (err) {
      setAlert({
        variant: 'error',
        message: err.message || 'Failed to resend verification email.',
      });
    } finally {
      setResending(false);
    }
  }, [email, startCooldown]);

  return (
    <div className="auth-card auth-card--narrow">
      <div className="auth-card__icon auth-card__icon--teal">
        <EnvelopeIcon />
      </div>

      <h1 className="auth-card__title">Check your email</h1>
      <p className="auth-card__description">
        {stateEmail ? (
          <>
            We sent a verification link to <strong>{stateEmail}</strong>. The link is valid for 24 hours.
          </>
        ) : (
          'We sent a verification link to your email. The link is valid for 24 hours.'
        )}
      </p>

      {alert && (
        <Alert
          variant={alert.variant}
          title={alert.message}
          onClose={() => setAlert(null)}
        />
      )}

      {showEmailInput && !stateEmail && (
        <div style={{ marginBottom: '12px', textAlign: 'left' }}>
          <Input
            label="Email"
            type="email"
            placeholder="e.g. kasun@example.lk"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
          />
        </div>
      )}

      <button
        type="button"
        className="auth-resend-btn"
        onClick={handleResend}
        disabled={remaining > 0 || resending}
      >
        <ResendIcon />
        {resending ? 'Sending...' : 'Resend email'}
      </button>

      {remaining > 0 && (
        <p className="auth-countdown-text">
          You can request a new email in {remaining} seconds
        </p>
      )}

      <hr className="auth-card__divider" style={{ margin: '20px 0' }} />

      <Link to="/login" className="auth-link auth-link--center">
        ← Back to Login
      </Link>
    </div>
  );
}
