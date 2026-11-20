import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { CustomerPage } from '../../../shared/layouts/customer/CustomerPage';
import { useAuth } from '../../auth/hooks/useAuth';
import { useToast } from '../../../shared/components/feedback/useToast';
import { deactivateAccount } from '../services/customerAccountApi';
import styles from '../customerAccount.module.css';

export function DeactivatePage() {
  const navigate = useNavigate();
  const { logout } = useAuth();
  const toast = useToast();

  const [modalOpen, setModalOpen] = useState(false);
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [modalError, setModalError] = useState('');

  const handleOpenModal = () => {
    setPassword('');
    setModalError('');
    setErrorMessage('');
    setModalOpen(true);
  };

  const handleCloseModal = () => {
    if (!loading) {
      setModalOpen(false);
      setPassword('');
      setModalError('');
    }
  };

  const handleConfirmDeactivate = async (e) => {
    e.preventDefault();
    setModalError('');
    setErrorMessage('');

    if (!password.trim()) {
      setModalError('Password is required to deactivate your account.');
      return;
    }

    setLoading(true);

    try {
      await deactivateAccount({ password });
      setPassword('');
      setModalOpen(false);

      toast.success('Your account has been deactivated.');
      await logout();
      navigate('/', {
        state: { message: 'Your account has been deactivated.' },
      });
    } catch (err) {
      if (err.status === 409 && err.code === 'OPEN_BOOKINGS_EXIST') {
        setBlocked(true);
        setModalOpen(false);
        setPassword('');
      } else if (err.status === 429) {
        const seconds = err.retryAfterSeconds;
        setModalError(
          seconds
            ? `Too many attempts. Please try again in ${seconds} seconds.`
            : 'Too many attempts. Please try again later.'
        );
      } else if (err.status === 503 || err.code === 'DEACTIVATION_UNAVAILABLE') {
        setModalError('Account deactivation is temporarily unavailable. Please try again later.');
      } else if (err.code === 'INVALID_PASSWORD') {
        setModalError('Password confirmation is incorrect.');
      } else {
        setModalError(err.message || 'Failed to deactivate account.');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <CustomerPage title="Deactivate account">
      <div className={styles.accountContainer}>
        <div>
          <p className={styles.pageHeaderSubtitle} style={{ marginTop: '-12px', marginBottom: '20px' }}>
            Close your LocalServe account.
          </p>
        </div>

        {errorMessage && (
          <div className={styles.alertWarning} role="alert" style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#dc2626' }}>
            {errorMessage}
          </div>
        )}

        <div className={styles.card}>
          <div className={styles.cardHeader}>
            <h2 className={styles.cardTitle}>Deactivate account</h2>
          </div>

          {blocked && (
            <div className={styles.alertWarning} role="alert">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={{ flexShrink: 0 }}>
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
              <div>
                <span>You have open bookings. Please complete or cancel them first.</span>
                <Link to="/customer/bookings" className={styles.alertLink}>
                  Go to My Bookings →
                </Link>
              </div>
            </div>
          )}

          <div className={styles.alertWarning}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={{ flexShrink: 0 }}>
              <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3" />
              <line x1="12" y1="9" x2="12" y2="13" />
              <line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
            <div>
              You will be logged out and will not be able to log in again. Your booking and payment records are kept as required by law. Reactivation is not available.
            </div>
          </div>

          <div className={styles.formActions}>
            <button
              type="button"
              className={styles.btnDanger}
              onClick={handleOpenModal}
              disabled={blocked}
            >
              Deactivate my account
            </button>
            <button
              type="button"
              className={styles.btnSecondary}
              onClick={() => navigate(-1)}
            >
              Cancel
            </button>
          </div>
        </div>

        {modalOpen && (
          <div
            className={styles.modalOverlay}
            onClick={handleCloseModal}
            role="dialog"
            aria-modal="true"
            aria-labelledby="deactivate-modal-title"
          >
            <div
              className={styles.modalContent}
              onClick={(e) => e.stopPropagation()}
              style={{ maxWidth: '420px', textAlign: 'center' }}
            >
              <div
                style={{
                  width: '48px',
                  height: '48px',
                  borderRadius: '50%',
                  background: '#fef2f2',
                  color: '#dc2626',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  margin: '0 auto 16px auto',
                }}
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3" />
                  <line x1="12" y1="9" x2="12" y2="13" />
                  <line x1="12" y1="17" x2="12.01" y2="17" />
                </svg>
              </div>

              <h2 id="deactivate-modal-title" className={styles.modalTitle} style={{ marginBottom: '8px' }}>
                Are you sure you want to deactivate your account?
              </h2>

              <p className={styles.pageHeaderSubtitle} style={{ marginBottom: '20px', lineHeight: 1.5 }}>
                This action will revoke access to current bookings, saved addresses, and provider communications across LocalServe.
              </p>

              {modalError && (
                <div className={styles.alertWarning} role="alert" style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#dc2626', textAlign: 'left' }}>
                  {modalError}
                </div>
              )}

              <form onSubmit={handleConfirmDeactivate}>
                <div className={styles.formGroup} style={{ textAlign: 'left' }}>
                  <label htmlFor="deactivate-password" className={styles.label}>
                    Enter your password to confirm <span style={{ color: '#dc2626' }}>*</span>
                  </label>
                  <input
                    id="deactivate-password"
                    type="password"
                    className={styles.input}
                    placeholder="Account password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    disabled={loading}
                    autoFocus
                    autoComplete="current-password"
                  />
                </div>

                <div className={styles.formActions} style={{ justifyContent: 'center', marginTop: '20px' }}>
                  <button
                    type="button"
                    className={styles.btnSecondary}
                    onClick={handleCloseModal}
                    disabled={loading}
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className={styles.btnDanger}
                    disabled={loading}
                  >
                    {loading ? 'Deactivating...' : 'Yes, deactivate'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}
      </div>
    </CustomerPage>
  );
}

export default DeactivatePage;
