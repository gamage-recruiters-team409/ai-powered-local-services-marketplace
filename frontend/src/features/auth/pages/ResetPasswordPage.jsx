import { useCallback, useEffect, useState } from 'react';
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

function ClockIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
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

function readTokenFromUrl(searchParams) {
  return (
    searchParams.get('token') ||
    (typeof window !== 'undefined' ? new URLSearchParams(window.location.search).get('token') : null)
  );
}

export default function ResetPasswordPage({ previewState } = {}) {
  const [searchParams] = useSearchParams();
  const [token] = useState(() => {
    if (previewState === 'default') return 'preview-token';
    return readTokenFromUrl(searchParams);
  });

  const [password, setPassword] = useState('');
  const [passwordError, setPasswordError] = useState(null);
  const [serverError, setServerError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [pageStatus, setPageStatus] = useState(() => {
    if (previewState === 'expired') return STATUS.EXPIRED;
    if (previewState === 'success') return STATUS.SUCCESS;
    if (previewState === 'default') return STATUS.FORM;
    return token ? STATUS.FORM : STATUS.EXPIRED;
  });

  const criteria = checkPasswordCriteria(password);
  const metCount = criteria.validCount;

  // Strip token from URL immediately (skip in preview mode)
  useEffect(() => {
    if (previewState) return;
    if (typeof window !== 'undefined' && window.location.search.includes('token=')) {
      const cleanUrl = window.location.pathname;
      window.history.replaceState({}, '', cleanUrl);
    }
  }, [previewState]);

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
        await authApi.resetPassword(token, password);
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
    [password, token]
  );

  if (pageStatus === STATUS.SUCCESS) {
    return (
      <div className="auth-card auth-card--narrow">
        <div className="auth-card__icon auth-card__icon--green">
          <SuccessIcon />
        </div>
        <h1 className="auth-card__title">Password updated</h1>
        <p className="auth-card__description">
          You can now log in with your new password.
        </p>
        <Link to="/login" className="shared-btn shared-btn--primary">
          Log in
        </Link>
      </div>
    );
  }

  if (pageStatus === STATUS.EXPIRED) {
    return (
      <div className="auth-card auth-card--narrow">
        <div className="auth-card__icon auth-card__icon--amber">
          <WarningIcon />
        </div>
        <h1 className="auth-card__title">This link has expired or was already used</h1>
        <p className="auth-card__description">
          Password reset links can be used once and expire after 30 minutes.
        </p>
        <Link
          to="/forgot-password"
          className="shared-btn shared-btn--primary"
          style={{ marginBottom: '16px' }}
        >
          Request a new link
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
          <div className="auth-criteria-header">
            <label className="shared-field__label" htmlFor="reset-new-password">
              New password
            </label>
            <span className="auth-criteria-counter">{`${metCount}/4 criteria`}</span>
          </div>
          <PasswordInput
            id="reset-new-password"
            placeholder="Enter new password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={passwordError}
            autoComplete="new-password"
            disabled={loading}
          />
        </div>

        <PasswordRules password={password} />

        <div className="auth-link-validity">
          <ClockIcon />
          <span>This link is valid for 30 minutes and can be used once.</span>
        </div>

        <Button
          type="submit"
          variant="primary"
          loading={loading}
          disabled={loading}
          style={{ width: '100%', marginTop: '6px' }}
        >
          Reset password →
        </Button>
      </form>
    </div>
  );
}
