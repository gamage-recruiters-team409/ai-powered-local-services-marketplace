import { useCallback, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import Button from '../../../shared/components/forms/Button';
import Input from '../../../shared/components/forms/Input';
import PasswordInput from '../../../shared/components/forms/PasswordInput';
import Checkbox from '../../../shared/components/forms/Checkbox';
import Alert from '../../../shared/components/feedback/Alert';
import PasswordRules from '../components/PasswordRules';
import { useAuth } from '../hooks/useAuth';
import { getErrorMessage } from '../utils/errorMessages';
import {
  validateEmail,
  validatePhone,
  validatePassword,
  formatPhoneDigits,
  normalizePhoneForSubmission,
} from '../utils/validators';

const POLICY_VERSION = '1.0';

export default function RegisterPage() {
  const navigate = useNavigate();
  const { register } = useAuth();

  const [role, setRole] = useState('CUSTOMER');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [acceptedTerms, setAcceptedTerms] = useState(false);

  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState(null);
  const [loading, setLoading] = useState(false);

  const validate = useCallback(() => {
    const newErrors = {};

    if (!name.trim()) {
      newErrors.name = 'Please enter your full name.';
    }

    const emailErr = validateEmail(email);
    if (emailErr) newErrors.email = emailErr;

    const phoneErr = validatePhone(phone);
    if (phoneErr) newErrors.phone = phoneErr;

    const passwordErr = validatePassword(password);
    if (passwordErr) newErrors.password = passwordErr;

    if (!acceptedTerms) {
      newErrors.acceptedTerms = 'You must agree to the Terms of Service and Privacy Policy.';
    }

    return newErrors;
  }, [name, email, phone, password, acceptedTerms]);

  const handleSubmit = useCallback(
    async (e) => {
      e.preventDefault();
      setServerError(null);

      const validationErrors = validate();
      setErrors(validationErrors);

      if (Object.keys(validationErrors).length > 0) {
        return;
      }

      setLoading(true);
      try {
        await register({
          name: name.trim(),
          email: email.trim(),
          phone: normalizePhoneForSubmission(phone),
          password,
          role,
          acceptedTerms: true,
          policyVersion: POLICY_VERSION,
        });

        navigate('/check-email', { state: { email: email.trim() }, replace: true });
      } catch (err) {
        const code = err.code || '';

        // Map field-level errors from backend
        if (err.fieldErrors && typeof err.fieldErrors === 'object') {
          const fieldMap = {};
          for (const [field, msg] of Object.entries(err.fieldErrors)) {
            fieldMap[field] = msg;
          }
          setErrors((prev) => ({ ...prev, ...fieldMap }));
        }

        setServerError({
          code,
          message: getErrorMessage(code, err.message),
        });
      } finally {
        setLoading(false);
      }
    },
    [validate, register, name, email, phone, password, role, navigate]
  );

  const handlePhoneChange = useCallback((e) => {
    setPhone(formatPhoneDigits(e.target.value));
  }, []);

  return (
    <div className="auth-card auth-card--wide">
      <h1 className="auth-card__title">Create your account</h1>
      <p className="auth-card__description" style={{ marginBottom: '16px' }}>
        Local Services Marketplace (Sri Lanka)
      </p>

      {serverError && (
        <Alert variant="error" title={serverError.message} onClose={() => setServerError(null)} />
      )}

      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        {/* Role selector */}
        <div>
          <p className="auth-role-selector__label">Account type</p>
          <div className="auth-role-selector">
            <button
              type="button"
              className={`auth-role-card${role === 'CUSTOMER' ? ' auth-role-card--selected' : ''}`}
              onClick={() => setRole('CUSTOMER')}
              aria-pressed={role === 'CUSTOMER'}
            >
              <div className="auth-role-card__info">
                <h4>Customer</h4>
                <p>I want to book services</p>
              </div>
              <span className="auth-role-card__radio" aria-hidden="true" />
            </button>
            <button
              type="button"
              className={`auth-role-card${role === 'PROVIDER' ? ' auth-role-card--selected' : ''}`}
              onClick={() => setRole('PROVIDER')}
              aria-pressed={role === 'PROVIDER'}
            >
              <div className="auth-role-card__info">
                <h4>Provider</h4>
                <p>I want to offer services</p>
              </div>
              <span className="auth-role-card__radio" aria-hidden="true" />
            </button>
          </div>
        </div>

        {/* Name + Email row */}
        <div className="auth-form__row">
          <Input
            label="Full name"
            placeholder="Enter your full name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            error={errors.name}
            autoComplete="name"
            disabled={loading}
          />
          <Input
            label="Email"
            type="email"
            placeholder="name@example.lk"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            error={errors.email}
            autoComplete="email"
            disabled={loading}
          />
        </div>

        {/* Phone + Password row */}
        <div className="auth-form__row">
          <div>
            <label className="shared-field__label">Phone number</label>
            <div className="auth-phone-group">
              <span className="auth-phone-prefix">+94</span>
              <Input
                placeholder="77 123 4567"
                value={phone}
                onChange={handlePhoneChange}
                error={errors.phone}
                autoComplete="tel-national"
                disabled={loading}
              />
            </div>
          </div>
          <div>
            <PasswordInput
              label="Password"
              placeholder="Create a password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              error={errors.password}
              autoComplete="new-password"
              disabled={loading}
            />
            {password.length > 0 && <PasswordRules password={password} />}
          </div>
        </div>

        {/* Terms */}
        <Checkbox
          label={
            <>
              I agree to the{' '}
              <a href="/terms" target="_blank" rel="noopener noreferrer" style={{ color: '#0f766e' }}>
                Terms of Service
              </a>{' '}
              and{' '}
              <a href="/privacy" target="_blank" rel="noopener noreferrer" style={{ color: '#0f766e' }}>
                Privacy Policy
              </a>
            </>
          }
          checked={acceptedTerms}
          onChange={(e) => setAcceptedTerms(e.target.checked)}
          error={errors.acceptedTerms}
          disabled={loading}
        />

        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button
            type="submit"
            variant="primary"
            loading={loading}
            disabled={loading}
          >
            Create account →
          </Button>
        </div>
      </form>

      <p className="auth-form__footer">
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </div>
  );
}
