import { useEffect, useState } from 'react';
import { CustomerPage } from '../../../shared/layouts/customer/CustomerPage';
import { useToast } from '../../../shared/components/feedback/useToast';
import ConfirmDialog from '../../../shared/components/feedback/ConfirmDialog';
import {
  getAddresses,
  createAddress,
  updateAddress,
  deleteAddress,
  setDefaultAddress,
  getDistricts,
} from '../services/customerAccountApi';
import { AddressModal } from './components/AddressModal';
import { StatusBadge } from './components/StatusBadge';
import { EmptyState } from './components/EmptyState';
import styles from './customerAccount.module.css';

export function SavedAddressesPage() {
  const toast = useToast();
  const [addresses, setAddresses] = useState([]);
  const [provinces, setProvinces] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingAddress, setEditingAddress] = useState(null);
  const [addressToDelete, setAddressToDelete] = useState(null);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [modalError, setModalError] = useState(null);
  const [pageError, setPageError] = useState(null);
  const [maxAddressesReached, setMaxAddressesReached] = useState(false);

  const loadData = async () => {
    try {
      const [addrData, districtData] = await Promise.all([
        getAddresses(),
        getDistricts(),
      ]);
      setAddresses(addrData || []);
      setProvinces(districtData?.provinces || []);
      setPageError(null);
      if (addrData && addrData.length >= 10) {
        setMaxAddressesReached(true);
      } else {
        setMaxAddressesReached(false);
      }
    } catch (err) {
      setPageError(err.message || 'Failed to load addresses');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleOpenAdd = () => {
    setEditingAddress(null);
    setModalError(null);
    setModalOpen(true);
  };

  const handleOpenEdit = (addr) => {
    setEditingAddress(addr);
    setModalError(null);
    setModalOpen(true);
  };

  const handleSaveAddress = async (formData) => {
    setSaving(true);
    setModalError(null);

    try {
      if (editingAddress?.id) {
        await updateAddress(editingAddress.id, formData);
        toast.success('Address updated successfully');
      } else {
        await createAddress(formData);
        toast.success('Address added successfully');
      }
      setModalOpen(false);
      setEditingAddress(null);
      await loadData();
    } catch (err) {
      if (err.code === 'MAX_ADDRESSES_REACHED') {
        setMaxAddressesReached(true);
        setModalError(err.message || 'Maximum number of saved addresses reached.');
      } else if (err.code === 'CONCURRENT_MODIFICATION') {
        toast.error('Address was modified by another action. Reloading list.');
        setModalOpen(false);
        await loadData();
      } else {
        setModalError(err.message || 'Failed to save address');
      }
    } finally {
      setSaving(false);
    }
  };

  const handleSetDefault = async (addrId) => {
    try {
      await setDefaultAddress(addrId);
      toast.success('Default address updated');
      await loadData();
    } catch (err) {
      if (err.code === 'CONCURRENT_MODIFICATION') {
        toast.error('Unable to set default address due to a concurrent update. Reloading list.');
        await loadData();
      } else {
        toast.error(err.message || 'Failed to set default address');
      }
    }
  };

  const handleConfirmDelete = async () => {
    if (!addressToDelete) return;
    setDeleting(true);
    try {
      await deleteAddress(addressToDelete.id);
      toast.success('Address deleted successfully');
      setAddressToDelete(null);
      await loadData();
    } catch (err) {
      toast.error(err.message || 'Failed to delete address');
    } finally {
      setDeleting(false);
    }
  };

  const actions = (
    <button
      type="button"
      className={styles.btnPrimary}
      onClick={handleOpenAdd}
      disabled={maxAddressesReached}
      title={maxAddressesReached ? 'Maximum address limit reached' : 'Add new address'}
    >
      + Add address
    </button>
  );

  return (
    <CustomerPage title="Saved addresses" actions={actions}>
      <div className={styles.accountContainer}>
        <div>
          <p className={styles.pageHeaderSubtitle} style={{ marginTop: '-12px', marginBottom: '20px' }}>
            Addresses you can use when booking a service.
          </p>
        </div>

        {pageError && (
          <div className={styles.alertWarning} role="alert" style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#dc2626' }}>
            {pageError}
          </div>
        )}

        {maxAddressesReached && (
          <div className={styles.alertWarning} role="status">
            You have reached the maximum allowed number of saved addresses. Please delete an address before adding a new one.
          </div>
        )}

        {loading ? (
          <div className={styles.card} style={{ minHeight: '180px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <p className={styles.inputHint}>Loading saved addresses...</p>
          </div>
        ) : addresses.length === 0 ? (
          <div className={styles.card}>
            <EmptyState
              icon={
                <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z" />
                  <circle cx="12" cy="10" r="3" />
                </svg>
              }
              title="No saved addresses"
              description="Add your home, work, or frequently used addresses to speed up service booking."
              actionLabel="+ Add address"
              onAction={handleOpenAdd}
            />
          </div>
        ) : (
          <div className={styles.addressList}>
            {addresses.map((addr) => (
              <div key={addr.id} className={styles.addressCard}>
                <div className={styles.addressMain}>
                  <div className={styles.addressIconWrapper}>
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8" />
                      <path d="M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    </svg>
                  </div>

                  <div className={styles.addressDetails}>
                    <div className={styles.addressTitleRow}>
                      <h3 className={styles.addressLine}>
                        {addr.label ? `${addr.label}: ` : ''}{addr.addressLine1}
                      </h3>
                      {addr.isDefault && (
                        <StatusBadge variant="default">Default</StatusBadge>
                      )}
                    </div>
                    <p className={styles.addressCity}>
                      {addr.city || addr.district}
                    </p>
                  </div>
                </div>

                <div className={styles.addressActions}>
                  {!addr.isDefault && (
                    <button
                      type="button"
                      className={styles.setDefaultBtn}
                      onClick={() => handleSetDefault(addr.id)}
                    >
                      Set as default
                    </button>
                  )}
                  <button
                    type="button"
                    className={styles.addressActionBtn}
                    onClick={() => handleOpenEdit(addr)}
                    aria-label={`Edit ${addr.label || addr.addressLine1}`}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z" />
                      <path d="m15 5 4 4" />
                    </svg>
                    Edit
                  </button>
                  <button
                    type="button"
                    className={styles.addressActionBtn}
                    style={{ color: '#dc2626' }}
                    onClick={() => setAddressToDelete(addr)}
                    aria-label={`Delete ${addr.label || addr.addressLine1}`}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M3 6h18" />
                      <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6" />
                      <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2" />
                    </svg>
                    Delete
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}

        <AddressModal
          isOpen={modalOpen}
          initialData={editingAddress}
          provinces={provinces}
          onClose={() => {
            setModalOpen(false);
            setEditingAddress(null);
            setModalError(null);
          }}
          onSave={handleSaveAddress}
          saving={saving}
          error={modalError}
        />

        <ConfirmDialog
          open={Boolean(addressToDelete)}
          title="Delete this address?"
          message="This address will be removed from your saved addresses."
          confirmLabel="Delete"
          cancelLabel="Cancel"
          variant="destructive"
          onConfirm={handleConfirmDelete}
          onCancel={() => setAddressToDelete(null)}
          loading={deleting}
        />
      </div>
    </CustomerPage>
  );
}

export default SavedAddressesPage;
