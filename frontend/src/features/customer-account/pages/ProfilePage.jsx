import { useEffect, useState } from 'react';
import { CustomerPage } from '../../../shared/layouts/customer/CustomerPage';
import { useToast } from '../../../shared/components/feedback/useToast';
import { getProfile, updateProfile } from '../services/customerAccountApi';
import { StatusBadge } from './components/StatusBadge';
import styles from './customerAccount.module.css';

/**
 * Validates Sri Lankan phone number format matching SriLankanPhoneUtil:
 * Accepts +94XXXXXXXXX, 0XXXXXXXXX, or 94XXXXXXXXX (9 digits after prefix)
 */
function validateSriLankanPhone(phone = '') {
  if (!phone || !phone.trim()) {
    return 'Phone number is required';
  }
  const cleaned = phone.trim().replace(/[\s\-()]/g, '');
  const pattern = /^(\+94|0|94)[0-9]{9}$/;
  if (!pattern.test(cleaned)) {
    return 'Invalid Sri Lankan phone number format. Expected +94XXXXXXXXX or 07XXXXXXXX';
  }
  return null;
}

export function ProfilePage() {
  const toast = useToast();
  const [profile, setProfile] = useState(null);
  const [fullName, setFullName] = useState('');
  const [phone, setPhone] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [phoneError, setPhoneError] = useState('');
  const [nameError, setNameError] = useState('');

  useEffect(() => {
    let mounted = true;
    getProfile()
      .then((data) => {
        if (mounted) {
          setProfile(data);
          setFullName(data.fullName || '');
          setPhone(data.phone || '');
          setLoading(false);
        }
      })
      .catch((err) => {
        if (mounted) {
          setErrorMessage(err.message || 'Failed to load profile');
          setLoading(false);
        }
      });

    return () => {
      mounted = false;
    };
  }, []);

  const handleReset = () => {
    if (profile) {
      setFullName(profile.fullName || '');
      setPhone(profile.phone || '');
    }
    setNameError('');
    setPhoneError('');
    setErrorMessage('');
  };

  const handleSave = async (e) => {
    e.preventDefault();
    setErrorMessage('');
    setNameError('');
    setPhoneError('');

    const trimmedName = fullName.trim();
    if (!trimmedName) {
      setNameError('Full name is required');
      return;
    }
    if (trimmedName.length > 100) {
      setNameError('Full name must be 100 characters or fewer');
      return;
    }

    const phoneValidation = validateSriLankanPhone(phone);
    if (phoneValidation) {
      setPhoneError(phoneValidation);
      return;
    }

    setSaving(true);
    try {
      const updated = await updateProfile({
        fullName: trimmedName,
        phone: phone.trim(),
      });
      setProfile(updated);
      setFullName(updated.name || '');
      setPhone(updated.phone || '');
      toast.success('Profile updated successfully');
    } catch (err) {
      setErrorMessage(err.message || 'Failed to update profile');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <CustomerPage title="Profile">
        <div className={styles.card} style={{ minHeight: '200px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <p className={styles.inputHint}>Loading profile details...</p>
        </div>
      </CustomerPage>
    );
  }

  const isEmailVerified = Boolean(profile?.emailVerified);

  return (
    <CustomerPage title="Profile">
      <div className={styles.accountContainer}>
        <div>
          <p className={styles.pageHeaderSubtitle} style={{ marginTop: '-12px', marginBottom: '20px' }}>
            Manage your personal details.
          </p>
        </div>

        {errorMessage && (
          <div className={styles.alertWarning} role="alert" style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#dc2626' }}>
            {errorMessage}
          </div>
        )}

        <div className={styles.card}>
          <div className={styles.cardHeader}>
            <h2 className={styles.cardTitle}>Personal details</h2>
            <span className={styles.cardHeaderTag}>Profile Settings</span>
          </div>

          <form onSubmit={handleSave}>
            <div className={styles.formGroup}>
              <label htmlFor="profile-fullName" className={styles.label}>
                Full name
              </label>
              <input
                id="profile-fullName"
                type="text"
                className={styles.input}
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                disabled={saving}
              />
              {nameError && <span className={styles.inputError}>{nameError}</span>}
            </div>

            <div className={styles.formGroup}>
              <label htmlFor="profile-email" className={styles.label}>
                Email address
              </label>
              <div className={styles.inputWrapper}>
                <input
                  id="profile-email"
                  type="email"
                  className={`${styles.input} ${styles.inputReadOnly}`}
                  value={profile?.email || ''}
                  readOnly
                  disabled
                />
                {isEmailVerified && (
                  <div className={styles.inputBadgeRight}>
                    <StatusBadge variant="success">
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                        <rect width="18" height="11" x="3" y="11" rx="2" ry="2" />
                        <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                      </svg>
                      VERIFIED
                    </StatusBadge>
                  </div>
                )}
              </div>
              <span className={styles.inputHint}>Email cannot be changed</span>
            </div>

            <div className={styles.formGroup}>
              <label htmlFor="profile-phone" className={styles.label}>
                Phone number
              </label>
              <input
                id="profile-phone"
                type="text"
                className={styles.input}
                placeholder="+94 77 123 4567"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                disabled={saving}
              />
              {phoneError && <span className={styles.inputError}>{phoneError}</span>}
            </div>

            <div className={styles.formActions}>
              <button
                type="submit"
                className={styles.btnPrimary}
                disabled={saving}
              >
                {saving ? 'Saving...' : 'Save changes'}
              </button>
              <button
                type="button"
                className={styles.btnSecondary}
                onClick={handleReset}
                disabled={saving}
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

export default ProfilePage;
