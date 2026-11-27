import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth'
import { ProviderPage } from '../../../shared/layouts/provider'
import {
  Button,
  Checkbox,
  FormStructure,
  Input,
  Modal,
  Radio,
  Select,
  Textarea,
} from '../../../shared/components/forms'
import {
  ConfirmDialog,
  InlineMessage,
  PageError,
  PageLoader,
  useToast,
} from '../../../shared/components/feedback'
import {
  createProviderProfile,
  getMyProviderActivity,
  getMyProviderPayoutDetails,
  getMyProviderProfile,
  getMyProviderVerificationStatus,
  getProviderCategories,
  getProviderDistricts,
  removeProviderProfileImage,
  updateProviderProfile,
  uploadProviderProfileImage,
} from '../services/providerProfileApi'
import styles from '../providerProfile.module.css'
import ProviderManagementDialogs from '../components/ProviderManagementDialogs'
import ProviderFeatureIcon from '../components/ProviderFeatureIcon'
import { formFromProfile, validateProfile, toProfileEditPayload } from '../services/providerProfileForm'

const EMPTY_FORM = {
  providerType: 'INDIVIDUAL',
  displayName: '',
  bio: '',
  district: '',
  city: '',
  experienceYears: '',
  categoryIds: [],
  serviceAreaDistricts: [],
}

const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const MAX_IMAGE_BYTES = 5 * 1024 * 1024

const STATUS_NAMES = {
  NOT_SUBMITTED: 'Not submitted',
  SUBMITTED: 'Submitted',
  UNDER_REVIEW: 'Under review',
  APPROVED: 'Verified',
  REJECTED: 'Rejected',
  RESUBMISSION_REQUESTED: 'Resubmission requested',
  SUSPENDED: 'Suspended',
  EXPIRED: 'Expired',
  ACTIVE: 'Active',
  PAUSED: 'Paused',
}

function flattenCategories(tree) {
  const result = []
  const seen = new Set()
  for (const group of tree) {
    for (const category of [group, ...(group.subcategories ?? [])]) {
      if (!category?.id || !category.active || seen.has(category.id)) continue
      seen.add(category.id)
      result.push({ id: category.id, name: category.name, group: group.name })
    }
  }
  return result
}

function toPayload(form) {
  return {
    providerType: form.providerType,
    displayName: form.displayName.trim(),
    bio: form.bio.trim(),
    district: form.district,
    city: form.city.trim(),
    experienceYears: form.experienceYears === '' ? null : Number(form.experienceYears),
    categoryIds: [...form.categoryIds],
    serviceAreaDistricts: [...form.serviceAreaDistricts],
  }
}

function initials(name) {
  return (name || 'Provider').trim().split(/\s+/).filter(Boolean)
    .slice(0, 2).map((word) => word[0].toUpperCase()).join('') || 'P'
}

function Avatar({ name, url, large = false }) {
  const [brokenUrl, setBrokenUrl] = useState(null)
  return (
    <span className={`${styles.avatar} ${large ? styles.avatarLarge : ''}`}>
      {url && brokenUrl !== url ? (
        <img src={url} alt={`${name || 'Provider'} profile`} onError={() => setBrokenUrl(url)} />
      ) : (
        <span aria-label="Profile initials">{initials(name)}</span>
      )}
    </span>
  )
}

function StatusBadge({ status }) {
  const label = STATUS_NAMES[status] ?? (status || 'Unavailable')
  return (
    <span className={`${styles.statusBadge} ${status === 'APPROVED' || status === 'ACTIVE' ? styles.statusPositive : ''} ${status === 'SUBMITTED' || status === 'UNDER_REVIEW' ? styles.statusNeutral : ''} ${status === 'PAUSED' || status === 'EXPIRED' || status === 'RESUBMISSION_REQUESTED' ? styles.statusWarning : ''} ${status === 'REJECTED' || status === 'SUSPENDED' ? styles.statusDanger : ''}`}>
      {status === 'APPROVED' && <span aria-hidden="true">✓ </span>}{label}
    </span>
  )
}

function ProgressSteps({ current = 1 }) {
  return (
    <div className={styles.progressSteps} aria-label="Provider setup steps">
      <div className={`${styles.progressStep} ${styles.stepCurrent}`}>
        <span className={styles.stepCircle}>{current === 2 ? '✓' : '1'}</span>
        <span>Provider profile</span>
      </div>
      <div className={styles.progressLine} aria-hidden="true" />
      <div className={styles.progressStep}>
        <span className={styles.stepCircleInactive}>2</span>
        <span>Verification documents</span>
      </div>
    </div>
  )
}

export function ProfileFields({ form, errors, districts, provinces, categories, busy, setField, toggleItem, onboarding, includeServices = true }) {
  const categorySet = new Set(form.categoryIds)
  const areaSet = new Set(form.serviceAreaDistricts)
  return (
    <div className={styles.formFields}>
      <fieldset className={styles.providerType} disabled={busy}>
        <legend>Provider type <span className={styles.required}>*</span></legend>
        {onboarding ? (
          <div className={styles.radioRow}>
            <Radio label="Individual" name="providerType" value="INDIVIDUAL" checked={form.providerType === 'INDIVIDUAL'} onChange={(e) => setField('providerType', e.target.value)} />
            <Radio label="Business" name="providerType" value="BUSINESS" checked={form.providerType === 'BUSINESS'} onChange={(e) => setField('providerType', e.target.value)} />
          </div>
        ) : (
          <div>
            <strong>{form.providerType === 'BUSINESS' ? 'Business' : 'Individual'}</strong>
            <p>Provider type cannot be changed after profile creation. Other profile details can still be edited.</p>
          </div>
        )}
        {errors.providerType && <InlineMessage variant="error">{errors.providerType}</InlineMessage>}
      </fieldset>

      <div className={styles.twoColumn}>
        <Input label={form.providerType === 'BUSINESS' ? 'Business display name' : 'Display name'} placeholder="Enter your provider or business display name" required maxLength={100} value={form.displayName} disabled={busy} onChange={(e) => setField('displayName', e.target.value)} error={errors.displayName} helperText="This name will be visible to customers." />
        <Input label="Experience (years)" placeholder="For example, 5" type="number" min="0" max="80" step="1" value={form.experienceYears} disabled={busy} onChange={(e) => setField('experienceYears', e.target.value)} error={errors.experienceYears} helperText={onboarding || form.experienceYears === '' ? 'Enter a whole number from 0 to 80.' : 'Enter a whole number from 0 to 80. An existing experience value cannot be cleared.'} />
      </div>

      <Textarea label="Bio" placeholder="Tell customers a little about your services and experience" required={onboarding} rows={onboarding ? 4 : 3} maxLength={1000} value={form.bio} disabled={busy} onChange={(e) => setField('bio', e.target.value)} error={errors.bio} helperText={`${form.bio.length}/1000 characters. Describe your services and background.`} />

      {includeServices && <div className={styles.twoColumn}>
        <div className={styles.selectionField}>
          <span className={styles.fieldLabel}>Service categories {onboarding && <span className={styles.required}>*</span>}</span>
          <details className={styles.selectionDropdown}>
            <summary>{form.categoryIds.length ? `${form.categoryIds.length} categories selected` : 'Select one or more categories'}</summary>
            <div className={styles.dropdownContent}>
              {categories.length ? categories.map((category) => (
                <Checkbox key={category.id} label={category.name} helperText={category.group !== category.name ? category.group : undefined} checked={categorySet.has(category.id)} disabled={busy} onChange={(e) => toggleItem('categoryIds', category.id, e.target.checked)} />
              )) : <p>No active categories are available.</p>}
            </div>
          </details>
          <small>Choose the categories that best match your services.</small>
          {errors.categoryIds && <span className={styles.fieldError} role="alert">{errors.categoryIds}</span>}
        </div>
        <div className={styles.selectionField}>
          <span className={styles.fieldLabel}>Service area</span>
          <details className={styles.selectionDropdown}>
            <summary>{form.serviceAreaDistricts.length ? `${form.serviceAreaDistricts.length} districts selected` : 'Select service-area districts'}</summary>
            <div className={styles.dropdownContent}>
              {provinces.map((province) => (
                <div className={styles.provinceBlock} key={province.name}>
                  <strong>{province.name}</strong>
                  <div className={styles.provinceOptions}>
                    {(province.districts ?? []).map((district) => (
                      <Checkbox key={district} label={district} checked={areaSet.has(district)} disabled={busy} onChange={(e) => toggleItem('serviceAreaDistricts', district, e.target.checked)} />
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </details>
          <small>Select the districts where you can provide services.</small>
          {errors.serviceAreaDistricts && <span className={styles.fieldError} role="alert">{errors.serviceAreaDistricts}</span>}
        </div>
      </div>

      }

      {onboarding && (
        <div className={styles.twoColumn}>
          <Select label="Primary district" required value={form.district} placeholder="Select your district" options={districts.map((name) => ({ value: name, label: name }))} disabled={busy} onChange={(e) => setField('district', e.target.value)} error={errors.district} helperText="Your main business location." />
          <Input label="City" required placeholder="Enter your city" value={form.city} maxLength={100} disabled={busy} onChange={(e) => setField('city', e.target.value)} error={errors.city} helperText="Used to display your location on your profile." />
        </div>
      )}
    </div>
  )
}

export default function ProviderProfilePage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const photoInputRef = useRef(null)
  const editTopRef = useRef(null)

  const [loading, setLoading] = useState(true)
  const [reloadKey, setReloadKey] = useState(0)
  const [loadError, setLoadError] = useState('')
  const [catalogError, setCatalogError] = useState('')
  const [profile, setProfile] = useState(null)
  const [provinces, setProvinces] = useState([])
  const [categoryTree, setCategoryTree] = useState([])
  const [verification, setVerification] = useState(null)
  const [activity, setActivity] = useState(null)
  const [payout, setPayout] = useState(null)
  const [form, setForm] = useState(() => ({ ...EMPTY_FORM }))
  const [errors, setErrors] = useState({})
  const [saveError, setSaveError] = useState('')
  const [saving, setSaving] = useState(false)
  const [photoBusy, setPhotoBusy] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [confirmPhotoRemoval, setConfirmPhotoRemoval] = useState(false)
  const [manageOpen, setManageOpen] = useState(null)

  const busy = saving || photoBusy
  const authenticatedUserId = user?.id || user?.userId
  const draftKey = authenticatedUserId ? `localserve:provider-profile-draft:${authenticatedUserId}` : null

  useEffect(() => {
    let active = true
    async function load() {
      try {
        const profileData = await getMyProviderProfile().catch((error) => {
          if (error?.status === 404 || error?.response?.status === 404) return null
          throw error
        })
        const results = await Promise.allSettled([
          getProviderDistricts(),
          getProviderCategories(),
          profileData ? getMyProviderVerificationStatus() : Promise.resolve(null),
          profileData ? getMyProviderActivity() : Promise.resolve(null),
          profileData ? getMyProviderPayoutDetails() : Promise.resolve(null),
        ])
        if (!active) return
        const [districtResult, categoryResult, verificationResult, activityResult, payoutResult] = results
        setProfile(profileData)
        setForm(formFromProfile(profileData))
        if (!profileData && draftKey) {
          try {
            const savedDraft = JSON.parse(localStorage.getItem(draftKey) || 'null')
            if (savedDraft && ['INDIVIDUAL', 'BUSINESS'].includes(savedDraft.providerType)) {
              setForm({ ...EMPTY_FORM, ...savedDraft })
            }
          } catch { /* Invalid or unavailable browser storage is ignored. */ }
        }
        setProvinces(districtResult.status === 'fulfilled' ? districtResult.value : [])
        setCategoryTree(categoryResult.status === 'fulfilled' ? categoryResult.value : [])
        setVerification(verificationResult.status === 'fulfilled' ? verificationResult.value : null)
        setActivity(activityResult.status === 'fulfilled' ? activityResult.value : null)
        setPayout(payoutResult.status === 'fulfilled' ? payoutResult.value : null)
        setCatalogError(districtResult.status === 'rejected' || categoryResult.status === 'rejected'
          ? 'Some selection options could not be loaded. Try refreshing before editing.' : '')
        setLoadError('')
      } catch (error) {
        if (active) setLoadError(error.message || 'Unable to load your provider profile.')
      } finally {
        if (active) setLoading(false)
      }
    }
    load()
    return () => { active = false }
  }, [reloadKey, draftKey])

  const districts = useMemo(() => [...new Set(provinces.flatMap((p) => p.districts ?? []))], [provinces])
  const categories = useMemo(() => flattenCategories(categoryTree), [categoryTree])
  const categoryNames = useMemo(() => {
    const byId = new Map(categories.map((category) => [category.id, category.name]))
    return (profile?.categoryIds ?? []).map((id) => byId.get(id) ?? 'Unavailable category')
  }, [categories, profile])

  function setField(field, value) {
    setForm((current) => ({ ...current, [field]: value }))
    setErrors((current) => ({ ...current, [field]: undefined }))
    setSaveError('')
  }

  function toggleItem(field, value, checked) {
    setForm((current) => ({
      ...current,
      [field]: checked ? [...new Set([...current[field], value])] : current[field].filter((item) => item !== value),
    }))
    setErrors((current) => ({ ...current, [field]: undefined }))
    setSaveError('')
  }

  function saveDraft() {
    if (!draftKey) {
      toast.error('Sign in to save a draft.')
      return
    }
    try {
      localStorage.setItem(draftKey, JSON.stringify(form))
      toast.success('Draft saved in this browser.')
    } catch {
      toast.error('Unable to save a draft on this device.')
    }
  }

  function openEditor() {
    setForm(formFromProfile(profile))
    setErrors({})
    setSaveError('')
    setEditOpen(true)
  }

  function closeEditor() {
    if (busy) return
    setEditOpen(false)
    setForm(formFromProfile(profile))
    setErrors({})
    setSaveError('')
  }

  async function saveProfile(event) {
    event.preventDefault()
    const onboarding = !profile
    const nextErrors = validateProfile(form, districts, onboarding, profile)
    setErrors(nextErrors)
    setSaveError('')
    if (Object.keys(nextErrors).length) {
      if (profile) editTopRef.current?.scrollIntoView({ block: 'start' })
      return
    }
    setSaving(true)
    try {
      const updated = profile
        ? await updateProviderProfile(toProfileEditPayload(form))
        : await createProviderProfile(toPayload(form))
      setProfile(updated)
      setForm(formFromProfile(updated))
      if (draftKey) {
        try { localStorage.removeItem(draftKey) } catch { /* Ignore unavailable storage */ }
      }
      if (onboarding) {
        toast.success('Provider profile saved. Add your verification documents next.')
        navigate('/provider/verification')
      } else {
        setEditOpen(false)
        toast.success('Provider profile updated.')
      }
    } catch (error) {
      setSaveError(error.message || 'Unable to save your provider profile.')
      if (profile) editTopRef.current?.scrollIntoView({ block: 'start' })
    } finally {
      setSaving(false)
    }
  }

  // A service category change may invalidate an APPROVED verification in the
  // backend. Refresh both verification and booking eligibility after saving,
  // rather than leaving an outdated Verified badge visible on this page.
  async function handleServicesUpdated(nextProfile) {
    setProfile(nextProfile)
    setForm(formFromProfile(nextProfile))
    // Do not display the previous statuses while new ones are being loaded.
    setVerification(null)
    setActivity(null)

    const [verificationResult, activityResult] = await Promise.allSettled([
      getMyProviderVerificationStatus(),
      getMyProviderActivity(),
    ])

    if (verificationResult.status === 'fulfilled') {
      setVerification(verificationResult.value)
    }
    if (activityResult.status === 'fulfilled') {
      setActivity(activityResult.value)
    }
    if (verificationResult.status === 'rejected' || activityResult.status === 'rejected') {
      toast.error('Services were saved, but the current verification or booking status could not be refreshed. Reload this page to check your status.')
    }
  }

  async function uploadPhoto(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    if (!IMAGE_TYPES.includes(file.type)) return toast.error('Choose a JPG, PNG or WEBP image.')
    if (file.size > MAX_IMAGE_BYTES) return toast.error('Profile photos must be 5 MB or smaller.')
    setPhotoBusy(true)
    try {
      setProfile(await uploadProviderProfileImage(file))
      toast.success('Profile photo saved.')
    } catch (error) {
      toast.error(error.message || 'Unable to upload your photo.')
    } finally {
      setPhotoBusy(false)
    }
  }

  async function removePhoto() {
    setPhotoBusy(true)
    try {
      setProfile(await removeProviderProfileImage())
      setConfirmPhotoRemoval(false)
      toast.success('Profile photo removed.')
    } catch (error) {
      toast.error(error.message || 'Unable to remove your photo.')
    } finally {
      setPhotoBusy(false)
    }
  }

  if (loading) return <ProviderPage title="My profile" showBack={false} plain><PageLoader label="Loading your profile..." /></ProviderPage>
  if (loadError) return (
    <ProviderPage title="My profile" showBack={false} plain>
      <PageError title="Profile unavailable" message={loadError} onRetry={() => { setLoading(true); setReloadKey((value) => value + 1) }} />
    </ProviderPage>
  )

  const fields = (
    <ProfileFields
      form={form}
      errors={errors}
      districts={districts}
      provinces={provinces}
      categories={categories}
      busy={busy}
      setField={setField}
      toggleItem={toggleItem}
      onboarding={!profile}
      includeServices={!profile}
    />
  )

  return (
    <div className={styles.pageShell}>
      <div className={styles.pageContent}>
        <div className={styles.breadcrumb}>Provider <span>/</span> My Profile</div>
        <ProviderPage
        title={profile ? 'My profile' : 'Complete your provider profile'}
        subtitle={profile ? 'View and manage your provider profile information.' : 'Tell us about your services before continuing to verification.'}
        showBack={false}
        plain
      >
        {catalogError && <InlineMessage variant="error">{catalogError}</InlineMessage>}
        {!profile ? (
          <div className={styles.onboardingCard}>
            <ProgressSteps />
            <div className={styles.onboardingHeading}>
              <h2>Profile details</h2>
              <p>This information will be used to create your marketplace provider profile.</p>
            </div>
            <FormStructure
              onSubmit={saveProfile}
              onCancel={saveDraft}
              cancelLabel="Save draft"
              submitLabel="Save & Continue"
              loading={saving}
              disabled={busy || Boolean(catalogError)}
              error={saveError}
              className={styles.profileForm}
            >
              {fields}
            </FormStructure>
            <p className={styles.draftDisclaimer}>Drafts are stored only on this browser. Your provider profile is created when you select Save & Continue.</p>
          </div>
        ) : (
          <div className={styles.dashboard}>
            <section className={`${styles.card} ${styles.summaryCard}`} aria-label="Provider profile summary">
              <div className={styles.identityArea}>
                <Avatar name={profile.displayName} url={profile.profileImageUrl} large />
                <div className={styles.identityText}>
                  <h2>{profile.displayName || 'Provider'}</h2>
                  <p>{profile.providerType === 'BUSINESS' ? 'Business Provider' : 'Individual Provider'}</p>
                  {user?.email && <p className={styles.emailLine}>✉ {user.email}</p>}
                </div>
              </div>
              <div className={styles.verificationSummary}>
                <span className={styles.mutedLabel}>Verification status</span>
                <StatusBadge status={verification?.status} />
              </div>
              <Button disabled={Boolean(catalogError)} onClick={openEditor}>Edit</Button>
            </section>

            <div className={styles.mainGrid}>
              <section className={`${styles.card} ${styles.aboutCard}`} aria-label="About">
                <h2>About</h2>
                <h3>Bio</h3>
                <p className={styles.bodyText}>{profile.bio || 'No bio added yet.'}</p>
                <div className={styles.cardDivider} />
                <h3>Experience</h3>
                <p className={styles.bodyText}>{profile.experienceYears == null ? 'Not provided' : `${profile.experienceYears} ${profile.experienceYears === 1 ? 'year' : 'years'} of experience`}</p>
              </section>
              <section className={`${styles.card} ${styles.serviceCard}`} aria-label="Service information">
                <h2>Service information</h2>
                <h3>Service categories</h3>
                <div className={styles.chipList}>
                  {categoryNames.length ? categoryNames.map((name, index) => <span key={`${name}-${index}`} className={styles.categoryChip}>{name}</span>) : <p className={styles.bodyText}>No categories selected</p>}
                </div>
                <h3>Service area</h3>
                <p className={styles.bodyText}><ProviderFeatureIcon name="pin" size={15} /> {profile.serviceAreaDistricts?.length ? profile.serviceAreaDistricts.join(', ') : 'Not configured'}</p>
                {profile.city && <p className={styles.secondaryText}>City: {profile.city}</p>}
                <div className={styles.cardActions}><Button variant="outline" disabled={Boolean(catalogError)} onClick={() => setManageOpen('services')}>Manage services</Button></div>
              </section>
            </div>

            <div className={styles.statusGrid}>
              <section className={`${styles.card} ${styles.verificationCard}`} aria-label="Verification">
                <div className={styles.sectionHeading}><h2>Verification</h2><Button variant="outline" onClick={() => navigate('/provider/verification')}>View verification status</Button></div>
                <div className={styles.verificationRow}>
                  <div><span className={styles.mutedLabel}>Verification status</span><StatusBadge status={verification?.status} /></div>
                  <div className={styles.documentSummary}><span className={styles.iconTile} aria-hidden="true"><ProviderFeatureIcon name="document" size={20} /></span><div><strong>Documents</strong><p>{verification?.submittedAt ? 'Verification documents submitted' : 'Documents not submitted'}</p></div></div>
                </div>
                <div className={styles.infoBanner}>ⓘ Your verification status is managed separately from your profile information.</div>
              </section>
              <section className={`${styles.card} ${styles.activityCard}`} aria-label="Profile activity">
                <div className={styles.sectionHeading}><h2>Profile activity</h2><Button variant="outline" disabled={!activity} onClick={() => setManageOpen('activity')}>Manage status</Button></div>
                <div className={styles.activityRow}><span className={styles.mutedLabel}>Current status</span><StatusBadge status={activity?.activityStatus} /></div>
                <div className={styles.cardDivider} />
                <p className={styles.secondaryText}>{activity?.activityStatus === 'PAUSED'
                  ? 'Your profile is paused and cannot receive new booking requests.'
                  : activity?.canReceiveNewBookings
                    ? 'Your profile is active and can receive new booking requests.'
                    : activity ? 'New booking requests are currently unavailable. Check your verification status.' : 'Activity status is unavailable.'}</p>
              </section>
            </div>

            <section className={`${styles.card} ${styles.payoutCard}`} aria-label="Payout details">
              <h2>Payout Details</h2>
              <div className={styles.payoutRow}>
                <div className={styles.payoutDescription}><span className={styles.iconTile} aria-hidden="true"><ProviderFeatureIcon name="bank" size={22} /></span><p>Manage your payout account details to receive payments for completed bookings.</p></div>
                <div className={styles.payoutAccount}><span className={styles.mutedLabel}>Bank account</span>
                  {payout?.configured ? <><strong>{payout.maskedAccountNumber || 'Account configured'}</strong><span>{payout.bankName}</span></> : <span>{payout ? 'Not configured' : 'Unavailable'}</span>}
                </div>
                <Button variant="outline" disabled={!payout} onClick={() => setManageOpen('payout')}>Manage payout details</Button>
              </div>
            </section>
          </div>
        )}
        </ProviderPage>
      </div>

      {/* Native Modal and ConfirmDialog use different overlay systems.
          Unmount the native dialog while confirming photo deletion. */}
      {profile && editOpen && !confirmPhotoRemoval && (
        <Modal isOpen onClose={closeEditor} title="Edit Profile" closeOnBackdrop={!busy} className={styles.editDialog}>
          <div className={styles.editContent} ref={editTopRef}>
            {(saveError || Object.values(errors).some(Boolean)) && (
              <div className={styles.formAlert} role="alert">
                <strong>Unable to save changes</strong>
                <span>{saveError || 'Please review the highlighted fields and try again.'}</span>
              </div>
            )}
            <section className={styles.modalPhoto} aria-label="Profile photo">
              <h3>Profile photo</h3>
              <div className={styles.photoRow}>
                <Avatar name={profile.displayName} url={profile.profileImageUrl} />
                <div className={styles.photoControls}>
                  <input ref={photoInputRef} className={styles.visuallyHidden} type="file" accept="image/jpeg,image/png,image/webp" aria-label="Choose profile photo" disabled={busy} onChange={uploadPhoto} />
                  <div className={styles.photoButtons}>
                    <Button variant="outline" disabled={busy} loading={photoBusy} onClick={() => photoInputRef.current?.click()}>{profile.profileImageUrl ? 'Replace profile photo' : 'Upload profile photo'}</Button>
                    {profile.profileImageUrl && <Button variant="outline" disabled={busy} onClick={() => setConfirmPhotoRemoval(true)}>Remove photo</Button>}
                  </div>
                  <p>PNG, JPG or WEBP. Maximum 5 MB.</p>
                </div>
              </div>
            </section>
            <div className={styles.editHeading}><h3>Basic Information</h3></div>
            <FormStructure onSubmit={saveProfile} onCancel={closeEditor} submitLabel="Save changes" loading={saving} disabled={busy || Boolean(catalogError)} className={styles.modalForm}>
              <div className={styles.readOnlyEmail}><span>Email</span><strong>{user?.email || 'Not available'}</strong><small>Email changes are managed in account settings.</small></div>
              {fields}
            </FormStructure>
          </div>
        </Modal>
      )}

      <ProviderManagementDialogs
        open={manageOpen}
        setOpen={setManageOpen}
        profile={profile}
        activity={activity}
        payout={payout}
        verification={verification}
        categories={categories}
        provinces={provinces}
        onProfileUpdated={handleServicesUpdated}
        onActivityUpdated={setActivity}
        onPayoutUpdated={setPayout}
      />

      <ConfirmDialog
        open={confirmPhotoRemoval}
        title="Remove profile photo?"
        message="The photo saved for this provider profile will be removed."
        confirmLabel="Remove photo"
        variant="destructive"
        loading={photoBusy}
        onCancel={() => setConfirmPhotoRemoval(false)}
        onConfirm={removePhoto}
      />
    </div>
  )
}
