import { useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import Button from '../../../shared/components/forms/Button';
import PasswordInput from '../../../shared/components/forms/PasswordInput';
import Alert from '../../../shared/components/feedback/Alert';
import PasswordRules from '../components/PasswordRules';
import { useAuth } from '../hooks/useAuth';
import { getErrorMessage } from '../utils/errorMessages';
import { validatePassword } from '../utils/validators';
import { getRoleHomePath } from '../utils/roleRedirect';
import * as authApi from '../api/authApi';

export default function ForceChangePasswordPage() {
  const navigate = useNavigate();
  const { user, refreshUser } = useAuth();

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = useCallback(
    async (e) => {
      e.preventDefault();
      setServerError(null);
      const newErrors = {};

      if (!currentPassword) {
        newErrors.currentPassword = 'Please enter your current password.';
      }

      const pwErr = validatePassword(newPassword);
      if (pwErr) {
        newErrors.newPassword = pwErr;
      }

      if (newPassword && confirmPassword !== newPassword) {
        newErrors.confirmPassword = 'Passwords do not match.';
      }

      setErrors(newErrors);
      if (Object.keys(newErrors).length > 0) return;

      setLoading(true);
      try {
        await authApi.changePassword(currentPassword, newPassword);
        const updatedUser = await refreshUser();
        const redirectTo = getRoleHomePath(updatedUser?.role || user?.role);
        navigate(redirectTo, { replace: true });
      } catch (err) {
        const code = err.code || '';
        setServerError(getErrorMessage(code, err.message));
      } finally {
        setLoading(false);
      }
    },
    [currentPassword, newPassword, confirmPassword, refreshUser, user, navigate]
  );

  return (
    <div className="auth-card">
      <h1 className="auth-card__title">Change your password</h1>
      <p className="auth-card__description">
        You are required to change your password before continuing.
      </p>

      {serverError && (
        <Alert variant="error" title={serverError} onClose={() => setServerError(null)} />
      )}

      <form className="auth-form" onSubmit={handleSubmit} noValidate>
        <PasswordInput
          label="Current password"
          placeholder="Enter current password"
          value={currentPassword}
          onChange={(e) => setCurrentPassword(e.target.value)}
          error={errors.currentPassword}
          autoComplete="current-password"
          disabled={loading}
        />

        <PasswordInput
          label="New password"
          placeholder="Enter new password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          error={errors.newPassword}
          autoComplete="new-password"
          disabled={loading}
        />

        {newPassword.length > 0 && <PasswordRules password={newPassword} />}

        <PasswordInput
          label="Confirm new password"
          placeholder="Re-enter new password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          error={errors.confirmPassword}
          autoComplete="new-password"
          disabled={loading}
        />

        <Button
          type="submit"
          variant="primary"
          loading={loading}
          disabled={loading}
          style={{ width: '100%' }}
        >
          Update password
        </Button>
      </form>
    </div>
  );
}
