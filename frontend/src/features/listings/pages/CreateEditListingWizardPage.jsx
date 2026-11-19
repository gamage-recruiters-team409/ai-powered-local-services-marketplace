import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { useToast } from '../../../shared/components/feedback'
import { CoverageLeafletMap } from '../components/CoverageLeafletMap'
import {
  AI_SUGGESTION_CHIPS,
  createListing,
  DEFAULT_PORTFOLIO_THUMBNAILS,
  DURATION_OPTIONS,
  extractListingApiError,
  FALLBACK_CATEGORIES,
  findNearestSriLankaDistrict,
  generateAiDescriptionSuggestion,
  getDistrictLocations,
  getListingById,
  getPublicCategories,
  screenListingContent,
  SRI_LANKA_PROVINCES_FALLBACK,
  updateListing,
} from '../services/listingService'
import styles from '../styles/providerListings.module.css'

const STEP_LABELS = [
  { id: 1, title: 'Basic Details', pct: '25%', numericPct: 25 },
  { id: 2, title: 'Pricing & Add-Ons', pct: '50%', numericPct: 50 },
  { id: 3, title: 'Portfolio Media', pct: '75%', numericPct: 75 },
  { id: 4, title: 'Review & Publish', pct: '100%', numericPct: 100 },
]

const STEP_RING_RADIUS = 23
const STEP_RING_CIRCUMFERENCE = 2 * Math.PI * STEP_RING_RADIUS

function StepProgressRing({ numericPct, completed }) {
  if (completed) {
    return (
      <div className={styles.stepRingWrap}>
        <span className={styles.stepCircleDone} aria-hidden="true">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
            <polyline points="20 6 9 17 4 12" />
          </svg>
        </span>
      </div>
    )
  }

  const dashOffset = STEP_RING_CIRCUMFERENCE * (1 - numericPct / 100)
  return (
    <div className={styles.stepRingWrap}>
      <svg className={styles.stepRingSvg} viewBox="0 0 56 56" aria-hidden="true">
        <circle
          cx="28"
          cy="28"
          r={STEP_RING_RADIUS}
          fill="#ffffff"
          stroke="#e2e8f0"
          strokeWidth="4.5"
        />
        <circle
          cx="28"
          cy="28"
          r={STEP_RING_RADIUS}
          fill="none"
          stroke="#0f766e"
          strokeWidth="4.5"
          strokeLinecap="round"
          strokeDasharray={STEP_RING_CIRCUMFERENCE}
          strokeDashoffset={dashOffset}
          transform="rotate(-90 28 28)"
        />
      </svg>
      <span className={styles.stepRingValue}>{numericPct}%</span>
    </div>
  )
}

const ALLOWED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])
const MAX_IMAGE_BYTES = 5 * 1024 * 1024
const MAX_IMAGES = 8

function createInitialFormState() {
  return {
    title: '',
    primaryCategoryId: 'cat-plumbing',
    subcategoryId: 'cat-leak-repair',
    executionModel: 'ON_SITE',
    district: 'Colombo',
    baseCity: 'Colombo 05 (Havelock Town)',
    location: { longitude: 79.8612, latitude: 6.9271 },
    coverageRadiusKm: 15,
    serviceAreas: ['Colombo 01-15', 'Dehiwala', 'Mount Lavinia'],
    description: '',
    pricingModel: 'FIXED',
    basePrice: '2800',
    durationMin: 90,
    addOns: [],
    photos: [
      {
        id: 'photo-default-1',
        name: 'primary_service_cover.jpg',
        httpsUrl: 'https://cdn.localserve.lk/listings/default-cover-plumbing.jpg',
        previewUrl: DEFAULT_PORTFOLIO_THUMBNAILS.plumbing,
      },
    ],
  }
}

function findPrimaryAndSub(categories, categoryId) {
  for (const root of categories) {
    if (root.id === categoryId) {
      return { primaryCategoryId: root.id, subcategoryId: '' }
    }
    const child = (root.children || []).find((sub) => sub.id === categoryId)
    if (child) {
      return { primaryCategoryId: root.id, subcategoryId: child.id }
    }
  }
  const firstRoot = categories[0]
  return {
    primaryCategoryId: firstRoot?.id || '',
    subcategoryId: firstRoot?.children?.find((c) => c.active !== false)?.id || '',
  }
}

export function CreateEditListingWizardPage() {
  const { listingId } = useParams()
  const isEditMode = Boolean(listingId)
  const navigate = useNavigate()
  const toast = useToast()

  const fileInputRef = useRef(null)
  const descriptionRef = useRef(null)

  const [step, setStep] = useState(1)
  const [form, setForm] = useState(createInitialFormState)
  const [categories, setCategories] = useState(FALLBACK_CATEGORIES)
  const [provinces, setProvinces] = useState(SRI_LANKA_PROVINCES_FALLBACK)
  const [loadingInit, setLoadingInit] = useState(true)
  const [submittingMode, setSubmittingMode] = useState('') // '' | 'draft' | 'publish'
  const [errors, setErrors] = useState({})
  const [gpsStatus, setGpsStatus] = useState('Default center: Colombo (6.9271° N, 79.8612° E)')

  // Zone chip input
  const [showZoneInput, setShowZoneInput] = useState(false)
  const [newZoneText, setNewZoneText] = useState('')

  // Add-On inline form
  const [showAddOnEditor, setShowAddOnEditor] = useState(false)
  const [editingAddOnId, setEditingAddOnId] = useState(null)
  const [addOnDraft, setAddOnDraft] = useState({
    title: '',
    description: '',
    extraPrice: '1500',
    extraDurationMin: '30',
  })
  const [addOnError, setAddOnError] = useState('')

  // Step 3 HTTPS URL / Upload feedback
  const [imageUrlInput, setImageUrlInput] = useState('')
  const [uploadNotice, setUploadNotice] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    async function loadInitialData() {
      setLoadingInit(true)
      try {
        const [catRes, geoRes] = await Promise.all([
          getPublicCategories(controller.signal),
          getDistrictLocations(controller.signal),
        ])
        if (!active) return

        const loadedCats = catRes.categories || FALLBACK_CATEGORIES
        const loadedProvinces = geoRes.provinces || SRI_LANKA_PROVINCES_FALLBACK
        setCategories(loadedCats)
        setProvinces(loadedProvinces)

        if (isEditMode && listingId) {
          const { listing } = await getListingById(listingId, controller.signal)
          if (!active) return
          const catPair = findPrimaryAndSub(loadedCats, listing.categoryId)
          const mappedPhotos = (listing.images || []).map((imgUrl, idx) => ({
            id: `existing-photo-${idx}`,
            name: `portfolio_${idx + 1}.jpg`,
            httpsUrl: imgUrl.startsWith('https://')
              ? imgUrl
              : `https://cdn.localserve.lk/listings/${listing.id}-photo-${idx + 1}.jpg`,
            previewUrl: imgUrl,
          }))

          setForm({
            title: listing.title || '',
            primaryCategoryId: catPair.primaryCategoryId,
            subcategoryId: catPair.subcategoryId,
            executionModel: listing.executionModel || 'ON_SITE',
            district: listing.district || 'Colombo',
            baseCity: listing.baseCity || listing.district || '',
            location: listing.location || { longitude: 79.8612, latitude: 6.9271 },
            coverageRadiusKm: Number(listing.coverageRadiusKm ?? 15),
            serviceAreas: Array.isArray(listing.serviceAreas) ? listing.serviceAreas : [],
            description: listing.description || '',
            pricingModel: listing.pricingModel === 'HOURLY' ? 'HOURLY' : 'FIXED',
            basePrice: String(listing.basePrice ?? '2800'),
            durationMin: Number(listing.durationMin ?? 90),
            addOns: Array.isArray(listing.addOns) ? listing.addOns : [],
            photos: mappedPhotos.length > 0 ? mappedPhotos : createInitialFormState().photos,
          })
        }
      } catch (error) {
        if (error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED') {
          return
        }
        toast.error(extractListingApiError(error, 'Failed to initialize listing form.'), {
          title: 'Load Error',
        })
      } finally {
        if (active) setLoadingInit(false)
      }
    }

    loadInitialData()
    return () => {
      active = false
      controller.abort()
    }
  }, [isEditMode, listingId, toast])

  const selectedPrimaryCategory = useMemo(
    () => categories.find((cat) => cat.id === form.primaryCategoryId) || categories[0],
    [categories, form.primaryCategoryId],
  )

  const availableSubcategories = useMemo(
    () => selectedPrimaryCategory?.children || [],
    [selectedPrimaryCategory],
  )

  const selectedSubcategory = useMemo(
    () => availableSubcategories.find((sub) => sub.id === form.subcategoryId),
    [availableSubcategories, form.subcategoryId],
  )

  const effectiveCategoryId = form.subcategoryId || form.primaryCategoryId

  const screeningResult = useMemo(
    () =>
      screenListingContent({
        title: form.title,
        description: form.description,
        addOns: form.addOns,
      }),
    [form.title, form.description, form.addOns],
  )

  const flatDistricts = useMemo(
    () => provinces.flatMap((province) => province.districts || []),
    [provinces],
  )

  const updateField = useCallback((field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }))
    setErrors((prev) => {
      if (!prev[field]) return prev
      const next = { ...prev }
      delete next[field]
      return next
    })
  }, [])

  const validateStep = useCallback(
    (targetStep) => {
      const nextErrors = {}

      if (targetStep === 1) {
        const trimmedTitle = form.title.trim()
        if (trimmedTitle.length < 10) {
          nextErrors.title = 'Service title must be at least 10 characters.'
        } else if (trimmedTitle.length > 120) {
          nextErrors.title = 'Service title must not exceed 120 characters.'
        }

        if (!effectiveCategoryId) {
          nextErrors.categoryId = 'Select a category for your listing.'
        } else if (selectedSubcategory && selectedSubcategory.active === false) {
          nextErrors.categoryId = 'Selected subcategory is currently inactive. Choose an active category.'
        }

        if (!form.district) {
          nextErrors.district = 'District is required.'
        }

        if (form.description.trim().length < 20) {
          nextErrors.description = 'Detailed service scope must be at least 20 characters to publish.'
        }
      }

      if (targetStep === 2) {
        const numericPrice = Number(form.basePrice)
        if (!Number.isFinite(numericPrice) || numericPrice < 100) {
          nextErrors.basePrice = 'Base rate must be at least LKR 100.00.'
        }
        if (!form.durationMin || Number(form.durationMin) < 15) {
          nextErrors.durationMin = 'Estimated duration must be at least 15 minutes.'
        }
      }

      if (targetStep === 3) {
        if (form.photos.length === 0) {
          nextErrors.photos = 'Upload at least 1 portfolio photo before publishing.'
        }
      }

      setErrors(nextErrors)
      return Object.keys(nextErrors).length === 0
    },
    [effectiveCategoryId, form, selectedSubcategory],
  )

  const handleDistrictChange = (districtName) => {
    const matched = flatDistricts.find((item) => item.name === districtName)
    setForm((prev) => ({
      ...prev,
      district: districtName,
      baseCity: prev.baseCity || districtName,
      location: matched
        ? { longitude: matched.longitude, latitude: matched.latitude }
        : prev.location,
    }))
    if (matched) {
      setGpsStatus(`Selected ${matched.name} (${matched.latitude.toFixed(4)}° N, ${matched.longitude.toFixed(4)}° E)`)
    }
  }

  const handleDetectGps = () => {
    if (!navigator.geolocation) {
      const colombo = findNearestSriLankaDistrict(6.9271, 79.8612, provinces)
      handleDistrictChange(colombo.name)
      setGpsStatus(`Fallback GPS resolved ${colombo.name} District (${colombo.latitude.toFixed(4)}° N, ${colombo.longitude.toFixed(4)}° E)`)
      return
    }

    setGpsStatus('Detecting current browser coordinates...')
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const { latitude, longitude } = position.coords
        const nearest = findNearestSriLankaDistrict(latitude, longitude, provinces)
        setForm((prev) => ({
          ...prev,
          district: nearest.name,
          baseCity: nearest.name,
          location: { longitude, latitude },
        }))
        setGpsStatus(
          `Auto-detected via Browser GPS -> ${nearest.name} (${latitude.toFixed(4)}° N, ${longitude.toFixed(4)}° E)`,
        )
      },
      () => {
        const fallbackDist = findNearestSriLankaDistrict(6.9271, 79.8612, provinces)
        handleDistrictChange(fallbackDist.name)
        setGpsStatus(`Location permission denied; defaulted to ${fallbackDist.name} District.`)
      },
      { timeout: 5000 },
    )
  }

  const handleAddZone = () => {
    const cleaned = newZoneText.trim()
    if (!cleaned) return
    if (form.serviceAreas.length >= 20) {
      toast.warning('Maximum 20 covered zones allowed.', { title: 'Limit Reached' })
      return
    }
    if (!form.serviceAreas.some((area) => area.toLowerCase() === cleaned.toLowerCase())) {
      updateField('serviceAreas', [...form.serviceAreas, cleaned])
    }
    setNewZoneText('')
    setShowZoneInput(false)
  }

  const handleAiSuggestDescription = () => {
    const suggestion = generateAiDescriptionSuggestion({
      title: form.title,
      categoryName: selectedPrimaryCategory?.name,
      subcategoryName: selectedSubcategory?.name,
      executionModel: form.executionModel,
      district: form.district,
      coverageRadiusKm: form.coverageRadiusKm,
    })
    updateField('description', suggestion)
    toast.info('Advisory AI description inserted. Review and customize before publishing (BR-016).', {
      title: 'AI-003 Suggestion Applied',
    })
  }

  const handleAppendChipToDescription = (chipText) => {
    const current = form.description.trim()
    if (current.toLowerCase().includes(chipText.toLowerCase())) return
    const next = current ? `${current} Includes ${chipText}.` : `${chipText} included.`
    updateField('description', next)
  }

  const handleOpenAddOnEditor = (existing = null) => {
    if (existing) {
      setEditingAddOnId(existing.addOnId)
      setAddOnDraft({
        title: existing.title,
        description: existing.description || '',
        extraPrice: String(existing.extraPrice),
        extraDurationMin: String(existing.extraDurationMin),
      })
    } else {
      setEditingAddOnId(null)
      setAddOnDraft({
        title: '',
        description: '',
        extraPrice: '1500',
        extraDurationMin: '30',
      })
    }
    setAddOnError('')
    setShowAddOnEditor(true)
  }

  const handleSaveAddOn = () => {
    const title = addOnDraft.title.trim()
    const price = Number(addOnDraft.extraPrice)
    const duration = Number(addOnDraft.extraDurationMin)

    if (title.length < 2 || title.length > 80) {
      setAddOnError('Add-on title must be between 2 and 80 characters.')
      return
    }
    if (!Number.isFinite(price) || price <= 0) {
      setAddOnError('Add-on extra price must be greater than LKR 0.00.')
      return
    }
    if (!Number.isFinite(duration) || duration < 0 || duration > 480) {
      setAddOnError('Add-on extra duration must be between 0 and 480 minutes.')
      return
    }

    if (!editingAddOnId && form.addOns.length >= 10) {
      setAddOnError('Maximum 10 add-on packages per listing.')
      return
    }

    const nextItem = {
      addOnId: editingAddOnId || `ao-${Date.now()}`,
      title,
      description: addOnDraft.description.trim(),
      extraPrice: Number(price.toFixed(2)),
      extraDurationMin: Math.round(duration),
      active: true,
    }

    const updatedList = editingAddOnId
      ? form.addOns.map((item) => (item.addOnId === editingAddOnId ? nextItem : item))
      : [...form.addOns, nextItem]

    updateField('addOns', updatedList)
    setShowAddOnEditor(false)
    setAddOnError('')
  }

  const handleFilesSelected = (fileList) => {
    const files = Array.from(fileList || [])
    if (files.length === 0) return

    setUploadNotice('')
    const remaining = MAX_IMAGES - form.photos.length
    if (remaining <= 0) {
      setUploadNotice('Upload failed: Maximum 8 images allowed per listing.')
      toast.error('Maximum 8 images allowed. Remove an image to upload another.', { title: 'Upload Failed' })
      return
    }

    const accepted = []
    for (const file of files) {
      if (accepted.length >= remaining) break
      if (!ALLOWED_IMAGE_TYPES.has(file.type)) {
        setUploadNotice(`Skipped "${file.name}": Only JPG, PNG, and WEBP formats are allowed.`)
        continue
      }
      if (file.size > MAX_IMAGE_BYTES) {
        setUploadNotice(`Upload failed for "${file.name}": Each image must be under 5 MB.`)
        toast.error(`"${file.name}" exceeds the 5 MB image limit.`, { title: 'Upload Failed' })
        continue
      }
      const slugSafe = file.name.toLowerCase().replace(/[^a-z0-9._-]+/g, '-')
      const objectUrl =
        typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function'
          ? URL.createObjectURL(file)
          : DEFAULT_PORTFOLIO_THUMBNAILS.plumbing

      accepted.push({
        id: `photo-${Date.now()}-${Math.random().toString(16).slice(2, 7)}`,
        name: file.name,
        httpsUrl: `https://cdn.localserve.lk/listings/${Date.now()}-${slugSafe}`,
        previewUrl: objectUrl,
      })
    }

    if (accepted.length > 0) {
      updateField('photos', [...form.photos, ...accepted])
    }
  }

  const handleAddImageUrl = () => {
    const trimmed = imageUrlInput.trim()
    if (!trimmed) return
    if (form.photos.length >= MAX_IMAGES) {
      toast.error('Maximum 8 images allowed per listing.', { title: 'Upload Limit Reached' })
      return
    }
    if (!/^https:\/\/.+/i.test(trimmed)) {
      setUploadNotice('Image URL must begin with https:// (FR-LIST-004).')
      return
    }
    updateField('photos', [
      ...form.photos,
      {
        id: `photo-url-${Date.now()}`,
        name: trimmed.split('/').pop() || 'portfolio-image.jpg',
        httpsUrl: trimmed,
        previewUrl: trimmed,
      },
    ])
    setImageUrlInput('')
    setUploadNotice('')
  }

  const handleSetCoverPhoto = (photoId) => {
    const target = form.photos.find((item) => item.id === photoId)
    if (!target) return
    const reordered = [target, ...form.photos.filter((item) => item.id !== photoId)]
    updateField('photos', reordered)
  }

  const handleRemovePhoto = (photoId) => {
    updateField(
      'photos',
      form.photos.filter((item) => item.id !== photoId),
    )
  }

  const buildPayload = (saveAsDraft) => {
    const imageUrls = form.photos.map((photo) => photo.httpsUrl)
    return {
      categoryId: effectiveCategoryId,
      title: form.title.trim() || 'Untitled Draft Service',
      description: form.description.trim(),
      pricingModel: form.pricingModel,
      basePrice: form.basePrice ? Number(Number(form.basePrice).toFixed(2)) : null,
      durationMin: Number(form.durationMin || 60),
      executionModel: form.executionModel,
      district: form.district,
      baseCity: form.baseCity.trim() || form.district,
      location: form.location,
      coverageRadiusKm: Number(form.coverageRadiusKm ?? 15),
      serviceAreas: form.serviceAreas,
      images: imageUrls,
      coverImageUrl: imageUrls[0] || null,
      addOns: form.addOns.map((item) => ({
        addOnId: item.addOnId,
        title: item.title,
        description: item.description || null,
        extraPrice: Number(item.extraPrice),
        extraDurationMin: Number(item.extraDurationMin),
        active: item.active !== false,
      })),
      saveAsDraft,
    }
  }

  const handleSubmitListing = async (saveAsDraft) => {
    if (!saveAsDraft) {
      const s1 = validateStep(1)
      if (!s1) {
        setStep(1)
        return
      }
      const s2 = validateStep(2)
      if (!s2) {
        setStep(2)
        return
      }
      const s3 = validateStep(3)
      if (!s3) {
        setStep(3)
        return
      }
    }

    setSubmittingMode(saveAsDraft ? 'draft' : 'publish')
    try {
      const payload = buildPayload(saveAsDraft)
      const result = isEditMode
        ? await updateListing(listingId, payload, categories)
        : await createListing(payload, categories)

      const saved = result.listing
      if (saveAsDraft) {
        toast.success(`"${saved.title}" saved as a private draft.`, { title: 'Draft Saved' })
      } else if (saved.status === 'FLAGGED') {
        toast.warning(
          'Submitted for admin review due to contact/link keyword policy (FR-LIST-006).',
          { title: 'Listing Flagged' },
        )
      } else {
        toast.success(`"${saved.title}" is now live in the marketplace.`, { title: 'Listing Published' })
      }

      navigate('/provider/listings')
    } catch (error) {
      toast.error(extractListingApiError(error, 'Failed to save service listing.'), {
        title: 'Submission Failed',
      })
    } finally {
      setSubmittingMode('')
    }
  }

  const handleNextStep = () => {
    if (validateStep(step)) {
      setStep((prev) => Math.min(4, prev + 1))
    }
  }

  const selectedDurationLabel =
    DURATION_OPTIONS.find((opt) => opt.value === Number(form.durationMin))?.label ||
    `${form.durationMin} Mins`

  if (loadingInit) {
    return (
      <div className={styles.wizardWrap}>
        <div className={styles.sectionCard}>Loading service listing editor...</div>
      </div>
    )
  }

  return (
    <div className={styles.wizardWrap}>
      {/* Breadcrumb & Header */}
      <div>
        <div className={styles.breadcrumb}>
          <span>Dashboard</span>
          <span>›</span>
          <span>Service Listings</span>
          <span>›</span>
          <span className={styles.breadcrumbCurrent}>
            {isEditMode ? 'Edit Listing' : 'Create New Listing'} (Step {step}: {STEP_LABELS[step - 1].title})
          </span>
        </div>

        <div className={styles.pageHeader} style={{ marginTop: 8 }}>
          <h1 className={styles.pageTitle}>
            {isEditMode ? 'Edit Service Listing' : 'Create Service Listing'}
          </h1>

          <button
            type="button"
            className={styles.outlineBtn}
            disabled={Boolean(submittingMode)}
            onClick={() => handleSubmitListing(true)}
          >
            {submittingMode === 'draft' ? 'Saving Draft...' : 'Save Draft'}
          </button>
        </div>
      </div>

      {/* 4-Step Progress Stepper */}
      <nav className={styles.stepperCard} aria-label="Listing creation progress">
        {STEP_LABELS.map((item, idx) => {
          const isCompleted = item.id < step
          const isActive = item.id === step
          const isNextUp = item.id === step + 1
          const hasNextConnector = idx < STEP_LABELS.length - 1
          const connectorFillPct = item.id < step ? 100 : item.id === step ? 45 : 0

          return (
            <div key={item.id} className={styles.stepItem}>
              {hasNextConnector && (
                <div className={styles.stepConnectorTrack} aria-hidden="true">
                  <div
                    className={styles.stepConnectorFill}
                    style={{ width: `${connectorFillPct}%` }}
                  />
                </div>
              )}

              <button
                type="button"
                className={styles.stepBtn}
                aria-current={isActive ? 'step' : undefined}
                onClick={() => {
                  if (item.id <= step || validateStep(step)) {
                    setStep(item.id)
                  }
                }}
              >
                <StepProgressRing numericPct={item.numericPct} completed={isCompleted} />
                <span className={styles.stepLabel}>
                  {item.id}. {item.title}
                </span>
                <span
                  className={
                    isCompleted || isActive ? styles.stepStatusActive : styles.stepStatusMuted
                  }
                >
                  {isCompleted ? 'Complete' : isActive ? 'In progress' : isNextUp ? 'Next up' : 'Pending'}
                </span>
              </button>
            </div>
          )
        })}
      </nav>

      {/* STEP 1: BASIC DETAILS & GEOGRAPHY (Unified High-Fi Card) */}
      {step === 1 && (
        <section className={styles.sectionCard}>
          <div className={styles.unifiedSubsection}>
            <div className={styles.sectionTitleHeader}>
              <div className={styles.sectionTitleLeft}>
                <span className={styles.sectionNumBadge}>01</span>
                <h2 className={styles.sectionHeading}>Taxonomy &amp; Service Title</h2>
              </div>
            </div>

            <div className={styles.fieldGroup}>
              <div className={styles.fieldLabelRow}>
                <label htmlFor="listing-title" className={styles.fieldLabel}>
                  Service Title *
                </label>
                <span className={styles.charCounter}>{form.title.length} / 120 characters</span>
              </div>
              <input
                id="listing-title"
                type="text"
                maxLength={120}
                className={`${styles.textInput} ${errors.title ? styles.inputError : ''}`}
                placeholder="Emergency Pipe Leak Repair & Waterline Diagnostic"
                value={form.title}
                onChange={(event) => updateField('title', event.target.value)}
              />
              {errors.title ? (
                <p className={styles.fieldErrorText}>{errors.title}</p>
              ) : (
                <p className={styles.fieldHelper}>Keep it clear and outcome-driven to attract qualified local customers.</p>
              )}
            </div>

            <div className={styles.fieldGridTwo}>
              <div className={styles.fieldGroup}>
                <label htmlFor="primary-category" className={styles.fieldLabel}>
                  Primary Category *
                </label>
                <select
                  id="primary-category"
                  className={styles.selectInput}
                  value={form.primaryCategoryId}
                  onChange={(event) => {
                    const nextRootId = event.target.value
                    const rootObj = categories.find((c) => c.id === nextRootId)
                    const firstActiveChild = rootObj?.children?.find((sub) => sub.active !== false)
                    setForm((prev) => ({
                      ...prev,
                      primaryCategoryId: nextRootId,
                      subcategoryId: firstActiveChild?.id || '',
                    }))
                  }}
                >
                  {categories.map((cat) => (
                    <option key={cat.id} value={cat.id} disabled={cat.active === false}>
                      {cat.name}{cat.active === false ? ' (Inactive)' : ''}
                    </option>
                  ))}
                </select>
              </div>

              <div className={styles.fieldGroup}>
                <label htmlFor="subcategory" className={styles.fieldLabel}>
                  Subcategory
                </label>
                <select
                  id="subcategory"
                  className={styles.selectInput}
                  value={form.subcategoryId}
                  onChange={(event) => updateField('subcategoryId', event.target.value)}
                >
                  <option value="">General ({selectedPrimaryCategory?.name || 'Root'})</option>
                  {availableSubcategories.map((sub) => (
                    <option key={sub.id} value={sub.id} disabled={sub.active === false}>
                      {sub.name}{sub.active === false ? ' (Inactive)' : ''}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            {errors.categoryId && <p className={styles.fieldErrorText}>{errors.categoryId}</p>}
          </div>

          <div className={`${styles.unifiedSubsection} ${styles.unifiedSubsectionDivider}`}>
            <div className={styles.sectionTitleHeader}>
              <div className={styles.sectionTitleLeft}>
                <span className={styles.sectionNumBadge}>02</span>
                <h2 className={styles.sectionHeading}>Fulfillment &amp; Coverage Radius</h2>
              </div>
            </div>

            <div className={styles.fieldGroup}>
              <span className={styles.fieldLabel}>Service Execution Model</span>
              <div className={styles.executionPillsRow}>
                <button
                  type="button"
                  className={`${styles.executionPillBtn} ${
                    form.executionModel === 'ON_SITE' ? styles.executionPillBtnSelected : ''
                  }`}
                  onClick={() => updateField('executionModel', 'ON_SITE')}
                >
                  <span
                    className={`${styles.checkboxSquare} ${
                      form.executionModel === 'ON_SITE' ? styles.checkboxSquareChecked : ''
                    }`}
                    aria-hidden="true"
                  >
                    {form.executionModel === 'ON_SITE' ? '✓' : ''}
                  </span>
                  <span>On-Site (Customer Location)</span>
                </button>

                <button
                  type="button"
                  className={`${styles.executionPillBtn} ${
                    form.executionModel === 'WORKSHOP' ? styles.executionPillBtnSelected : ''
                  }`}
                  onClick={() => updateField('executionModel', 'WORKSHOP')}
                >
                  <span
                    className={`${styles.checkboxSquare} ${
                      form.executionModel === 'WORKSHOP' ? styles.checkboxSquareChecked : ''
                    }`}
                    aria-hidden="true"
                  >
                    {form.executionModel === 'WORKSHOP' ? '✓' : ''}
                  </span>
                  <span>Provider Workshop / Bay</span>
                </button>
              </div>
            </div>

            <div className={styles.fieldGroup}>
              <label htmlFor="district-select" className={styles.fieldLabel}>
                Base City / District (Select From Platform List) *
              </label>
              <div className={styles.districtRow}>
                <select
                  id="district-select"
                  className={styles.selectInput}
                  value={form.district}
                  onChange={(event) => handleDistrictChange(event.target.value)}
                >
                  {provinces.map((province) => (
                    <optgroup key={province.name} label={province.name}>
                      {(province.districts || []).map((district) => (
                        <option key={district.name} value={district.name}>
                          {district.name} District ({province.name})
                        </option>
                      ))}
                    </optgroup>
                  ))}
                </select>

                <button type="button" className={styles.outlineBtn} onClick={handleDetectGps}>
                  📍 Detect Via GPS
                </button>
              </div>
              <p className={styles.fieldHelper}>{gpsStatus}</p>
            </div>

            <div className={styles.fieldGroup}>
              <label htmlFor="base-city-input" className={styles.fieldLabel}>
                Dispatch Neighbourhood / Base City
              </label>
              <input
                id="base-city-input"
                type="text"
                maxLength={100}
                className={styles.textInput}
                placeholder="e.g. Colombo 05 (Havelock Town)"
                value={form.baseCity}
                onChange={(event) => updateField('baseCity', event.target.value)}
              />
            </div>

            <div className={styles.fieldGroup}>
              <span className={styles.fieldLabel}>Covered Cities &amp; Zones</span>
              <div className={styles.zonesList}>
                {form.serviceAreas.map((area) => (
                  <span key={area} className={styles.zoneChip}>
                    ✓ {area}
                    <button
                      type="button"
                      className={styles.zoneRemoveBtn}
                      aria-label={`Remove ${area}`}
                      onClick={() =>
                        updateField(
                          'serviceAreas',
                          form.serviceAreas.filter((item) => item !== area),
                        )
                      }
                    >
                      ×
                    </button>
                  </span>
                ))}

                {showZoneInput ? (
                  <div className={styles.controlsGroup}>
                    <input
                      type="text"
                      className={styles.textInput}
                      style={{ width: 170, padding: '6px 10px' }}
                      placeholder="Enter suburb..."
                      aria-label="New service area name"
                      value={newZoneText}
                      onChange={(event) => setNewZoneText(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault()
                          handleAddZone()
                        }
                      }}
                    />
                    <button type="button" className={styles.outlineBtn} onClick={handleAddZone}>
                      Add
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    className={styles.dashedChipBtn}
                    onClick={() => setShowZoneInput(true)}
                  >
                    + Add City
                  </button>
                )}
              </div>
            </div>

            <div className={styles.fieldGroup}>
              <div className={styles.fieldLabelRow}>
                <label htmlFor="coverage-radius" className={styles.fieldLabel}>
                  Coverage Radius
                </label>
                <span className={styles.countPill}>{form.coverageRadiusKm} Km</span>
              </div>
              <input
                id="coverage-radius"
                type="range"
                min={0}
                max={50}
                step={1}
                className={styles.rangeInput}
                style={{
                  background: `linear-gradient(to right, #0f766e 0%, #0f766e ${(form.coverageRadiusKm / 50) * 100}%, #e2e8f0 ${(form.coverageRadiusKm / 50) * 100}%, #e2e8f0 100%)`,
                }}
                value={form.coverageRadiusKm}
                onChange={(event) => updateField('coverageRadiusKm', Number(event.target.value))}
              />
              <div className={styles.fieldLabelRow}>
                <span className={styles.fieldHelper}>0 Km</span>
                <span className={styles.fieldHelper}>25 Km</span>
                <span className={styles.fieldHelper}>50 Km</span>
              </div>

              <CoverageLeafletMap
                latitude={form.location?.latitude ?? 6.9271}
                longitude={form.location?.longitude ?? 79.8612}
                radiusKm={form.coverageRadiusKm}
                districtLabel={form.baseCity || form.district}
                onCoordinateChange={(coords) => {
                  updateField('location', coords)
                  setGpsStatus(
                    `Pinned on map: ${coords.latitude.toFixed(4)}° N, ${coords.longitude.toFixed(4)}° E`,
                  )
                }}
              />
            </div>
          </div>

          <div className={`${styles.unifiedSubsection} ${styles.unifiedSubsectionDivider}`}>
            <div className={styles.sectionTitleHeader}>
              <div className={styles.sectionTitleLeft}>
                <span className={styles.sectionNumBadge}>03</span>
                <h2 className={styles.sectionHeading}>Scope &amp; Deliverables</h2>
              </div>
              <button
                type="button"
                className={styles.aiSuggestBtn}
                onClick={handleAiSuggestDescription}
              >
                ⚡ + AI Suggest Description
              </button>
            </div>

            {screeningResult.flagged && (
              <div className={styles.policyWarningBanner} role="alert">
                <span>
                  🛡 Platform Policy: {screeningResult.reasons[0]}
                </span>
                <button
                  type="button"
                  className={styles.dangerBtn}
                  onClick={() => descriptionRef.current?.focus()}
                >
                  Review text
                </button>
              </div>
            )}

            <div className={styles.fieldGroup}>
              <div className={styles.fieldLabelRow}>
                <label htmlFor="listing-description" className={styles.fieldLabel}>
                  Detailed Service Scope *
                </label>
                <span className={styles.charCounter}>{form.description.length} / 3,000 characters</span>
              </div>
              <textarea
                id="listing-description"
                ref={descriptionRef}
                rows={5}
                maxLength={3000}
                className={`${styles.textareaInput} ${errors.description ? styles.inputError : ''}`}
                placeholder="Describe inspection steps, tools used, safety guarantees, and what is included..."
                value={form.description}
                onChange={(event) => updateField('description', event.target.value)}
              />
              {errors.description && <p className={styles.fieldErrorText}>{errors.description}</p>}

              <div className={styles.suggestionChipsRow}>
                {AI_SUGGESTION_CHIPS.map((chip) => (
                  <button
                    key={chip}
                    type="button"
                    className={styles.suggestionChipBtn}
                    onClick={() => handleAppendChipToDescription(chip)}
                  >
                    + {chip}
                  </button>
                ))}
              </div>
            </div>
          </div>

          <div className={`${styles.wizardFooterBar} ${styles.unifiedSubsectionDivider}`}>
            <button
              type="button"
              className={styles.outlineBtn}
              onClick={() => navigate('/provider/listings')}
            >
              Cancel
            </button>
            <button type="button" className={styles.primaryBtn} onClick={handleNextStep}>
              Continue To Pricing &amp; Add-Ons →
            </button>
          </div>
        </section>
      )}

      {/* STEP 2: PRICING & ADD-ONS */}
      {step === 2 && (
        <>
          <div className={styles.infoBanner}>
            <span>
              ℹ <strong>Price Snapshot:</strong> Editing price or duration applies only to prospective bookings.
              Existing active bookings retain their original rates (FR-LIST-005).
            </span>
          </div>

          <section className={styles.sectionCard}>
            <div className={styles.sectionTitleHeader}>
              <div className={styles.sectionTitleLeft}>
                <span className={styles.sectionNumBadge}>01</span>
                <h2 className={styles.sectionHeading}>Pricing Model &amp; Base Rates</h2>
              </div>
            </div>

            <div className={styles.fieldGroup}>
              <span className={styles.fieldLabel}>Service Pricing Model</span>
              <div className={styles.choiceCardsGrid}>
                <button
                  type="button"
                  className={`${styles.choiceCard} ${
                    form.pricingModel === 'FIXED' ? styles.choiceCardSelected : ''
                  }`}
                  onClick={() => updateField('pricingModel', 'FIXED')}
                >
                  <div className={styles.choiceCardTitleRow}>
                    <span style={{ display: 'inline-flex', alignItems: 'center' }}>
                      <span
                        className={`${styles.radioDot} ${
                          form.pricingModel === 'FIXED' ? styles.radioDotSelected : ''
                        }`}
                        aria-hidden="true"
                      >
                        {form.pricingModel === 'FIXED' && <span className={styles.radioDotInner} />}
                      </span>
                      <span>Fixed Price</span>
                    </span>
                    {form.pricingModel === 'FIXED' && <span className={styles.countPill}>Active</span>}
                  </div>
                  <p className={styles.choiceCardDesc}>
                    Standard upfront diagnostic and initial callout fee for common service requests.
                  </p>
                </button>

                <button
                  type="button"
                  className={`${styles.choiceCard} ${
                    form.pricingModel === 'HOURLY' ? styles.choiceCardSelected : ''
                  }`}
                  onClick={() => updateField('pricingModel', 'HOURLY')}
                >
                  <div className={styles.choiceCardTitleRow}>
                    <span style={{ display: 'inline-flex', alignItems: 'center' }}>
                      <span
                        className={`${styles.radioDot} ${
                          form.pricingModel === 'HOURLY' ? styles.radioDotSelected : ''
                        }`}
                        aria-hidden="true"
                      >
                        {form.pricingModel === 'HOURLY' && <span className={styles.radioDotInner} />}
                      </span>
                      <span>Hourly Rate</span>
                    </span>
                    {form.pricingModel === 'HOURLY' && <span className={styles.countPill}>Active</span>}
                  </div>
                  <p className={styles.choiceCardDesc}>
                    Flexible labor billing per service rate for larger or variable technical repairs.
                  </p>
                </button>
              </div>
            </div>

            <div className={styles.fieldGridTwo}>
              <div className={styles.fieldGroup}>
                <label htmlFor="base-price" className={styles.fieldLabel}>
                  BASE RATE (LKR) *
                </label>
                <div className={`${styles.currencyInputBox} ${errors.basePrice ? styles.inputError : ''}`}>
                  <span className={styles.currencyAddonLeft}>Rs.</span>
                  <input
                    id="base-price"
                    type="number"
                    min={100}
                    step="50"
                    className={styles.currencyInnerInput}
                    value={form.basePrice}
                    onChange={(event) => updateField('basePrice', event.target.value)}
                  />
                  <span className={styles.currencyAddonRight}>LKR</span>
                </div>
                {errors.basePrice ? (
                  <p className={styles.fieldErrorText}>{errors.basePrice}</p>
                ) : (
                  <p className={styles.fieldHelper}>Clients see this as the starting commitment price.</p>
                )}
              </div>

              <div className={styles.fieldGroup}>
                <label htmlFor="duration-select" className={styles.fieldLabel}>
                  ESTIMATED SERVICE DURATION *
                </label>
                <select
                  id="duration-select"
                  className={styles.selectInput}
                  value={form.durationMin}
                  onChange={(event) => updateField('durationMin', Number(event.target.value))}
                >
                  {DURATION_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </select>
                <p className={styles.fieldHelper}>Helps calculate slot bookings &amp; schedule dispatch.</p>
              </div>
            </div>
          </section>

          <section className={styles.sectionCard}>
            <div className={styles.sectionTitleHeader}>
              <div className={styles.sectionTitleLeft}>
                <span className={styles.sectionNumBadge}>02</span>
                <h2 className={styles.sectionHeading}>Optional Add-On Packages (Extras)</h2>
              </div>
              <span className={styles.addOnsChip}>{form.addOns.length} configured</span>
            </div>

            {form.addOns.map((addOn) => (
              <div key={addOn.addOnId} className={styles.addOnCard}>
                <div className={styles.addOnLeftGroup}>
                  <span className={styles.addOnIconBadge} aria-hidden="true">+</span>
                  <div>
                    <strong>{addOn.title}</strong>
                    {addOn.description && <p className={styles.fieldHelper}>{addOn.description}</p>}
                  </div>
                </div>
                <div className={styles.controlsGroup}>
                  <div style={{ textAlign: 'right' }}>
                    <strong>+ Rs. {Number(addOn.extraPrice).toLocaleString('en-LK', { minimumFractionDigits: 2 })}</strong>
                    <div className={styles.fieldHelper}>Duration: +{addOn.extraDurationMin} Mins</div>
                  </div>
                  <button
                    type="button"
                    className={styles.outlineBtn}
                    onClick={() => handleOpenAddOnEditor(addOn)}
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    className={styles.deleteOutlineBtn}
                    onClick={() =>
                      updateField(
                        'addOns',
                        form.addOns.filter((item) => item.addOnId !== addOn.addOnId),
                      )
                    }
                  >
                    Delete
                  </button>
                </div>
              </div>
            ))}

            {showAddOnEditor ? (
              <div className={styles.archiveInfoCard}>
                <strong>{editingAddOnId ? 'Edit Add-On Package' : 'New Add-On Package'}</strong>
                <div className={styles.fieldGridTwo} style={{ marginTop: 8 }}>
                  <div className={styles.fieldGroup}>
                    <label htmlFor="addon-title" className={styles.fieldLabel}>Add-On Title *</label>
                    <input
                      id="addon-title"
                      type="text"
                      maxLength={80}
                      className={styles.textInput}
                      placeholder="e.g. Emergency Night Callout"
                      value={addOnDraft.title}
                      onChange={(event) => setAddOnDraft((p) => ({ ...p, title: event.target.value }))}
                    />
                  </div>
                  <div className={styles.fieldGroup}>
                    <label htmlFor="addon-desc" className={styles.fieldLabel}>Short Description</label>
                    <input
                      id="addon-desc"
                      type="text"
                      maxLength={300}
                      className={styles.textInput}
                      placeholder="Optional customer-facing note"
                      value={addOnDraft.description}
                      onChange={(event) => setAddOnDraft((p) => ({ ...p, description: event.target.value }))}
                    />
                  </div>
                  <div className={styles.fieldGroup}>
                    <label htmlFor="addon-price" className={styles.fieldLabel}>Extra Price (LKR) *</label>
                    <input
                      id="addon-price"
                      type="number"
                      min={1}
                      className={styles.textInput}
                      value={addOnDraft.extraPrice}
                      onChange={(event) => setAddOnDraft((p) => ({ ...p, extraPrice: event.target.value }))}
                    />
                  </div>
                  <div className={styles.fieldGroup}>
                    <label htmlFor="addon-duration" className={styles.fieldLabel}>Extra Duration (Mins)</label>
                    <input
                      id="addon-duration"
                      type="number"
                      min={0}
                      max={480}
                      className={styles.textInput}
                      value={addOnDraft.extraDurationMin}
                      onChange={(event) => setAddOnDraft((p) => ({ ...p, extraDurationMin: event.target.value }))}
                    />
                  </div>
                </div>
                {addOnError && <p className={styles.fieldErrorText}>{addOnError}</p>}
                <div className={styles.modalFooter} style={{ marginTop: 8 }}>
                  <button
                    type="button"
                    className={styles.outlineBtn}
                    onClick={() => setShowAddOnEditor(false)}
                  >
                    Cancel
                  </button>
                  <button type="button" className={styles.primaryBtn} onClick={handleSaveAddOn}>
                    Save Add-On
                  </button>
                </div>
              </div>
            ) : (
              <button
                type="button"
                className={styles.dashedAddBtn}
                onClick={() => handleOpenAddOnEditor(null)}
              >
                + Add Another Extra / Add-On Package
              </button>
            )}
          </section>

          <div className={styles.wizardFooterBar}>
            <div className={styles.controlsGroup}>
              <button type="button" className={styles.outlineBtn} onClick={() => setStep(1)}>
                ← Back To Basic Details
              </button>
              <button
                type="button"
                className={styles.ghostBtn}
                onClick={() => navigate('/provider/listings')}
              >
                Cancel
              </button>
            </div>
            <button type="button" className={styles.primaryBtn} onClick={handleNextStep}>
              Continue To Portfolio Media →
            </button>
          </div>
        </>
      )}

      {/* STEP 3: PORTFOLIO MEDIA */}
      {step === 3 && (
        <>
          <section className={styles.sectionCard}>
            <div className={styles.sectionTitleHeader}>
              <div className={styles.sectionTitleLeft}>
                <span className={styles.sectionNumBadge}>01</span>
                <h2 className={styles.sectionHeading}>Media Dropzone</h2>
              </div>
              <span className={styles.fieldHelper}>High-quality photos increase bookings cleanly</span>
            </div>

            <div
              className={styles.dropzoneBox}
              onDragOver={(event) => event.preventDefault()}
              onDrop={(event) => {
                event.preventDefault()
                handleFilesSelected(event.dataTransfer?.files)
              }}
            >
              <span className={styles.dropzoneIconCircle} aria-hidden="true">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="17 8 12 3 7 8" />
                  <line x1="12" y1="3" x2="12" y2="15" />
                </svg>
              </span>
              <strong>Media Dropzone</strong>
              <p className={styles.fieldHelper}>
                Drag &amp; drop high-resolution service photos here. Supports JPG, PNG, WEBP up to 5MB each (Max 8 photos).
              </p>
              <input
                ref={fileInputRef}
                type="file"
                multiple
                accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
                hidden
                aria-label="Upload portfolio photos"
                onChange={(event) => handleFilesSelected(event.target.files)}
              />
              <button
                type="button"
                className={styles.primaryBtn}
                onClick={() => fileInputRef.current?.click()}
              >
                + Select Photos From Computer
              </button>
            </div>

            <div className={styles.districtRow}>
              <input
                type="url"
                className={styles.textInput}
                placeholder="Or paste HTTPS image URL (e.g. https://cdn.localserve.lk/work-1.jpg)"
                aria-label="HTTPS image URL"
                value={imageUrlInput}
                onChange={(event) => setImageUrlInput(event.target.value)}
              />
              <button type="button" className={styles.outlineBtn} onClick={handleAddImageUrl}>
                Add URL
              </button>
            </div>

            {uploadNotice && <p className={styles.fieldErrorText}>{uploadNotice}</p>}
            {errors.photos && <p className={styles.fieldErrorText}>{errors.photos}</p>}
          </section>

          <section className={styles.sectionCard}>
            <div className={styles.sectionTitleHeader}>
              <div className={styles.sectionTitleLeft}>
                <span className={styles.sectionNumBadge}>02</span>
                <h2 className={styles.sectionHeading}>Uploaded Portfolio Photos (Max 8)</h2>
              </div>
              <span className={styles.countPill}>{MAX_IMAGES - form.photos.length} Slots Remaining</span>
            </div>

            <div className={styles.photoGrid}>
              {form.photos.map((photo, index) => {
                const isCover = index === 0
                return (
                  <div
                    key={photo.id}
                    className={`${styles.photoCard} ${isCover ? styles.photoCardCover : ''}`}
                  >
                    <div className={styles.photoPreviewWrap}>
                      <img
                        src={photo.previewUrl || photo.httpsUrl}
                        alt={photo.name}
                        className={styles.photoPreviewImg}
                        onError={(event) => {
                          event.currentTarget.src = DEFAULT_PORTFOLIO_THUMBNAILS.plumbing
                        }}
                      />
                      {isCover && <span className={styles.coverBadge}>PRIMARY COVER</span>}
                    </div>
                    <div className={styles.photoNameLine}>{photo.name}</div>
                    <div className={styles.photoCardFooter}>
                      {isCover ? (
                        <span className={styles.kpiMetaEmerald}>Active Cover</span>
                      ) : (
                        <button
                          type="button"
                          className={styles.textBtnEmerald}
                          onClick={() => handleSetCoverPhoto(photo.id)}
                        >
                          Make Cover
                        </button>
                      )}
                      <button
                        type="button"
                        className={styles.textBtnRed}
                        onClick={() => handleRemovePhoto(photo.id)}
                      >
                        Delete
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>

            <div className={styles.archiveInfoCard}>
              <div className={styles.archiveInfoRow}>
                <span>✓ Direct Upload Status:</span>
                <strong>{form.photos.length} / {MAX_IMAGES}</strong>
              </div>
              <div className={styles.uploadBarTrack}>
                <div
                  className={styles.uploadBarFill}
                  style={{ width: `${(form.photos.length / MAX_IMAGES) * 100}%` }}
                />
              </div>
            </div>
          </section>

          <div className={styles.wizardFooterBar}>
            <div className={styles.controlsGroup}>
              <button type="button" className={styles.outlineBtn} onClick={() => setStep(2)}>
                ← Back To Pricing &amp; Add-Ons
              </button>
              <button
                type="button"
                className={styles.ghostBtn}
                onClick={() => navigate('/provider/listings')}
              >
                Cancel
              </button>
            </div>
            <button type="button" className={styles.primaryBtn} onClick={handleNextStep}>
              Continue To Review &amp; Publish →
            </button>
          </div>
        </>
      )}

      {/* STEP 4: REVIEW & PUBLISH */}
      {step === 4 && (
        <>
          <section className={styles.sectionCard}>
            <div className={styles.sectionTitleHeader}>
              <div className={styles.sectionTitleLeft}>
                <span className={styles.sectionNumBadge}>01</span>
                <h2 className={styles.sectionHeading}>Basic Details &amp; Geography</h2>
              </div>
              <button type="button" className={styles.outlineBtn} onClick={() => setStep(1)}>
                Edit
              </button>
            </div>

            <div className={styles.fieldGridTwo}>
              <div className={styles.reviewCell}>
                <p className={styles.reviewLabel}>SERVICE TITLE</p>
                <p className={styles.reviewValue}>{form.title}</p>
              </div>
              <div className={styles.reviewCell}>
                <p className={styles.reviewLabel}>CATEGORY &amp; SUBCATEGORY</p>
                <p className={styles.reviewValue}>
                  {selectedPrimaryCategory?.name}
                  {selectedSubcategory ? ` > ${selectedSubcategory.name}` : ''}
                </p>
              </div>
            </div>

            <div className={styles.reviewSummaryGrid}>
              <div className={styles.reviewCell}>
                <p className={styles.reviewLabel}>SERVICE EXECUTION MODEL</p>
                <p className={styles.reviewValue}>
                  {form.executionModel === 'WORKSHOP'
                    ? 'Provider Workshop / Bay'
                    : 'On-Site (Customer Location)'}
                </p>
              </div>
              <div className={styles.reviewCell}>
                <p className={styles.reviewLabel}>DISPATCH BASE CITY</p>
                <p className={styles.reviewValue}>{form.baseCity || form.district}</p>
              </div>
              <div className={styles.reviewCell}>
                <p className={styles.reviewLabel}>COVERAGE ZONES &amp; RADIUS</p>
                <p className={styles.reviewValue}>
                  {form.serviceAreas.join(', ') || form.district} ({form.coverageRadiusKm} Km Radius)
                </p>
              </div>
            </div>

            <div className={styles.reviewCell}>
              <p className={styles.reviewLabel}>DETAILED SERVICE SCOPE &amp; DELIVERABLES</p>
              <p className={styles.modalBodyText}>{form.description}</p>
            </div>

            {screeningResult.flagged && (
              <div className={styles.policyWarningBanner}>
                <span>
                  ⚠ Content screening detected contact/link text — publishing will route this listing to Admin
                  Moderation (FLAGGED) for approval.
                </span>
              </div>
            )}
          </section>

          <section className={styles.sectionCard}>
            <div className={styles.sectionTitleHeader}>
              <div className={styles.sectionTitleLeft}>
                <span className={styles.sectionNumBadge}>02</span>
                <h2 className={styles.sectionHeading}>Pricing Model &amp; Add-On Packages</h2>
              </div>
              <button type="button" className={styles.outlineBtn} onClick={() => setStep(2)}>
                Edit
              </button>
            </div>

            <div className={styles.reviewSummaryGrid}>
              <div className={styles.reviewCell}>
                <p className={styles.reviewLabel}>PRICING MODEL</p>
                <p className={styles.reviewValue}>
                  {form.pricingModel === 'HOURLY' ? 'Hourly Rate (LKR)' : 'Fixed Price Callout'}
                </p>
              </div>
              <div className={styles.reviewCellHighlight}>
                <p className={styles.reviewLabel}>BASE RATE</p>
                <p className={styles.reviewValueEmerald}>
                  Rs. {Number(form.basePrice || 0).toLocaleString('en-LK', { minimumFractionDigits: 2 })} LKR
                </p>
                <p className={styles.fieldHelper}>Includes initial diagnosis and arrival</p>
              </div>
              <div className={styles.reviewCell}>
                <p className={styles.reviewLabel}>ESTIMATED DURATION</p>
                <p className={styles.reviewValue}>{selectedDurationLabel}</p>
              </div>
            </div>

            <div>
              <p className={styles.reviewLabel}>
                CONFIGURED ADD-ON PACKAGES ({form.addOns.length} INCLUDED)
              </p>
              {form.addOns.length === 0 ? (
                <p className={styles.fieldHelper}>No optional add-on packages configured.</p>
              ) : (
                <div className={styles.reviewSummaryGrid} style={{ marginTop: 8 }}>
                  {form.addOns.map((addOn) => (
                    <div key={addOn.addOnId} className={styles.reviewCell}>
                      <p className={styles.reviewValue}>{addOn.title}</p>
                      <p className={styles.kpiMetaEmerald}>
                        + Rs. {Number(addOn.extraPrice).toLocaleString('en-LK', { minimumFractionDigits: 2 })} (+{addOn.extraDurationMin} Mins)
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </section>

          <section className={styles.sectionCard}>
            <div className={styles.sectionTitleHeader}>
              <div className={styles.sectionTitleLeft}>
                <span className={styles.sectionNumBadge}>03</span>
                <h2 className={styles.sectionHeading}>
                  Portfolio Media ({form.photos.length} Photo{form.photos.length === 1 ? '' : 's'} Attached)
                </h2>
              </div>
              <button type="button" className={styles.outlineBtn} onClick={() => setStep(3)}>
                Edit
              </button>
            </div>

            <div className={styles.photoGrid}>
              {form.photos.map((photo, index) => (
                <div key={photo.id} className={styles.photoCard}>
                  <div className={styles.photoPreviewWrap}>
                    <img
                      src={photo.previewUrl || photo.httpsUrl}
                      alt={photo.name}
                      className={styles.photoPreviewImg}
                      onError={(event) => {
                        event.currentTarget.src = DEFAULT_PORTFOLIO_THUMBNAILS.plumbing
                      }}
                    />
                    {index === 0 && <span className={styles.coverBadge}>Primary</span>}
                  </div>
                  <div className={styles.photoCardFooter}>
                    <span className={styles.fieldHelper}>{photo.name}</span>
                  </div>
                </div>
              ))}
            </div>
            <p className={styles.fieldHelper}>
              Upload quota: <strong>{form.photos.length} of 8</strong> media slots used.
            </p>
          </section>

          <div className={styles.wizardFooterBar}>
            <div className={styles.controlsGroup}>
              <button type="button" className={styles.outlineBtn} onClick={() => setStep(3)}>
                ← Back To Portfolio Media
              </button>
              <button
                type="button"
                className={styles.ghostBtn}
                onClick={() => navigate('/provider/listings')}
              >
                Cancel
              </button>
            </div>
            <button
              type="button"
              className={styles.primaryBtn}
              disabled={Boolean(submittingMode)}
              onClick={() => handleSubmitListing(false)}
            >
              {submittingMode === 'publish'
                ? 'Publishing Listing...'
                : isEditMode
                  ? 'Save Changes & Publish →'
                  : 'Publish Service Listing →'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}
