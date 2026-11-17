import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import Button from '../../../shared/components/forms/Button';
import PasswordInput from '../../../shared/components/forms/PasswordInput';
import Alert from '../../../shared/components/feedback/Alert';
import PasswordRules from '../components/PasswordRules';
import { getErrorMessage } from '../utils/errorMessages';
import { checkPasswordCriteria, validatePassword } from '../utils/validators';
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

function ClockIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="10" />
      <path d="M12 6v6l4 2" />
    </svg>
  );
}

const STATUS = {
  FORM: 'form',
  SUCCESS: 'success',
  EXPIRED: 'expired',
};

export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const tokenParam =
    searchParams.get('token') ||
    (typeof window !== 'undefined' ? new URLSearchParams(window.location.search).get('token') : null);
  const tokenRef = useRef(tokenParam);

  const [password, setPassword] = useState('');
  const [passwordError, setPasswordError] = useState(null);
  const [serverError, setServerError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [pageStatus, setPageStatus] = useState(tokenRef.current ? STATUS.FORM : STATUS.EXPIRED);

  const criteria = checkPasswordCriteria(password);

  // Strip token from URL immediately
  useEffect(() => {
    if (typeof window !== 'undefined' && window.location.search.includes('token=')) {
      const cleanUrl = window.location.pathname;
      window.history.replaceState({}, '', cleanUrl);
    }
  }, []);

  // Set referrer meta tag
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

  const handleSubmit = useCallback(
    async (e) => {
      e.preventDefault();
      setServerError(null);
      setPasswordError(null);

      const err = validatePassword(password);
      if (err) {
        setPasswordError(err);
        return;
      }

      setLoading(true);
      try {
        await authApi.resetPassword(tokenRef.current, password);
        setPageStatus(STATUS.SUCCESS);
      } catch (apiErr) {
        const code = apiErr.code || '';

        if (code === 'INVALID_OR_EXPIRED_TOKEN' || code === 'TOKEN_EXPIRED' || code === 'TOKEN_REUSED') {
          setPageStatus(STATUS.EXPIRED);
          return;
        }

        setServerError(getErrorMessage(code, apiErr.message));
      } finally {
        setLoading(false);
      }
    },
    [password]
  );

  if (pageStatus === STATUS.SUCCESS) {
    return (
      <div className="auth-card auth-card--narrow">
        <div className="auth-card__icon auth-card__icon--green">
          <SuccessIcon />
        </div>
        <h1 className="auth-card__title">Password reset successful</h1>
        <p className="auth-card__description">
          Your password has been updated. You can now log in with your new password.
        </p>
        <Link to="/login" className="auth-link auth-link--center" style={{ fontWeight: 600 }}>
          Continue to Login →
        </Link>
      </div>
    );
  }

  if (pageStatus === STATUS.EXPIRED) {
    return (
      <div className="auth-card auth-card--narrow">
        <div className="auth-card__icon" style={{ backgroundColor: '#fef2f2', color: '#dc2626' }}>
          <WarningIcon />
        </div>
        <h1 className="auth-card__title">Link expired</h1>
        <p className="auth-card__description">
          This password reset link is invalid or has expired. Please request a new one.
        </p>
        <Link to="/forgot-password" className="auth-link auth-link--center" style={{ marginBottom: '12px' }}>
          Request new link
        </Link>
        <Link to="/login" className="auth-link auth-link--center">
          ← Back to Login
        </Link>
      </div>
    );
  }

  return (
    <div className="auth-card">
      <h1 className="auth-card__title">Set a new password</h1>
      <p className="auth-card__description">
        Enter your new password below to secure your marketplace account.
      </p>

      {serverError && (
        <Alert variant="error" title={serverError} onClose={() => setServerError(null)} />
      )}

      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
            <label className="shared-field__label" htmlFor="reset-password">New password</label>
            <span style={{ fontSize: '12px', color: '#6b7280' }}>
              {criteria.validCount}/4 criteria
            </span>
          </div>
          <PasswordInput
            id="reset-password"
            placeholder="Enter new password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={passwordError}
            autoComplete="new-password"
            disabled={loading}
          />
        </div>

        <PasswordRules password={password} />

        <p className="auth-link-validity">
          <ClockIcon />
          This link is valid for 30 minutes and can be used once.
        </p>

        <Button
          type="submit"
          variant="primary"
          loading={loading}
          disabled={loading || !criteria.isValid}
          style={{ width: '100%' }}
        >
          Reset password →
        </Button>
      </form>
    </div>
  );
}
