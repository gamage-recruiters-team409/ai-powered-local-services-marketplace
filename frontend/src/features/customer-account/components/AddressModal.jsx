import { useState } from 'react';
import styles from '../customerAccount.module.css';

export function AddressModal({
  isOpen,
  initialData = null,
  provinces = [],
  onClose,
  onSave,
  saving = false,
  error = null,
}) {
  const [label, setLabel] = useState(initialData?.label ?? '');
  const [addressLine1, setAddressLine1] = useState(initialData?.addressLine1 ?? '');
  const [district, setDistrict] = useState(initialData?.district ?? '');
  const [validationError, setValidationError] = useState('');

  if (!isOpen) return null;

  const handleSubmit = (e) => {
    e.preventDefault();
    const trimmedLabel = label.trim();
    const trimmedLine = addressLine1.trim();
    const trimmedDistrict = district.trim();

    if (!trimmedLabel) {
      setValidationError('Label is required (e.g. Home, Work).');
      return;
    }
    if (trimmedLabel.length > 30) {
      setValidationError('Label must be 30 characters or fewer.');
      return;
    }
    if (!trimmedLine) {
      setValidationError('Address line is required.');
      return;
    }
    if (trimmedLine.length > 100) {
      setValidationError('Address line must be 100 characters or fewer.');
      return;
    }
    if (!trimmedDistrict) {
      setValidationError('Please select a City / District.');
      return;
    }

    setValidationError('');
    onSave({
      label: trimmedLabel,
      addressLine1: trimmedLine,
      city: trimmedDistrict,
      district: trimmedDistrict,
    });
  };

  const isEdit = Boolean(initialData?.id);

  return (
    <div className={styles.modalOverlay} onClick={onClose} role="dialog" aria-modal="true" aria-labelledby="address-modal-title">
      <div className={styles.modalContent} onClick={(e) => e.stopPropagation()}>
        <div className={styles.modalHeader}>
          <h2 id="address-modal-title" className={styles.modalTitle}>
            {isEdit ? 'Edit address' : 'Add address'}
          </h2>
          <button
            type="button"
            className={styles.modalCloseBtn}
            onClick={onClose}
            aria-label="Close modal"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
          </button>
        </div>

        <form onSubmit={handleSubmit}>
          {(error || validationError) && (
            <div className={styles.alertWarning} role="alert" style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#dc2626' }}>
              {validationError || error}
            </div>
          )}

          <div className={styles.formGroup}>
            <label htmlFor="address-label" className={styles.label}>
              Label <span style={{ color: '#dc2626' }}>*</span>
            </label>
            <input
              id="address-label"
              type="text"
              maxLength={30}
              className={styles.input}
              placeholder="e.g. Home, Work"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              disabled={saving}
              autoFocus
            />
          </div>

          <div className={styles.formGroup}>
            <label htmlFor="address-line1" className={styles.label}>
              Address line <span style={{ color: '#dc2626' }}>*</span>
            </label>
            <input
              id="address-line1"
              type="text"
              maxLength={100}
              className={styles.input}
              placeholder="e.g. 45/2, Alfred House Gardens"
              value={addressLine1}
              onChange={(e) => setAddressLine1(e.target.value)}
              disabled={saving}
            />
          </div>

          <div className={styles.formGroup}>
            <label htmlFor="address-district" className={styles.label}>
              City / District <span style={{ color: '#dc2626' }}>*</span>
            </label>
            <select
              id="address-district"
              className={styles.input}
              value={district}
              onChange={(e) => setDistrict(e.target.value)}
              disabled={saving}
            >
              <option value="">Select city / district</option>
              {provinces.map((prov) => (
                <optgroup key={prov.name} label={`${prov.name} Province`}>
                  {prov.districts.map((d) => (
                    <option key={d} value={d}>
                      {d}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>

          <div className={styles.formActions} style={{ justifyContent: 'flex-end', marginTop: '24px' }}>
            <button
              type="button"
              className={styles.btnSecondary}
              onClick={onClose}
              disabled={saving}
            >
              Cancel
            </button>
            <button
              type="submit"
              className={styles.btnPrimary}
              disabled={saving}
            >
              {saving ? 'Saving...' : 'Save'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default AddressModal;
