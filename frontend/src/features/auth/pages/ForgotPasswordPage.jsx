import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';

import Button from '../../../shared/components/forms/Button';
import Input from '../../../shared/components/forms/Input';
import Alert from '../../../shared/components/feedback/Alert';
import * as authApi from '../api/authApi';
import { validateEmail } from '../utils/validators';

function SuccessIcon() {
  return (
    <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 6L9 17l-5-5" />
    </svg>
  );
}

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [emailError, setEmailError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [serverError, setServerError] = useState(null);

  const handleSubmit = useCallback(
    async (e) => {
      e.preventDefault();
      setServerError(null);
      setEmailError(null);

      const err = validateEmail(email);
      if (err) {
        setEmailError(err);
        return;
      }

      setLoading(true);
      try {
        await authApi.forgotPassword(email.trim());
        setSubmitted(true);
      } catch {
        // Always show success (backend sends 200 regardless for security)
        setSubmitted(true);
      } finally {
        setLoading(false);
      }
    },
    [email]
  );

  if (submitted) {
    return (
      <div className="auth-card auth-card--narrow">
        <div className="auth-card__icon auth-card__icon--green">
          <SuccessIcon />
        </div>
        <h1 className="auth-card__title">Check your email</h1>
        <p className="auth-card__description">
          If this email is registered, we have sent a reset link.
        </p>
        <Link to="/login" className="auth-link auth-link--center" style={{ fontWeight: 600 }}>
          Back to Login
        </Link>
      </div>
    );
  }

  return (
    <div className="auth-card">
      <h1 className="auth-card__title">Forgot your password?</h1>
      <p className="auth-card__description">
        Enter your registered email and we will send you a reset link.
      </p>

      {serverError && (
        <Alert variant="error" title={serverError} onClose={() => setServerError(null)} />
      )}

      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        <Input
          label="Email"
          type="email"
          placeholder="e.g. kasun@example.lk"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={emailError}
          autoComplete="email"
          disabled={loading}
        />

        <Button
          type="submit"
          variant="primary"
          loading={loading}
          disabled={loading}
          style={{ width: '100%' }}
        >
          Send reset link
        </Button>
      </form>

      <div style={{ textAlign: 'center', marginTop: '16px' }}>
        <Link to="/login" className="auth-link auth-link--center">
          ← Back to Login
        </Link>
      </div>
    </div>
  );
}
