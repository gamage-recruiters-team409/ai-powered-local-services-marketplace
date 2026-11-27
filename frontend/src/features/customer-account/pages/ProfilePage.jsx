import { useEffect, useRef, useState } from 'react';
import { CustomerPage } from '../../../shared/layouts/customer/CustomerPage';
import { useAuth } from '../../auth/hooks/useAuth';
import { useToast } from '../../../shared/components/feedback/useToast';
import {
  getProfile,
  removeProfilePhoto,
  updateProfile,
  uploadProfilePhoto,
} from '../services/customerAccountApi';
import { StatusBadge } from '../components/StatusBadge';
import { RecentBookings } from '../components/RecentBookings';
import styles from '../customerAccount.module.css';

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

function getInitials(name = '') {
  if (!name || !name.trim()) return 'U';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) {
    return parts[0].charAt(0).toUpperCase();
  }
  return (parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
}

export function ProfilePage() {
  const { refreshUser, updateCurrentUser } = useAuth();
  const toast = useToast();
  const [profile, setProfile] = useState(null);
  const [fullName, setFullName] = useState('');
  const [phone, setPhone] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [phoneError, setPhoneError] = useState('');
  const [nameError, setNameError] = useState('');
  const [photoPreview, setPhotoPreview] = useState(null);
  const [photoLoading, setPhotoLoading] = useState(false);
  const [photoError, setPhotoError] = useState('');
  const fileInputRef = useRef(null);
  const previewUrlRef = useRef(null);

  useEffect(() => {
    return () => {
      if (previewUrlRef.current) {
        URL.revokeObjectURL(previewUrlRef.current);
      }
    };
  }, []);

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
      setFullName(updated.fullName || '');
      setPhone(updated.phone || '');
      const refreshed = await refreshUser();
      if (!refreshed && typeof updateCurrentUser === 'function') {
        try {
          updateCurrentUser({ name: updated.fullName, phone: updated.phone });
        } catch {
          // Fallback failure must not throw
        }
      }
      toast.success('Profile updated successfully');
    } catch (err) {
      setErrorMessage(err.message || 'Failed to update profile');
    } finally {
      setSaving(false);
    }
  };

  const handleFileChange = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;

    setPhotoError('');

    const allowedTypes = ['image/jpeg', 'image/png', 'image/jpg'];
    if (!allowedTypes.includes(file.type?.toLowerCase())) {
      setPhotoError('Please select a JPEG or PNG image');
      return;
    }

    if (file.size > 5 * 1024 * 1024) {
      setPhotoError('Image size must not exceed 5 MB');
      return;
    }

    if (previewUrlRef.current) {
      URL.revokeObjectURL(previewUrlRef.current);
    }
    const preview = URL.createObjectURL(file);
    previewUrlRef.current = preview;
    setPhotoPreview(preview);
    setPhotoLoading(true);

    try {
      const updated = await uploadProfilePhoto(file);
      if (previewUrlRef.current) {
        URL.revokeObjectURL(previewUrlRef.current);
        previewUrlRef.current = null;
      }
      setPhotoPreview(null);
      setProfile((prev) => (prev ? { ...prev, profileImageUrl: updated.profileImageUrl } : updated));
      const refreshed = await refreshUser();
      if (!refreshed && typeof updateCurrentUser === 'function') {
        try {
          updateCurrentUser({ profileImageUrl: updated.profileImageUrl ?? null });
        } catch {
          // Fallback failure must not throw
        }
      }
      toast.success('Profile photo updated successfully');
    } catch (err) {
      if (previewUrlRef.current) {
        URL.revokeObjectURL(previewUrlRef.current);
        previewUrlRef.current = null;
      }
      setPhotoPreview(null);
      setPhotoError(err.message || 'Failed to upload profile photo');
    } finally {
      setPhotoLoading(false);
    }
  };

  const handleRemovePhoto = async () => {
    setPhotoError('');
    setPhotoLoading(true);
    try {
      await removeProfilePhoto();
      if (previewUrlRef.current) {
        URL.revokeObjectURL(previewUrlRef.current);
        previewUrlRef.current = null;
      }
      setPhotoPreview(null);
      setProfile((prev) => (prev ? { ...prev, profileImageUrl: null } : prev));
      const refreshed = await refreshUser();
      if (!refreshed && typeof updateCurrentUser === 'function') {
        try {
          updateCurrentUser({ profileImageUrl: null });
        } catch {
          // Fallback failure must not throw
        }
      }
      toast.success('Profile photo removed');
    } catch (err) {
      setPhotoError(err.message || 'Failed to remove profile photo');
    } finally {
      setPhotoLoading(false);
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
  const displayPhoto = photoPreview || profile?.profileImageUrl;

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

          <div className={styles.avatarSection}>
            <div className={styles.avatarCircle}>
              {displayPhoto ? (
                <img src={displayPhoto} alt="Profile photo" className={styles.avatarImage} />
              ) : (
                <span className={styles.avatarInitials} aria-label="Initials">
                  {getInitials(profile?.fullName || fullName)}
                </span>
              )}
            </div>
            <div className={styles.avatarActions}>
              <div className={styles.avatarButtons}>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/jpeg,image/png"
                  style={{ display: 'none' }}
                  onChange={handleFileChange}
                  data-testid="file-input-photo"
                />
                <button
                  type="button"
                  className={styles.btnSecondary}
                  onClick={() => fileInputRef.current?.click()}
                  disabled={photoLoading || saving}
                >
                  {photoLoading ? 'Uploading...' : 'Change photo'}
                </button>
                {displayPhoto && (
                  <button
                    type="button"
                    className={styles.btnSecondary}
                    onClick={handleRemovePhoto}
                    disabled={photoLoading || saving}
                    style={{ color: '#dc2626', borderColor: '#fecaca' }}
                  >
                    Remove photo
                  </button>
                )}
              </div>
              <p className={styles.photoHelpText}>JPG or PNG, max 5 MB</p>
              {photoError && <span className={styles.photoError}>{photoError}</span>}
            </div>
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

        <RecentBookings />
      </div>
    </CustomerPage>
  );
}

export default ProfilePage;
