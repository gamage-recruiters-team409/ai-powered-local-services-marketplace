import { useMemo, useState } from 'react'
import { Button, Checkbox, Input, Modal, Radio } from '../../../shared/components/forms'
import { InlineMessage, useToast } from '../../../shared/components/feedback'
import {
  pauseMyProviderProfile,
  reactivateMyProviderProfile,
  updateMyProviderPayoutDetails,
  updateProviderProfile,
} from '../services/providerProfileApi'
import ProviderFeatureIcon from './ProviderFeatureIcon'
import styles from '../providerManagement.module.css'

const EMPTY_PAYOUT = { bankName: '', accountHolderName: '', accountNumber: '' }

function Footer({ loading, cancel, label = 'Save changes', disabled = false }) {
  return <div className={styles.footer}>
    <Button variant="outline" onClick={cancel} disabled={loading}>Cancel</Button>
    <Button type="submit" loading={loading} disabled={loading || disabled}>{label}</Button>
  </div>
}

function ServicesEditor({ profile, categories, provinces, loading, onSave, onCancel, error }) {
  const [categoryIds, setCategoryIds] = useState(() => [...(profile.categoryIds ?? [])])
  const [districts, setDistricts] = useState(() => [...(profile.serviceAreaDistricts ?? [])])
  const [attemptedSave, setAttemptedSave] = useState(false)
  const availableDistricts = useMemo(() => new Set(provinces.flatMap((province) => province.districts ?? [])), [provinces])
  const invalidDistricts = districts.filter((district) => !availableDistricts.has(district))
  const categorySet = new Set(categoryIds)
  const districtSet = new Set(districts)

  function toggle(current, setCurrent, value, checked) {
    setCurrent(checked ? [...new Set([...current, value])] : current.filter((item) => item !== value))
  }

  return <form className={styles.form} onSubmit={(event) => {
    event.preventDefault()
    setAttemptedSave(true)
    if (districts.length > 0 && !invalidDistricts.length) {
      onSave({ categoryIds, serviceAreaDistricts: districts })
    }
  }}>
    <div className={styles.fieldGroup}>
      <h3>Service categories</h3>
      <p>Select the categories that best represent your services.</p>
      <details className={styles.dropdown}>
        <summary>{categoryIds.length ? `${categoryIds.length} categories selected` : 'Select service categories'}</summary>
        <div className={styles.optionGrid}>
          {categories.map((category) => <Checkbox key={category.id} label={category.name} checked={categorySet.has(category.id)} disabled={loading} onChange={(event) => toggle(categoryIds, setCategoryIds, category.id, event.target.checked)} />)}
          {!categories.length && <p>No categories available. Try again later.</p>}
        </div>
      </details>
      {!!categoryIds.length && <div className={styles.chips}>{categoryIds.map((id) => {
        const name = categories.find((category) => category.id === id)?.name ?? id
        return <button key={id} type="button" className={styles.chip} disabled={loading} onClick={() => setCategoryIds(categoryIds.filter((item) => item !== id))}>{name} ×</button>
      })}</div>}
    </div>
    <div className={styles.fieldGroup}>
      <h3>Service-area districts</h3>
      <p>Select the districts where customers can request your services.</p>
      <details className={styles.dropdown}>
        <summary>{districts.length ? `${districts.length} districts selected` : 'Select service areas'}</summary>
        <div className={styles.provinces}>
          {provinces.map((province) => <section key={province.name}>
            <h4>{province.name}</h4>
            <div className={styles.optionGrid}>{(province.districts ?? []).map((district) => <Checkbox key={district} label={district} checked={districtSet.has(district)} disabled={loading} onChange={(event) => toggle(districts, setDistricts, district, event.target.checked)} />)}</div>
          </section>)}
        </div>
      </details>
      {attemptedSave && districts.length === 0 && <InlineMessage variant="error">Select at least one service-area district to save your services.</InlineMessage>}
      {invalidDistricts.length > 0 && <InlineMessage variant="error">Previously saved service areas need to be updated before saving: {invalidDistricts.join(', ')}.</InlineMessage>}
    </div>
    {error && <InlineMessage variant="error">{error}</InlineMessage>}
    <Footer cancel={onCancel} loading={loading} disabled={!categories.length || !provinces.length || !!invalidDistricts.length} />
  </form>
}

function PayoutEditor({ payout, loading, error, onCancel, onSave }) {
  const [form, setForm] = useState(() => ({
    ...EMPTY_PAYOUT,
    bankName: payout?.bankName ?? '',
    accountHolderName: payout?.accountHolderName ?? '',
  }))
  const change = (key, value) => setForm((current) => ({ ...current, [key]: value }))
  const accountNumber = form.accountNumber.replace(/[\s-]/g, '')
  const accountValid = /^\d{6,34}$/.test(accountNumber)
  return <form className={styles.form} onSubmit={(event) => {
    event.preventDefault()
    if (accountValid) onSave({ ...form, bankName: form.bankName.trim(), accountHolderName: form.accountHolderName.trim() })
  }}>
    <h3>Bank account details</h3>
    <p className={styles.helper}>Enter the bank account to receive your payouts.</p>
    {payout?.configured && <div className={styles.info}>Current account: <strong>{payout.maskedAccountNumber}</strong>. For security, enter the full account number when saving any changes.</div>}
    <Input label="Bank name" required maxLength={100} value={form.bankName} onChange={(e) => change('bankName', e.target.value)} disabled={loading} placeholder="e.g. Commercial Bank" />
    <Input label="Account holder name" required maxLength={100} value={form.accountHolderName} onChange={(e) => change('accountHolderName', e.target.value)} disabled={loading} />
    <Input label="Account number" required value={form.accountNumber} onChange={(e) => change('accountNumber', e.target.value)} disabled={loading} inputMode="numeric" autoComplete="off" helperText="6–34 digits; spaces and hyphens are accepted. Account number is never displayed in full after saving." />
    {form.accountNumber && !accountValid && <p className={styles.error} role="alert">Enter a valid bank account number containing 6–34 digits.</p>}
    {error && <InlineMessage variant="error">{error}</InlineMessage>}
    <Footer cancel={onCancel} loading={loading} disabled={!form.bankName.trim() || !form.accountHolderName.trim() || !accountValid} />
  </form>
}

function ActivityEditor({ activity, verification, loading, error, onCancel, onProceed }) {
  const current = activity?.activityStatus || 'ACTIVE'
  const [next, setNext] = useState(current === 'PAUSED' ? 'ACTIVE' : 'PAUSED')
  const eligible = verification?.status === 'APPROVED'
  return <div className={styles.form}>
    <div className={styles.currentStatus}>Current status <span className={current === 'PAUSED' ? styles.paused : styles.active}>{current}</span></div>
    <div className={styles.activityOptions}>
      <Radio name="provider-next-activity" value="PAUSED" label="Pause profile" helperText="Temporarily stop receiving new booking requests. Existing confirmed bookings continue." checked={next === 'PAUSED'} disabled={loading} onChange={() => setNext('PAUSED')} />
      <Radio name="provider-next-activity" value="ACTIVE" label="Reactivate profile" helperText="Allow new booking requests again when your verification is approved." checked={next === 'ACTIVE'} disabled={loading} onChange={() => setNext('ACTIVE')} />
    </div>
    <div className={styles.info}>Existing confirmed bookings remain unaffected by voluntary pause or reactivation.</div>
    {!eligible && <InlineMessage variant="warning">Your verification must be approved before you can change your profile activity.</InlineMessage>}
    {error && <InlineMessage variant="error">{error}</InlineMessage>}
    <div className={styles.footer}>
      <Button variant="outline" onClick={onCancel} disabled={loading}>Cancel</Button>
      <Button onClick={() => onProceed(next)} disabled={loading || !eligible || next === current}>Continue</Button>
    </div>
  </div>
}

/** UI flows are based only on existing WP02 API contracts. No other feature is modified. */
export default function ProviderManagementDialogs({ open, setOpen, profile, activity, payout, verification, categories, provinces, onProfileUpdated, onActivityUpdated, onPayoutUpdated }) {
  const toast = useToast()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [confirm, setConfirm] = useState(null)
  const close = () => { if (!loading) { setOpen(null); setConfirm(null); setError('') } }

  async function saveServices(payload) {
    setLoading(true); setError('')
    try {
      const updatedProfile = await updateProviderProfile(payload)
      // Wait for the parent to refresh verification and activity eligibility.
      await onProfileUpdated(updatedProfile)
      toast.success('Service information updated.')
      closeAfterSuccess()
    }
    catch (err) { setError(err.message || 'Unable to update services.') }
    finally { setLoading(false) }
  }
  async function savePayout(payload) {
    setLoading(true); setError('')
    try { onPayoutUpdated(await updateMyProviderPayoutDetails(payload)); toast.success('Payout details updated securely.'); closeAfterSuccess() }
    catch (err) { setError(err.message || 'Unable to update payout details.') }
    finally { setLoading(false) }
  }
  async function changeActivity() {
    if (!confirm) return
    setLoading(true); setError('')
    try {
      const updated = confirm === 'PAUSED' ? await pauseMyProviderProfile() : await reactivateMyProviderProfile()
      onActivityUpdated(updated)
      toast.success(confirm === 'PAUSED' ? 'Your provider profile is paused.' : 'Your provider profile is active again.')
      closeAfterSuccess()
    } catch (err) { setError(err.message || 'Unable to change your profile status.') }
    finally { setLoading(false) }
  }
  function closeAfterSuccess() { setOpen(null); setConfirm(null); setError('') }

  return <>
    <Modal isOpen={open === 'services'} onClose={close} closeOnBackdrop={!loading} className={styles.modal} title="Manage services">
      {open === 'services' && <ServicesEditor profile={profile} categories={categories} provinces={provinces} loading={loading} error={error} onSave={saveServices} onCancel={close} />}
    </Modal>
    <Modal isOpen={open === 'payout'} onClose={close} closeOnBackdrop={!loading} className={styles.modal} title="Manage payout details">
      {open === 'payout' && <PayoutEditor payout={payout} loading={loading} error={error} onSave={savePayout} onCancel={close} />}
    </Modal>
    <Modal isOpen={open === 'activity' && !confirm} onClose={close} closeOnBackdrop={!loading} className={styles.modal} title="Manage profile status">
      {open === 'activity' && !confirm && <ActivityEditor activity={activity} verification={verification} loading={loading} error={error} onCancel={close} onProceed={(next) => { setError(''); setConfirm(next) }} />}
    </Modal>
    <Modal isOpen={open === 'activity' && Boolean(confirm)} onClose={() => { if (!loading) setConfirm(null) }} closeOnBackdrop={!loading} className={styles.confirmModal} title={confirm === 'PAUSED' ? 'Pause your profile?' : 'Reactivate your profile?'}>
      {confirm && <div className={styles.confirmBody}>
        <span className={confirm === 'PAUSED' ? styles.warningIcon : styles.successIcon}><ProviderFeatureIcon name={confirm === 'PAUSED' ? 'alert' : 'refresh'} size={27} /></span>
        <p>{confirm === 'PAUSED' ? 'Your profile will temporarily stop receiving new booking requests.' : 'Your profile can receive new booking requests again, provided your verification is approved.'}</p>
        <div className={styles.info}>Existing confirmed bookings will continue normally.</div>
        {error && <InlineMessage variant="error">{error}</InlineMessage>}
        <div className={styles.footer}><Button variant="outline" disabled={loading} onClick={() => setConfirm(null)}>Back</Button><Button disabled={loading} loading={loading} onClick={changeActivity}>{confirm === 'PAUSED' ? 'Pause profile' : 'Reactivate profile'}</Button></div>
      </div>}
    </Modal>
  </>
}
