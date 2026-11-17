import { useCallback, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import Button from '../../../shared/components/forms/Button';
import Input from '../../../shared/components/forms/Input';
import PasswordInput from '../../../shared/components/forms/PasswordInput';
import Alert from '../../../shared/components/feedback/Alert';
import { useAuth } from '../hooks/useAuth';
import { getErrorMessage } from '../utils/errorMessages';
import { validateEmail } from '../utils/validators';
import { resolvePostLoginRedirect } from '../utils/roleRedirect';
import * as authApi from '../api/authApi';

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { login } = useAuth();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [emailError, setEmailError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);

  const handleSubmit = useCallback(
    async (e) => {
      e.preventDefault();
      setError(null);
      setEmailError(null);

      const emailValidation = validateEmail(email);
      if (emailValidation) {
        setEmailError(emailValidation);
        return;
      }

      if (!password) {
        setError({ code: null, message: 'Please enter your password.' });
        return;
      }

      setLoading(true);
      try {
        const result = await login({ email: email.trim(), password });

        if (result.mustChangePassword) {
          navigate('/change-password', { replace: true });
          return;
        }

        const redirectTo = resolvePostLoginRedirect(result.user, location.state?.from);
        navigate(redirectTo, { replace: true });
      } catch (err) {
        const code = err.code || '';
        const retryAfterSeconds = err.retryAfterSeconds;

        setError({
          code,
          message: getErrorMessage(code, err.message),
          retryAfterSeconds: retryAfterSeconds || null,
        });
      } finally {
        setLoading(false);
      }
    },
    [email, password, login, navigate, location.state]
  );

  const handleResendVerification = useCallback(async () => {
    setResending(true);
    try {
      await authApi.resendVerification(email.trim());
      navigate('/check-email', { state: { email: email.trim() }, replace: true });
    } catch (err) {
      // Show error but stay on login
      setError({
        code: err.code,
        message: 'Failed to resend verification email. Please try again.',
      });
    } finally {
      setResending(false);
    }
  }, [email, navigate]);

  const isEmailNotVerified = error?.code === 'EMAIL_NOT_VERIFIED';
  const isAccountLocked = error?.code === 'ACCOUNT_LOCKED';
  const isBadCredentials = error?.code === 'BAD_CREDENTIALS';
  const isRateLimited = error?.code === 'RATE_LIMITED';

  let alertVariant = 'error';
  let alertTitle = error?.message || '';
  let alertChildren = null;

  if (isEmailNotVerified) {
    alertVariant = 'warning';
    alertTitle = 'Your email is not verified yet.';
    alertChildren = (
      <>
        Please verify your email to log in.{' '}
        <button
          type="button"
          className="fb-link-btn"
          onClick={handleResendVerification}
          disabled={resending}
          style={{ fontWeight: 500 }}
        >
          Resend verification email
        </button>
      </>
    );
  } else if (isAccountLocked) {
    alertVariant = 'warning';
    alertTitle = 'Account temporarily locked';
    alertChildren = 'Your account has been temporarily locked for 15 minutes due to multiple failed login attempts.';
  } else if (isBadCredentials) {
    alertTitle = 'Incorrect email or password.';
    alertChildren = 'Please verify your credentials or reset your password below.';
  } else if (isRateLimited) {
    alertTitle = 'Too many requests.';
    alertChildren = error.retryAfterSeconds
      ? `Please try again in ${error.retryAfterSeconds} seconds.`
      : 'Please try again later.';
  }

  return (
    <div className="auth-card">
      <p className="auth-card__subtitle">Local Services Marketplace (Sri Lanka)</p>
      <hr className="auth-card__divider" />
      <h1 className="auth-card__title">Log in</h1>

      {error && (
        <Alert
          variant={alertVariant}
          title={alertTitle}
          onClose={() => setError(null)}
        >
          {alertChildren}
        </Alert>
      )}

      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        <Input
          label="Email"
          type="email"
          placeholder="e.g. kasun@example.lk"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={emailError || (isBadCredentials ? 'Authentication failed' : null)}
          autoComplete="email"
          disabled={loading}
        />

        <PasswordInput
          label="Password"
          placeholder="Enter your password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          disabled={loading}
        />

        <Link to="/forgot-password" className="auth-form__forgot-link">
          Forgot password?
        </Link>

        <div className="auth-form__actions">
          <Button
            type="submit"
            variant="primary"
            loading={loading}
            disabled={loading}
            className="shared-btn--full-width"
            style={{ width: '100%' }}
          >
            Log in →
          </Button>
        </div>

        <div className="auth-form__separator">OR</div>

        <button type="button" className="auth-google-btn" disabled>
          <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
            <path d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844a4.14 4.14 0 01-1.796 2.716v2.259h2.908c1.702-1.567 2.684-3.875 2.684-6.615z" fill="#4285F4"/>
            <path d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332A8.997 8.997 0 009 18z" fill="#34A853"/>
            <path d="M3.964 10.71A5.41 5.41 0 013.682 9c0-.593.102-1.17.282-1.71V4.958H.957A8.996 8.996 0 000 9c0 1.452.348 2.827.957 4.042l3.007-2.332z" fill="#FBBC05"/>
            <path d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0A8.997 8.997 0 00.957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58z" fill="#EA4335"/>
          </svg>
          Continue with Google
        </button>
      </form>

      <p className="auth-form__footer">
        New here? <Link to="/register">Create an account</Link>
      </p>
    </div>
  );
}
