import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CustomerPage } from '../../../shared/layouts/customer/CustomerPage';
import { useAuth } from '../../auth/hooks/useAuth';
import { useToast } from '../../../shared/components/feedback/useToast';
import PasswordRules from '../../auth/components/PasswordRules';
import { checkPasswordCriteria } from '../../auth/utils/validators';
import { changePassword } from '../services/customerAccountApi';
import styles from '../customerAccount.module.css';

export function SettingsPage() {
  const navigate = useNavigate();
  const { logout } = useAuth();
  const toast = useToast();

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});

  const handleReset = () => {
    setCurrentPassword('');
    setNewPassword('');
    setConfirmPassword('');
    setErrorMessage('');
    setFieldErrors({});
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setErrorMessage('');
    setFieldErrors({});

    const errors = {};

    if (!currentPassword) {
      errors.currentPassword = 'Current password is required';
    }

    if (!newPassword) {
      errors.newPassword = 'New password is required';
    } else {
      const bytes = new TextEncoder().encode(newPassword).length;
      const criteria = checkPasswordCriteria(newPassword);
      if (bytes > 72) {
        errors.newPassword = 'Password must not exceed 72 bytes (some characters, such as emoji or symbols, use more than one byte).';
      } else if (!criteria.isValid) {
        errors.newPassword = 'Password must meet all complexity requirements';
      } else if (newPassword === currentPassword) {
        errors.newPassword = 'New password cannot be the same as the current password';
      }
    }

    if (!confirmPassword) {
      errors.confirmPassword = 'Confirm password is required';
    } else if (newPassword && newPassword !== confirmPassword) {
      errors.confirmPassword = 'Passwords do not match';
    }

    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      return;
    }

    setLoading(true);

    try {
      await changePassword({
        currentPassword,
        newPassword,
      });

      // Passwords do not stay in state
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');

      toast.success('Password changed successfully. Please log in with your new password.');
      await logout();
      navigate('/login', {
        state: { message: 'Password changed successfully. Please log in with your new password.' },
      });
    } catch (err) {
      if (err.status === 429) {
        const seconds = err.retryAfterSeconds;
        setErrorMessage(
          seconds
            ? `Too many attempts. Please try again in ${seconds} seconds.`
            : 'Too many attempts. Please try again later.'
        );
      } else if (err.code === 'INVALID_CURRENT_PASSWORD') {
        setFieldErrors({ currentPassword: 'Current password is incorrect' });
      } else if (err.code === 'SAME_PASSWORD') {
        setFieldErrors({ newPassword: 'New password cannot be the same as the current password' });
      } else if (err.code === 'WEAK_PASSWORD') {
        setFieldErrors({ newPassword: err.message || 'Password does not meet complexity requirements' });
      } else {
        setErrorMessage(err.message || 'Failed to update password');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <CustomerPage title="Settings">
      <div className={styles.accountContainer}>
        <div>
          <p className={styles.pageHeaderSubtitle} style={{ marginTop: '-12px', marginBottom: '20px' }}>
            Manage your account security and password.
          </p>
        </div>

        {errorMessage && (
          <div className={styles.alertWarning} role="alert" style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#dc2626' }}>
            {errorMessage}
          </div>
        )}

        <div className={styles.card}>
          <div className={styles.cardHeader}>
            <h2 className={styles.cardTitle}>Change password</h2>
            <span className={styles.cardHeaderTag}>Security</span>
          </div>

          <form onSubmit={handleSubmit}>
            <div className={styles.formGroup}>
              <label htmlFor="settings-currentPassword" className={styles.label}>
                Current password <span style={{ color: '#dc2626' }}>*</span>
              </label>
              <input
                id="settings-currentPassword"
                type="password"
                className={styles.input}
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                disabled={loading}
                autoComplete="current-password"
              />
              {fieldErrors.currentPassword && (
                <span className={styles.inputError}>{fieldErrors.currentPassword}</span>
              )}
            </div>

            <div className={styles.formGroup}>
              <label htmlFor="settings-newPassword" className={styles.label}>
                New password <span style={{ color: '#dc2626' }}>*</span>
              </label>
              <input
                id="settings-newPassword"
                type="password"
                maxLength={72}
                className={styles.input}
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                disabled={loading}
                autoComplete="new-password"
              />
              {fieldErrors.newPassword && (
                <span className={styles.inputError}>{fieldErrors.newPassword}</span>
              )}
            </div>

            {newPassword.length > 0 && (
              <div style={{ marginBottom: '20px' }}>
                <PasswordRules password={newPassword} />
              </div>
            )}

            <div className={styles.formGroup}>
              <label htmlFor="settings-confirmPassword" className={styles.label}>
                Confirm new password <span style={{ color: '#dc2626' }}>*</span>
              </label>
              <input
                id="settings-confirmPassword"
                type="password"
                maxLength={72}
                className={styles.input}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                disabled={loading}
                autoComplete="new-password"
              />
              {fieldErrors.confirmPassword && (
                <span className={styles.inputError}>{fieldErrors.confirmPassword}</span>
              )}
            </div>

            <div className={styles.formActions}>
              <button
                type="submit"
                className={styles.btnPrimary}
                disabled={loading}
              >
                {loading ? 'Updating...' : 'Update password'}
              </button>
              <button
                type="button"
                className={styles.btnSecondary}
                onClick={handleReset}
                disabled={loading}
              >
                Cancel
              </button>
            </div>
          </form>
        </div>
      </div>
    </CustomerPage>
  );
}

export default SettingsPage;
