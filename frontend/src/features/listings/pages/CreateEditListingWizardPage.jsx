import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { useToast } from '../../../shared/components/feedback'
import { CoverageLeafletMap } from '../components/CoverageLeafletMap'
import { ListingCoverMedia } from '../components/ListingCoverMedia'
import { WizardLoadingSkeleton } from '../components/ListingSkeletons'
import {
  IconAlertTriangle,
  IconArrowLeft,
  IconArrowRight,
  IconCheck,
  IconCheckCircle,
  IconEye,
  IconGps,
  IconHomePin,
  IconInfo,
  IconMapPin,
  IconPlus,
  IconRefresh,
  IconShieldAlert,
  IconSparkles,
  IconUploadCloud,
  IconWorkshop,
  IconX,
} from '../components/ListingIcons'
import { useAnimatedNumber, useSlidingIndicator } from '../hooks/useListingMotion'
import {
  computeListingPayoutBreakdown,
  createListing,
  deleteListingMedia,
  DURATION_OPTIONS,
  extractListingApiError,
  extractListingErrorCode,
  FALLBACK_CATEGORIES,
  findNearestSriLankaDistrict,
  formatModerationFeedback,
  getDistrictLocations,
  getListingById,
  getPublicCategories,
  isListingRequestCanceled,
  resolveCategoryIconAsset,
  screenListingContent,
  SRI_LANKA_PROVINCES_FALLBACK,
  updateListing,
  uploadListingPhoto,
} from '../services/listingService'
import {
  findNearestSriLankaZone,
  getZonesWithinRadius,
  isWithinSriLankaBounds,
  MAX_SERVICE_AREAS,
  mergeRadiusZones,
  SRI_LANKA_ZONES,
} from '../services/sriLankaZones'
import styles from '../styles/providerListings.module.css'

const WIZARD_AUTO_BACKUP_KEY = 'wp03.wizardDraft.new.v1'

const STEP_LABELS = [
  { id: 1, title: 'Basic Details', pct: '25%', numericPct: 25 },
  { id: 2, title: 'Pricing & Add-Ons', pct: '50%', numericPct: 50 },
  { id: 3, title: 'Portfolio Media', pct: '75%', numericPct: 75 },
  { id: 4, title: 'Review & Publish', pct: '100%', numericPct: 100 },
]

const SCOPE_STARTER_TEMPLATES = [
  {
    id: 'included',
    label: "+ What's Included",
    snippet: 'Included in service:\n- On-site diagnostic check and upfront scope confirmation\n- ',
  },
  {
    id: 'tools',
    label: '+ Tools & Materials',
    snippet: 'Tools & materials:\n- Professional trade equipment and safety gear provided\n- ',
  },
  {
    id: 'prep',
    label: '+ Preparation & Warranty',
    snippet: 'Workmanship & customer note:\n- Clean post-service handover and service guarantee\n- ',
  },
]

const STEP_RING_RADIUS = 23
const STEP_RING_CIRCUMFERENCE = 2 * Math.PI * STEP_RING_RADIUS

function StepProgressRing({ numericPct, completed, active }) {
  if (completed) {
    return (
      <div className={styles.stepRingWrap}>
        <span className={styles.stepCircleDone} aria-hidden="true">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
            <polyline className={styles.stepCheckPath} points="20 6 9 17 4 12" />
          </svg>
        </span>
      </div>
    )
  }

  const clampedPct = Math.max(8, Math.min(100, Number(numericPct || 0)))
  const dashOffset = STEP_RING_CIRCUMFERENCE * (1 - clampedPct / 100)
  return (
    <div className={styles.stepRingWrap}>
      <svg className={styles.stepRingSvg} viewBox="0 0 56 56" aria-hidden="true">
        <circle
          cx="28"
          cy="28"
          r={STEP_RING_RADIUS}
          fill={active ? '#f0fdfa' : '#ffffff'}
          stroke={active ? '#d1fae5' : '#e2e8f0'}
          strokeWidth="4.5"
        />
        <circle
          cx="28"
          cy="28"
          r={STEP_RING_RADIUS}
          fill="none"
          stroke={active ? '#10b981' : '#94a3b8'}
          strokeWidth="4.5"
          strokeLinecap="round"
          strokeDasharray={STEP_RING_CIRCUMFERENCE}
          strokeDashoffset={dashOffset}
          transform="rotate(-90 28 28)"
        />
      </svg>
      <span className={styles.stepRingValue}>{Math.round(clampedPct)}%</span>
    </div>
  )
}

const ALLOWED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])
const MAX_IMAGE_BYTES = 5 * 1024 * 1024
const MAX_IMAGES = 8

function formatLkrDisplay(value) {
  const numeric = Number(value || 0)
  return `Rs. ${numeric.toLocaleString('en-LK', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

function getSubcategories(rootCategory) {
  if (!rootCategory) return []
  if (Array.isArray(rootCategory.subcategories)) return rootCategory.subcategories
  if (Array.isArray(rootCategory.children)) return rootCategory.children
  return []
}

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
    serviceAreas: getZonesWithinRadius(6.9271, 79.8612, 15, { fallbackLabel: 'Colombo' }),
    description: '',
    pricingModel: 'FIXED',
    basePrice: '2800',
    durationMin: 90,
    addOns: [],
    photos: [],
  }
}

function resolveEditCategoriesAndSelection(loadedCats, listing) {
  const targetId = listing?.categoryId
  if (!targetId) {
    const firstRoot = loadedCats[0]
    const firstActiveSub = getSubcategories(firstRoot).find((c) => c.active !== false)
    return {
      augmentedCategories: loadedCats,
      primaryCategoryId: firstRoot?.id || '',
      subcategoryId: firstActiveSub?.id || '',
    }
  }

  for (const root of loadedCats) {
    if (root.id === targetId) {
      return {
        augmentedCategories: loadedCats,
        primaryCategoryId: root.id,
        subcategoryId: '',
      }
    }
    const matchedSub = getSubcategories(root).find((sub) => sub.id === targetId)
    if (matchedSub) {
      return {
        augmentedCategories: loadedCats,
        primaryCategoryId: root.id,
        subcategoryId: matchedSub.id,
      }
    }
  }

  const parentId = listing?.parentCategoryId
  if (parentId) {
    const parentExists = loadedCats.some((root) => root.id === parentId)
    if (parentExists) {
      const inactiveSub = {
        id: targetId,
        name: listing.subcategoryName || listing.categoryName || 'Previously Selected Subcategory',
        parentId,
        commissionRate: 0.1,
        active: false,
      }
      const nextCats = loadedCats.map((root) => {
        if (root.id !== parentId) return root
        const mergedSubs = [inactiveSub, ...getSubcategories(root)]
        return {
          ...root,
          subcategories: mergedSubs,
          children: mergedSubs,
        }
      })
      return {
        augmentedCategories: nextCats,
        primaryCategoryId: parentId,
        subcategoryId: targetId,
      }
    }
  }

  const inactiveRoot = {
    id: targetId,
    name: listing?.categoryName || 'Previously Selected Category',
    slug: 'inactive-category',
    commissionRate: 0.1,
    active: false,
    subcategories: [],
    children: [],
  }
  return {
    augmentedCategories: [inactiveRoot, ...loadedCats],
    primaryCategoryId: targetId,
    subcategoryId: '',
  }
}

function cleanupUnsavedSessionMedia(photosToClean) {
  const candidates = (Array.isArray(photosToClean) ? photosToClean : []).filter(
    (item) => item?.uploadedInSession && item?.publicId,
  )
  candidates.forEach((item) => {
    deleteListingMedia(item.publicId).catch(() => {
      // Best-effort cloud cleanup for unsaved session uploads; never block wizard interaction
    })
  })
}

export function CreateEditListingWizardPage() {
  const { listingId } = useParams()
  const isEditMode = Boolean(listingId)
  const navigate = useNavigate()
  const toast = useToast()

  const fileInputRef = useRef(null)
  const descriptionRef = useRef(null)

  const [step, setStep] = useState(1)
  const [stepDirection, setStepDirection] = useState('forward')
  const [shakeTick, setShakeTick] = useState(0)
  const [isDirty, setIsDirty] = useState(false)
  const [showCancelConfirm, setShowCancelConfirm] = useState(false)
  const [recoverableDraft, setRecoverableDraft] = useState(null)

  const [form, setForm] = useState(createInitialFormState)
  const [categories, setCategories] = useState(FALLBACK_CATEGORIES)
  const [provinces, setProvinces] = useState(SRI_LANKA_PROVINCES_FALLBACK)
  const [loadingInit, setLoadingInit] = useState(true)
  const [submittingMode, setSubmittingMode] = useState('')
  const [errors, setErrors] = useState({})
  const [verificationBlockedMsg, setVerificationBlockedMsg] = useState('')
  const [editingListingMeta, setEditingListingMeta] = useState(null)
  const [gpsStatus, setGpsStatus] = useState('Default center: Colombo (6.9271° N, 79.8612° E)')

  const [showZoneInput, setShowZoneInput] = useState(false)
  const [newZoneText, setNewZoneText] = useState('')
  const [autoSyncZones, setAutoSyncZones] = useState(true)
  const [manualZones, setManualZones] = useState([])
  const [excludedAutoZones, setExcludedAutoZones] = useState([])

  const [showAddOnEditor, setShowAddOnEditor] = useState(false)
  const [editingAddOnId, setEditingAddOnId] = useState(null)
  const [pendingDeleteAddOnId, setPendingDeleteAddOnId] = useState(null)
  const [previewSelectedAddOnIds, setPreviewSelectedAddOnIds] = useState([])
  const [addOnDraft, setAddOnDraft] = useState({
    title: '',
    description: '',
    extraPrice: '1500',
    extraDurationMin: '30',
  })
  const [addOnError, setAddOnError] = useState('')

  const [imageUrlInput, setImageUrlInput] = useState('')
  const [uploadNotice, setUploadNotice] = useState('')
  const [isDragOverDropzone, setIsDragOverDropzone] = useState(false)
  const [draggedPhotoId, setDraggedPhotoId] = useState(null)

  const executionTrackRef = useRef(null)
  const executionIndicatorStyle = useSlidingIndicator(
    executionTrackRef,
    form.executionModel,
    `${step}-${form.executionModel}`,
  )

  const goToStep = useCallback(
    (nextStep) => {
      const clamped = Math.max(1, Math.min(4, nextStep))
      setStepDirection(clamped >= step ? 'forward' : 'backward')
      setStep(clamped)
    },
    [step],
  )

  const clearAutoBackup = useCallback(() => {
    try {
      window.sessionStorage?.removeItem(WIZARD_AUTO_BACKUP_KEY)
    } catch {
      // Ignore storage access issues
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    async function loadInitialData() {
      setLoadingInit(true)
      try {
        const [catRes, geoRes] = await Promise.all([
          getPublicCategories(controller.signal).catch((err) => {
            if (isListingRequestCanceled(err)) throw err
            return { categories: FALLBACK_CATEGORIES, isFallback: true }
          }),
          getDistrictLocations(controller.signal),
        ])
        if (!active) return

        const loadedCats =
          Array.isArray(catRes.categories) && catRes.categories.length > 0
            ? catRes.categories
            : FALLBACK_CATEGORIES
        const loadedProvinces = geoRes.provinces || SRI_LANKA_PROVINCES_FALLBACK
        setProvinces(loadedProvinces)

        if (isEditMode && listingId) {
          const { listing } = await getListingById(listingId, controller.signal)
          if (!active) return
          setEditingListingMeta({
            status: listing.status || '',
            categoryActive: listing.categoryActive !== false,
            moderationReason: listing.moderationReason || '',
            moderationFlags: Array.isArray(listing.moderationFlags) ? listing.moderationFlags : [],
          })

          const resolvedCat = resolveEditCategoriesAndSelection(loadedCats, listing)
          setCategories(resolvedCat.augmentedCategories)

          const mappedPhotos = (listing.images || [])
            .filter((imgUrl) => typeof imgUrl === 'string' && imgUrl.trim().length > 0)
            .map((imgUrl, idx) => ({
              id: `existing-photo-${idx}`,
              name: imgUrl.split('/').pop() || `portfolio_${idx + 1}.jpg`,
              httpsUrl: imgUrl,
              previewUrl: imgUrl,
              uploading: false,
              progress: 100,
            }))

          const savedLoc = listing.location || { longitude: 79.8612, latitude: 6.9271 }
          const savedRadius = Number(listing.coverageRadiusKm ?? 15)
          const savedAreas = Array.isArray(listing.serviceAreas) ? listing.serviceAreas : []
          const baselineAutoSet = new Set(
            getZonesWithinRadius(savedLoc.latitude, savedLoc.longitude, savedRadius, {
              maxZones: SRI_LANKA_ZONES.length,
              fallbackLabel: listing.baseCity || listing.district || 'Colombo',
            }).map((zone) => zone.toLowerCase()),
          )
          const preservedCustomZones = savedAreas.filter(
            (area) => !baselineAutoSet.has(String(area).trim().toLowerCase()),
          )

          setManualZones(preservedCustomZones)
          setExcludedAutoZones([])
          setIsDirty(false)
          setForm({
            title: listing.title || '',
            primaryCategoryId: resolvedCat.primaryCategoryId,
            subcategoryId: resolvedCat.subcategoryId,
            executionModel: listing.executionModel || 'ON_SITE',
            district: listing.district || 'Colombo',
            baseCity: listing.baseCity || listing.district || '',
            location: savedLoc,
            coverageRadiusKm: savedRadius,
            serviceAreas: savedAreas,
            description: listing.description || '',
            pricingModel: listing.pricingModel === 'HOURLY' ? 'HOURLY' : 'FIXED',
            basePrice: String(listing.basePrice ?? '2800'),
            durationMin: Number(listing.durationMin ?? 90),
            addOns: Array.isArray(listing.addOns) ? listing.addOns : [],
            photos: mappedPhotos,
          })
        } else {
          setCategories(loadedCats)
          setEditingListingMeta(null)
          setManualZones([])
          setExcludedAutoZones([])
          setIsDirty(false)

          const firstActiveRoot = loadedCats.find((c) => c.active !== false) || loadedCats[0]
          const firstActiveSub =
            getSubcategories(firstActiveRoot).find((sub) => sub.active !== false) || null
          if (firstActiveRoot) {
            setForm((prev) => {
              const rootStillExists = loadedCats.some((c) => c.id === prev.primaryCategoryId)
              if (rootStillExists) {
                const matchedRoot = loadedCats.find((c) => c.id === prev.primaryCategoryId)
                const validSub =
                  getSubcategories(matchedRoot).find(
                    (sub) => sub.id === prev.subcategoryId && sub.active !== false,
                  ) || getSubcategories(matchedRoot).find((sub) => sub.active !== false)
                return {
                  ...prev,
                  subcategoryId: validSub?.id || '',
                }
              }
              return {
                ...prev,
                primaryCategoryId: firstActiveRoot.id,
                subcategoryId: firstActiveSub?.id || '',
              }
            })
          }

          try {
            const rawDraft = window.sessionStorage?.getItem(WIZARD_AUTO_BACKUP_KEY)
            if (rawDraft) {
              const parsed = JSON.parse(rawDraft)
              const hasContent =
                parsed?.form &&
                (String(parsed.form.title || '').trim().length > 0 ||
                  String(parsed.form.description || '').trim().length > 0 ||
                  (Array.isArray(parsed.form.addOns) && parsed.form.addOns.length > 0) ||
                  (Array.isArray(parsed.form.photos) && parsed.form.photos.length > 0))
              if (hasContent) {
                setRecoverableDraft(parsed)
              }
            }
          } catch {
            // Ignore malformed storage data
          }
        }
      } catch (error) {
        if (isListingRequestCanceled(error)) {
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

  useEffect(() => {
    if (isEditMode || !isDirty || loadingInit) return
    try {
      const persistedPhotos = (form.photos || [])
        .filter((photo) => !photo.uploading && /^https?:\/\//i.test(photo.httpsUrl || ''))
        .map((photo) => ({
          id: photo.id,
          name: photo.name,
          httpsUrl: photo.httpsUrl,
          previewUrl: photo.httpsUrl,
          uploading: false,
          progress: 100,
        }))

      const snapshot = {
        savedAt: new Date().toISOString(),
        step,
        form: {
          ...form,
          photos: persistedPhotos,
        },
      }
      window.sessionStorage?.setItem(WIZARD_AUTO_BACKUP_KEY, JSON.stringify(snapshot))
    } catch {
      // Storage quota or unavailable
    }
  }, [form, isDirty, isEditMode, loadingInit, step])

  useEffect(() => {
    const onKeyDown = (event) => {
      if (event.key === 'Escape' && showCancelConfirm && !submittingMode) {
        setShowCancelConfirm(false)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [showCancelConfirm, submittingMode])

  const handleResumeSavedDraft = () => {
    if (!recoverableDraft?.form) return
    setForm((prev) => ({
      ...prev,
      ...recoverableDraft.form,
      photos: Array.isArray(recoverableDraft.form.photos) ? recoverableDraft.form.photos : [],
      addOns: Array.isArray(recoverableDraft.form.addOns) ? recoverableDraft.form.addOns : [],
    }))
    if (recoverableDraft.step && Number.isFinite(Number(recoverableDraft.step))) {
      goToStep(Number(recoverableDraft.step))
    }
    setIsDirty(true)
    setRecoverableDraft(null)
    toast.info('Restored your unsaved listing progress.', { title: 'Draft Restored' })
  }

  const handleDismissSavedDraft = () => {
    cleanupUnsavedSessionMedia(recoverableDraft?.form?.photos)
    clearAutoBackup()
    setRecoverableDraft(null)
  }

  const selectedPrimaryCategory = useMemo(
    () => categories.find((cat) => cat.id === form.primaryCategoryId) || categories[0],
    [categories, form.primaryCategoryId],
  )

  const availableSubcategories = useMemo(
    () => getSubcategories(selectedPrimaryCategory),
    [selectedPrimaryCategory],
  )

  const selectedSubcategory = useMemo(
    () => availableSubcategories.find((sub) => sub.id === form.subcategoryId),
    [availableSubcategories, form.subcategoryId],
  )

  const effectiveCategoryId = form.subcategoryId || form.primaryCategoryId

  const effectiveCommissionRate = useMemo(() => {
    if (selectedSubcategory && Number.isFinite(Number(selectedSubcategory.commissionRate))) {
      return Number(selectedSubcategory.commissionRate)
    }
    if (selectedPrimaryCategory && Number.isFinite(Number(selectedPrimaryCategory.commissionRate))) {
      return Number(selectedPrimaryCategory.commissionRate)
    }
    return 0.1
  }, [selectedPrimaryCategory, selectedSubcategory])

  const selectedCategoryIcon = useMemo(
    () => resolveCategoryIconAsset(selectedSubcategory || selectedPrimaryCategory),
    [selectedPrimaryCategory, selectedSubcategory],
  )

  const payoutBreakdown = useMemo(
    () =>
      computeListingPayoutBreakdown({
        basePrice: form.basePrice,
        addOns: form.addOns,
        selectedAddOnIds: previewSelectedAddOnIds,
        commissionRate: effectiveCommissionRate,
      }),
    [effectiveCommissionRate, form.addOns, form.basePrice, previewSelectedAddOnIds],
  )

  const animatedNetPayout = useAnimatedNumber(payoutBreakdown.providerNetPayout, 260)

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

  const manualZoneKeySet = useMemo(
    () => new Set(manualZones.map((zone) => zone.toLowerCase())),
    [manualZones],
  )

  const suggestedDistrictTowns = useMemo(() => {
    const selectedSet = new Set((form.serviceAreas || []).map((z) => z.toLowerCase()))
    const inDistrict = SRI_LANKA_ZONES.filter(
      (zone) => zone.district === form.district && !selectedSet.has(zone.name.toLowerCase()),
    )
    const others = SRI_LANKA_ZONES.filter(
      (zone) => zone.district !== form.district && !selectedSet.has(zone.name.toLowerCase()),
    )
    return {
      quickPills: inDistrict.slice(0, 5),
      allOptions: [...inDistrict, ...others].slice(0, 40),
    }
  }, [form.district, form.serviceAreas])

  const zoneSyncPreview = useMemo(() => {
    const lat = form.location?.latitude ?? 6.9271
    const lng = form.location?.longitude ?? 79.8612
    const autoZones = getZonesWithinRadius(lat, lng, form.coverageRadiusKm, {
      maxZones: SRI_LANKA_ZONES.length,
      fallbackLabel: form.baseCity || form.district,
    })
    return mergeRadiusZones({
      autoZones,
      manualZones,
      excludedAutoZones,
      maxZones: MAX_SERVICE_AREAS,
    })
  }, [
    form.location,
    form.coverageRadiusKm,
    form.baseCity,
    form.district,
    manualZones,
    excludedAutoZones,
  ])

  const persistedPhotos = useMemo(
    () => form.photos.filter((photo) => !photo.uploading && Boolean(photo.httpsUrl)),
    [form.photos],
  )

  const anyPhotoUploading = useMemo(
    () => form.photos.some((photo) => Boolean(photo.uploading)),
    [form.photos],
  )

  const stepLivePercentMap = useMemo(() => {
    const titleOk = form.title.trim().length >= 10 && form.title.trim().length <= 120
    const catOk =
      Boolean(effectiveCategoryId) &&
      selectedPrimaryCategory?.active !== false &&
      (!selectedSubcategory || selectedSubcategory.active !== false)
    const geoOk = Boolean(form.district) && Boolean(form.baseCity.trim()) && form.serviceAreas.length > 0
    const descOk = form.description.trim().length >= 50
    const s1Checks = [titleOk, catOk, geoOk, descOk].filter(Boolean).length

    const priceOk = Number.isFinite(Number(form.basePrice)) && Number(form.basePrice) >= 100
    const durOk = Number(form.durationMin) >= 15
    const s2Checks = [priceOk, durOk].filter(Boolean).length

    const s3Ok = persistedPhotos.length >= 1 && !anyPhotoUploading

    return {
      1: Math.max(25, Math.round((s1Checks / 4) * 100)),
      2: Math.max(50, Math.round((s2Checks / 2) * 100)),
      3: s3Ok ? 100 : 50,
      4: 100,
    }
  }, [
    anyPhotoUploading,
    effectiveCategoryId,
    form.baseCity,
    form.basePrice,
    form.description,
    form.district,
    form.durationMin,
    form.serviceAreas.length,
    form.title,
    persistedPhotos.length,
    selectedPrimaryCategory,
    selectedSubcategory,
  ])

  const applyGeoZoneSync = useCallback(
    (
      nextLocation,
      nextRadiusKm,
      nextFallbackLabel,
      { nextManual = manualZones, nextExcluded = excludedAutoZones } = {},
    ) => {
      const lat = nextLocation?.latitude ?? 6.9271
      const lng = nextLocation?.longitude ?? 79.8612
      const autoZones = getZonesWithinRadius(lat, lng, nextRadiusKm, {
        maxZones: SRI_LANKA_ZONES.length,
        fallbackLabel: nextFallbackLabel,
      })
      return mergeRadiusZones({
        autoZones,
        manualZones: nextManual,
        excludedAutoZones: nextExcluded,
        maxZones: MAX_SERVICE_AREAS,
      }).serviceAreas
    },
    [manualZones, excludedAutoZones],
  )

  const updateField = useCallback((field, value) => {
    setIsDirty(true)
    setForm((prev) => ({ ...prev, [field]: value }))
    setErrors((prev) => {
      if (!prev[field]) return prev
      const next = { ...prev }
      delete next[field]
      return next
    })
  }, [])

  const scrollAndFocusFirstError = useCallback((nextErrors) => {
    const selectorOrder = [
      ['title', '#listing-title'],
      ['categoryId', '#primary-category'],
      ['district', '#district-select'],
      ['baseCity', '#base-city-input'],
      ['serviceAreas', '#zone-add-trigger'],
      ['description', '#listing-description'],
      ['basePrice', '#base-price'],
      ['durationMin', '#duration-select'],
      ['photos', '#select-photos-btn'],
    ]
    for (const [key, selector] of selectorOrder) {
      if (nextErrors[key]) {
        const el = typeof document !== 'undefined' ? document.querySelector(selector) : null
        if (el) {
          el.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
          el.focus?.({ preventScroll: true })
        }
        break
      }
    }
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
        } else if (selectedPrimaryCategory && selectedPrimaryCategory.active === false) {
          nextErrors.categoryId = 'Selected primary category is currently inactive. Choose an active category.'
        } else if (selectedSubcategory && selectedSubcategory.active === false) {
          nextErrors.categoryId = 'Selected subcategory is currently inactive. Choose an active category.'
        }

        if (!form.district) {
          nextErrors.district = 'District is required.'
        }

        if (!form.baseCity || !form.baseCity.trim()) {
          nextErrors.baseCity = 'Dispatch neighbourhood / base city is required when publishing.'
        }

        if (!Array.isArray(form.serviceAreas) || form.serviceAreas.length === 0) {
          nextErrors.serviceAreas = 'Add at least 1 covered city or zone before publishing.'
        }

        if (form.description.trim().length < 50) {
          nextErrors.description = 'Detailed service scope must be at least 50 characters to publish.'
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
        if (anyPhotoUploading) {
          nextErrors.photos = 'Please wait for your selected photos to finish uploading.'
        } else if (persistedPhotos.length === 0) {
          nextErrors.photos = 'Upload or paste at least 1 portfolio photo before publishing.'
        }
      }

      setErrors(nextErrors)
      const isValid = Object.keys(nextErrors).length === 0
      if (!isValid) {
        setShakeTick((tick) => tick + 1)
        window.requestAnimationFrame?.(() => scrollAndFocusFirstError(nextErrors))
      }
      return isValid
    },
    [
      anyPhotoUploading,
      effectiveCategoryId,
      form,
      persistedPhotos.length,
      scrollAndFocusFirstError,
      selectedPrimaryCategory,
      selectedSubcategory,
    ],
  )

  const handleDistrictChange = (districtName) => {
    setIsDirty(true)
    const matched = flatDistricts.find((item) => item.name === districtName)
    if (autoSyncZones) {
      setExcludedAutoZones([])
    }
    setForm((prev) => {
      const nextLocation = matched
        ? { longitude: matched.longitude, latitude: matched.latitude }
        : prev.location
      const nextBaseCity = prev.baseCity || districtName
      const nextAreas = autoSyncZones
        ? applyGeoZoneSync(nextLocation, prev.coverageRadiusKm, districtName, {
            nextExcluded: [],
          })
        : prev.serviceAreas
      return {
        ...prev,
        district: districtName,
        baseCity: nextBaseCity,
        location: nextLocation,
        serviceAreas: nextAreas,
      }
    })
    if (autoSyncZones) {
      setErrors((prev) => {
        if (!prev.serviceAreas) return prev
        const next = { ...prev }
        delete next.serviceAreas
        return next
      })
    }
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
        if (!isWithinSriLankaBounds(latitude, longitude)) {
          setGpsStatus(
            `Detected GPS (${latitude.toFixed(4)}° N, ${longitude.toFixed(4)}° E) is outside Sri Lanka (VPN or desktop IP location detected). Please select your district or click on the map to pin your location.`,
          )
          toast.warning(
            'Browser GPS returned coordinates outside Sri Lanka (likely a VPN or IP-based location). Please select your district or click directly on the map.',
            { title: 'Location Outside Sri Lanka' },
          )
          return
        }

        setIsDirty(true)
        const nearest = findNearestSriLankaDistrict(latitude, longitude, provinces)
        const nearestZone = findNearestSriLankaZone(latitude, longitude)
        const resolvedBaseCity = nearestZone?.name || nearest.name
        const nextLocation = { longitude, latitude }
        if (autoSyncZones) {
          setExcludedAutoZones([])
        }
        setForm((prev) => ({
          ...prev,
          district: nearest.name,
          baseCity: resolvedBaseCity,
          location: nextLocation,
          serviceAreas: autoSyncZones
            ? applyGeoZoneSync(nextLocation, prev.coverageRadiusKm, resolvedBaseCity, {
                nextExcluded: [],
              })
            : prev.serviceAreas,
        }))
        if (autoSyncZones) {
          setErrors((prev) => {
            if (!prev.serviceAreas) return prev
            const next = { ...prev }
            delete next.serviceAreas
            return next
          })
        }
        setGpsStatus(
          `Auto-detected via Browser GPS -> ${resolvedBaseCity}, ${nearest.name} (${latitude.toFixed(4)}° N, ${longitude.toFixed(4)}° E)`,
        )
      },
      () => {
        const fallbackDist = findNearestSriLankaDistrict(6.9271, 79.8612, provinces)
        handleDistrictChange(fallbackDist.name)
        setGpsStatus(`Location permission denied; defaulted to ${fallbackDist.name} District.`)
      },
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 0 },
    )
  }

  const handleCoverageRadiusChange = (nextRadiusKm) => {
    setIsDirty(true)
    const numericRadius = Number(nextRadiusKm)
    setForm((prev) => {
      const nextAreas = autoSyncZones
        ? applyGeoZoneSync(prev.location, numericRadius, prev.baseCity || prev.district)
        : prev.serviceAreas
      return {
        ...prev,
        coverageRadiusKm: numericRadius,
        serviceAreas: nextAreas,
      }
    })
    if (autoSyncZones) {
      setErrors((prev) => {
        if (!prev.serviceAreas) return prev
        const next = { ...prev }
        delete next.serviceAreas
        return next
      })
    }
  }

  const handleMapCoordinateChange = (coords) => {
    setIsDirty(true)
    setForm((prev) => {
      const nextAreas = autoSyncZones
        ? applyGeoZoneSync(coords, prev.coverageRadiusKm, prev.baseCity || prev.district)
        : prev.serviceAreas
      return {
        ...prev,
        location: coords,
        serviceAreas: nextAreas,
      }
    })
    if (autoSyncZones) {
      setErrors((prev) => {
        if (!prev.serviceAreas) return prev
        const next = { ...prev }
        delete next.serviceAreas
        return next
      })
    }
    setGpsStatus(
      `Pinned on map: ${coords.latitude.toFixed(4)}° N, ${coords.longitude.toFixed(4)}° E`,
    )
  }

  const addZoneByName = (rawName) => {
    const cleaned = String(rawName || '').trim()
    if (!cleaned) return false
    if (form.serviceAreas.length >= MAX_SERVICE_AREAS) {
      toast.warning(`Maximum ${MAX_SERVICE_AREAS} covered zones allowed.`, { title: 'Limit Reached' })
      return false
    }
    const cleanedKey = cleaned.toLowerCase()
    if (!form.serviceAreas.some((area) => area.toLowerCase() === cleanedKey)) {
      updateField('serviceAreas', [...form.serviceAreas, cleaned])
    }
    setManualZones((prev) =>
      prev.some((zone) => zone.toLowerCase() === cleanedKey) ? prev : [...prev, cleaned],
    )
    setExcludedAutoZones((prev) => prev.filter((zone) => zone.toLowerCase() !== cleanedKey))
    return true
  }

  const handleAddZone = () => {
    if (addZoneByName(newZoneText)) {
      setNewZoneText('')
      setShowZoneInput(false)
    }
  }

  const handleRemoveZone = (areaToRemove) => {
    const removeKey = String(areaToRemove || '').trim().toLowerCase()
    updateField(
      'serviceAreas',
      form.serviceAreas.filter((item) => item.toLowerCase() !== removeKey),
    )
    const isManual = manualZones.some((zone) => zone.toLowerCase() === removeKey)
    if (isManual) {
      setManualZones((prev) => prev.filter((zone) => zone.toLowerCase() !== removeKey))
    } else {
      setExcludedAutoZones((prev) =>
        prev.some((zone) => zone.toLowerCase() === removeKey) ? prev : [...prev, areaToRemove],
      )
    }
  }

  const handleToggleAutoSyncZones = (checked) => {
    setAutoSyncZones(checked)
    if (checked) {
      setIsDirty(true)
      setForm((prev) => ({
        ...prev,
        serviceAreas: applyGeoZoneSync(
          prev.location,
          prev.coverageRadiusKm,
          prev.baseCity || prev.district,
        ),
      }))
      setErrors((prev) => {
        if (!prev.serviceAreas) return prev
        const next = { ...prev }
        delete next.serviceAreas
        return next
      })
    }
  }

  const handleResetSyncZones = () => {
    setIsDirty(true)
    setAutoSyncZones(true)
    setExcludedAutoZones([])
    setForm((prev) => ({
      ...prev,
      serviceAreas: applyGeoZoneSync(
        prev.location,
        prev.coverageRadiusKm,
        prev.baseCity || prev.district,
        { nextExcluded: [] },
      ),
    }))
    setErrors((prev) => {
      if (!prev.serviceAreas) return prev
      const next = { ...prev }
      delete next.serviceAreas
      return next
    })
  }

  const handleInsertScopeStarter = (snippet) => {
    const current = form.description
    const prefix = current && !current.endsWith('\n') ? `${current}\n\n` : current
    const nextValue = `${prefix}${snippet}`.slice(0, 3000)
    updateField('description', nextValue)
    window.requestAnimationFrame?.(() => {
      if (descriptionRef.current) {
        descriptionRef.current.focus()
        descriptionRef.current.selectionStart = nextValue.length
        descriptionRef.current.selectionEnd = nextValue.length
      }
    })
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

  const handleConfirmDeleteAddOn = (addOnId) => {
    updateField(
      'addOns',
      form.addOns.filter((item) => item.addOnId !== addOnId),
    )
    setPreviewSelectedAddOnIds((prev) => prev.filter((id) => id !== addOnId))
    setPendingDeleteAddOnId(null)
  }

  const handleTogglePreviewAddOn = (addOnId) => {
    setPreviewSelectedAddOnIds((prev) =>
      prev.includes(addOnId) ? prev.filter((id) => id !== addOnId) : [...prev, addOnId],
    )
  }

  const handleFilesSelected = async (fileList) => {
    const files = Array.from(fileList || [])
    if (files.length === 0) return

    setUploadNotice('')
    const remaining = MAX_IMAGES - form.photos.length
    if (remaining <= 0) {
      setUploadNotice('Upload failed: Maximum 8 images allowed per listing.')
      toast.error('Maximum 8 images allowed. Remove an image to upload another.', { title: 'Upload Failed' })
      return
    }

    const validFiles = []
    for (const file of files) {
      if (validFiles.length >= remaining) break
      if (!ALLOWED_IMAGE_TYPES.has(file.type)) {
        setUploadNotice(`Skipped "${file.name}": Only JPG, PNG, and WEBP formats are allowed.`)
        continue
      }
      if (file.size > MAX_IMAGE_BYTES) {
        setUploadNotice(`Upload failed for "${file.name}": Each image must be under 5 MB.`)
        toast.error(`"${file.name}" exceeds the 5 MB image limit.`, { title: 'Upload Failed' })
        continue
      }
      validFiles.push(file)
    }

    if (validFiles.length === 0) return

    setIsDirty(true)
    const pendingEntries = validFiles.map((file, idx) => {
      const objectUrl =
        typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function'
          ? URL.createObjectURL(file)
          : ''
      return {
        file,
        tempId: `photo-upload-${Date.now()}-${idx}-${Math.random().toString(16).slice(2, 6)}`,
        previewUrl: objectUrl,
      }
    })

    setForm((prev) => ({
      ...prev,
      photos: [
        ...prev.photos,
        ...pendingEntries.map((entry) => ({
          id: entry.tempId,
          name: entry.file.name,
          httpsUrl: '',
          previewUrl: entry.previewUrl,
          uploading: true,
          progress: 12,
        })),
      ],
    }))
    setErrors((prev) => {
      if (!prev.photos) return prev
      const next = { ...prev }
      delete next.photos
      return next
    })

    await Promise.all(
      pendingEntries.map(async (entry) => {
        try {
          const { uploaded } = await uploadListingPhoto(entry.file, {
            onProgress: (pct) => {
              setForm((prev) => ({
                ...prev,
                photos: prev.photos.map((photo) =>
                  photo.id === entry.tempId ? { ...photo, progress: Math.max(15, pct) } : photo,
                ),
              }))
            },
          })

          setForm((prev) => ({
            ...prev,
            photos: prev.photos.map((photo) =>
              photo.id === entry.tempId
                ? {
                    ...photo,
                    name: uploaded.fileName || entry.file.name,
                    publicId: uploaded.publicId || '',
                    uploadedInSession: true,
                    httpsUrl: uploaded.secureUrl,
                    previewUrl: entry.previewUrl || uploaded.secureUrl,
                    uploading: false,
                    progress: 100,
                  }
                : photo,
            ),
          }))
        } catch (error) {
          const errMsg = extractListingApiError(
            error,
            `Failed to upload "${entry.file.name}". You can paste an HTTPS image link below instead.`,
          )
          setForm((prev) => ({
            ...prev,
            photos: prev.photos.filter((photo) => photo.id !== entry.tempId),
          }))
          setUploadNotice(errMsg)
          toast.error(errMsg, { title: 'Photo Upload Failed' })
        }
      }),
    )
  }

  const handleAddImageUrl = () => {
    const trimmed = imageUrlInput.trim()
    if (!trimmed) return
    if (form.photos.length >= MAX_IMAGES) {
      toast.error('Maximum 8 images allowed per listing.', { title: 'Upload Limit Reached' })
      return
    }
    if (!/^https?:\/\/.+/i.test(trimmed)) {
      setUploadNotice('Please enter a valid image link starting with https://.')
      return
    }
    if (!/\.(jpg|jpeg|png|webp)(\?.*)?$/i.test(trimmed)) {
      setUploadNotice('Image link must end in .jpg, .jpeg, .png, or .webp.')
      return
    }
    updateField('photos', [
      ...form.photos,
      {
        id: `photo-url-${Date.now()}`,
        name: trimmed.split('/').pop() || 'portfolio-image.jpg',
        httpsUrl: trimmed,
        previewUrl: trimmed,
        uploading: false,
        progress: 100,
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

  const handleMovePhoto = (photoId, delta) => {
    const currentIdx = form.photos.findIndex((item) => item.id === photoId)
    if (currentIdx === -1) return
    const targetIdx = currentIdx + delta
    if (targetIdx < 0 || targetIdx >= form.photos.length) return
    const nextPhotos = [...form.photos]
    const [moved] = nextPhotos.splice(currentIdx, 1)
    nextPhotos.splice(targetIdx, 0, moved)
    updateField('photos', nextPhotos)
  }

  const handleDropReorderPhoto = (targetPhotoId) => {
    if (!draggedPhotoId || draggedPhotoId === targetPhotoId) {
      setDraggedPhotoId(null)
      return
    }
    const fromIdx = form.photos.findIndex((item) => item.id === draggedPhotoId)
    const toIdx = form.photos.findIndex((item) => item.id === targetPhotoId)
    if (fromIdx === -1 || toIdx === -1) {
      setDraggedPhotoId(null)
      return
    }
    const nextPhotos = [...form.photos]
    const [moved] = nextPhotos.splice(fromIdx, 1)
    nextPhotos.splice(toIdx, 0, moved)
    updateField('photos', nextPhotos)
    setDraggedPhotoId(null)
  }

  const handleRemovePhoto = (photoId) => {
    const target = form.photos.find((item) => item.id === photoId)
    updateField(
      'photos',
      form.photos.filter((item) => item.id !== photoId),
    )
    if (target) {
      cleanupUnsavedSessionMedia([target])
    }
  }

  const handleCancelWizard = () => {
    if (isDirty) {
      setShowCancelConfirm(true)
      return
    }
    navigate('/provider/listings')
  }

  const handleConfirmDiscardAndLeave = () => {
    cleanupUnsavedSessionMedia(form.photos)
    setShowCancelConfirm(false)
    clearAutoBackup()
    navigate('/provider/listings')
  }

  const buildPayload = (saveAsDraft) => {
    const imageUrls = persistedPhotos.map((photo) => photo.httpsUrl)
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
    setVerificationBlockedMsg('')
    if (!saveAsDraft) {
      const s1 = validateStep(1)
      if (!s1) {
        goToStep(1)
        return
      }
      const s2 = validateStep(2)
      if (!s2) {
        goToStep(2)
        return
      }
      const s3 = validateStep(3)
      if (!s3) {
        goToStep(3)
        return
      }
    }

    setSubmittingMode(saveAsDraft ? 'draft' : 'publish')
    setShowCancelConfirm(false)
    try {
      const payload = buildPayload(saveAsDraft)
      const result = isEditMode
        ? await updateListing(listingId, payload, categories)
        : await createListing(payload, categories)

      clearAutoBackup()
      const saved = result.listing
      if (saveAsDraft) {
        toast.success(`"${saved.title}" saved as a private draft.`, { title: 'Draft Saved' })
      } else if (saved.status === 'FLAGGED') {
        const hasPolicyFlags = Array.isArray(saved.moderationFlags) && saved.moderationFlags.length > 0
        if (hasPolicyFlags) {
          toast.warning(
            'Your listing was sent for moderator review because it appears to contain contact details or external links.',
            { title: 'Listing Under Review' },
          )
        } else {
          toast.info(
            `"${saved.title}" was updated and resubmitted to the moderator review queue.`,
            { title: 'Resubmitted for Review' },
          )
        }
      } else {
        toast.success(`"${saved.title}" is now live in the marketplace.`, { title: 'Listing Published' })
      }

      navigate('/provider/listings')
    } catch (error) {
      const code = extractListingErrorCode(error)
      const msg = extractListingApiError(error, 'Failed to save service listing.')
      if (!saveAsDraft && (code === 'PROVIDER_NOT_VERIFIED' || code === 'PROVIDER_VERIFICATION_UNAVAILABLE')) {
        setVerificationBlockedMsg(msg)
      }
      toast.error(msg, {
        title: 'Submission Failed',
      })
    } finally {
      setSubmittingMode('')
    }
  }

  const handleNextStep = () => {
    if (validateStep(step)) {
      goToStep(Math.min(4, step + 1))
    }
  }

  const canSaveAsDraft =
    !isEditMode || !editingListingMeta?.status || editingListingMeta.status === 'DRAFT'
  const isModeratedEdit =
    isEditMode &&
    (editingListingMeta?.status === 'FLAGGED' || editingListingMeta?.status === 'REJECTED')
  const isSelectedCategoryInactive =
    selectedPrimaryCategory?.active === false || selectedSubcategory?.active === false

  const selectedDurationLabel =
    DURATION_OPTIONS.find((opt) => opt.value === Number(form.durationMin))?.label ||
    `${form.durationMin} Mins`

  const trimmedDescriptionLength = form.description.trim().length
  const scopeReadinessPct = Math.min(100, Math.round((trimmedDescriptionLength / 50) * 100))
  const scopeRemainingChars = Math.max(0, 50 - trimmedDescriptionLength)

  const previewCoverSrc = form.photos[0]?.previewUrl || form.photos[0]?.httpsUrl || ''

  const stepTransitionClass =
    stepDirection === 'backward' ? styles.stepPaneBackward : styles.stepPaneForward

  if (loadingInit) {
    return <WizardLoadingSkeleton />
  }

  return (
    <div className={styles.wizardWrap}>
      {/* Breadcrumb & Header */}
      <div>
        <div className={styles.breadcrumb}>
          <button
            type="button"
            className={styles.breadcrumbLinkBtn}
            onClick={handleCancelWizard}
          >
            Service Listings
          </button>
          <span>›</span>
          <span className={styles.breadcrumbCurrent}>
            {isEditMode ? 'Edit Listing' : 'Create New Listing'} (Step {step}: {STEP_LABELS[step - 1].title})
          </span>
        </div>

        <div className={styles.pageHeader} style={{ marginTop: 8 }}>
          <div>
            <h1 className={styles.pageTitle}>
              {isEditMode ? 'Edit Service Listing' : 'Create Service Listing'}
            </h1>
            <p className={styles.pageSubtitle}>
              Step {step} of 4 · Complete each guided step to publish a clear, customer-ready service package.
            </p>
          </div>

          {canSaveAsDraft && (
            <button
              type="button"
              className={styles.outlineBtn}
              disabled={Boolean(submittingMode)}
              onClick={() => handleSubmitListing(true)}
            >
              {submittingMode === 'draft' ? 'Saving Draft...' : 'Save Draft'}
            </button>
          )}
        </div>
      </div>

      {/* Auto-Saved Local Draft Recovery Banner */}
      {recoverableDraft && !isEditMode && (
        <div className={styles.draftRecoveryBanner} role="region" aria-label="Unsaved draft recovery">
          <div className={styles.inlineIconText}>
            <IconRefresh size={16} className={styles.iconTeal} />
            <span>
              <strong>Unsaved listing draft found:</strong> Would you like to continue editing{' '}
              {recoverableDraft.form?.title ? `"${recoverableDraft.form.title}"` : 'your previous draft'}?
            </span>
          </div>
          <div className={styles.controlsGroup}>
            <button type="button" className={styles.outlineBtn} onClick={handleDismissSavedDraft}>
              Start Fresh
            </button>
            <button type="button" className={styles.primaryBtn} onClick={handleResumeSavedDraft}>
              Resume Draft
            </button>
          </div>
        </div>
      )}

      {/* High-Fi Figma Emerald 4-Step Progress Stepper */}
      <nav className={styles.stepperCard} aria-label="Listing creation progress">
        {STEP_LABELS.map((item, idx) => {
          const isCompleted = item.id < step
          const isActive = item.id === step
          const isNextUp = item.id === step + 1
          const hasNextConnector = idx < STEP_LABELS.length - 1
          const livePct = isActive ? stepLivePercentMap[item.id] : item.numericPct
          const connectorFillPct = item.id < step ? 100 : item.id === step ? Math.round(livePct * 0.65) : 0

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
                className={`${styles.stepBtn} ${isActive ? styles.stepBtnCurrent : ''}`}
                aria-current={isActive ? 'step' : undefined}
                onClick={() => {
                  if (item.id <= step || validateStep(step)) {
                    goToStep(item.id)
                  }
                }}
              >
                <StepProgressRing numericPct={livePct} completed={isCompleted} active={isActive} />
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

      {/* Moderation Feedback Banner when editing FLAGGED or REJECTED listings */}
      {isModeratedEdit && (
        <div className={styles.policyWarningBanner} role="alert">
          <div className={styles.moderationBannerBody}>
            <span className={styles.inlineIconText}>
              <IconShieldAlert size={17} />
              <span>
                <strong>
                  {editingListingMeta.status === 'REJECTED'
                    ? 'Rejected by Moderator:'
                    : 'Under Moderator Review:'}
                </strong>{' '}
                {formatModerationFeedback(
                  editingListingMeta.moderationReason,
                  editingListingMeta.moderationFlags,
                )}
              </span>
            </span>
            <span className={styles.moderationBannerHint}>
              Fix the issue below and save your changes to send this listing back for review.
            </span>
          </div>
        </div>
      )}

      {/* Provider Verification Gate Notice */}
      {verificationBlockedMsg && (
        <div className={styles.policyWarningBanner} role="alert">
          <span className={styles.inlineIconText}>
            <IconShieldAlert size={17} />
            <span>
              <strong>Account Verification Required:</strong> {verificationBlockedMsg}
              {canSaveAsDraft
                ? ' You can save this listing as a Draft now and publish once your account is verified.'
                : ' Complete your account verification before reactivating this listing.'}
            </span>
          </span>
          <div className={styles.controlsGroup}>
            {canSaveAsDraft && (
              <button
                type="button"
                className={styles.outlineBtn}
                disabled={Boolean(submittingMode)}
                onClick={() => handleSubmitListing(true)}
              >
                Save as Draft Instead
              </button>
            )}
            <button
              type="button"
              className={styles.primaryBtn}
              onClick={() => navigate('/provider/verification')}
            >
              <span>Go to Verification</span>
              <IconArrowRight size={14} />
            </button>
          </div>
        </div>
      )}

      {/* STEP 1: BASIC DETAILS & GEOGRAPHY */}
      {step === 1 && (
        <div key={`step-1-${shakeTick > 0 ? 'validated' : 'init'}`} className={stepTransitionClass}>
          <div className={styles.wizardStepGrid}>
            <div className={styles.wizardStackCol}>
              <section className={styles.sectionCard}>
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
                    className={`${styles.textInput} ${
                      errors.title ? `${styles.inputError} ${styles.fieldErrorShake}` : ''
                    }`}
                    placeholder="Emergency Pipe Leak Repair & Waterline Diagnostic"
                    value={form.title}
                    onChange={(event) => updateField('title', event.target.value)}
                  />
                  {errors.title ? (
                    <p className={styles.fieldErrorText}>{errors.title}</p>
                  ) : (
                    <p className={styles.fieldHelper}>
                      Keep it clear and outcome-driven to attract qualified local customers.
                    </p>
                  )}
                </div>

                {isSelectedCategoryInactive && (
                  <div className={styles.policyCalloutBox} role="alert">
                    <span className={styles.inlineIconText}>
                      <IconAlertTriangle size={15} />
                      <span>
                        Your current category was deactivated by an administrator. Select an active category below to
                        re-enable public bookings.
                      </span>
                    </span>
                  </div>
                )}

                <div className={styles.fieldGridTwo}>
                  <div className={styles.fieldGroup}>
                    <label htmlFor="primary-category" className={styles.fieldLabel}>
                      Primary Category *
                    </label>
                    <select
                      id="primary-category"
                      className={`${styles.selectInput} ${
                        errors.categoryId ? `${styles.inputError} ${styles.fieldErrorShake}` : ''
                      }`}
                      value={form.primaryCategoryId}
                      onChange={(event) => {
                        setIsDirty(true)
                        const nextRootId = event.target.value
                        const rootObj = categories.find((c) => c.id === nextRootId)
                        const firstActiveChild = getSubcategories(rootObj).find(
                          (sub) => sub.active !== false,
                        )
                        setForm((prev) => ({
                          ...prev,
                          primaryCategoryId: nextRootId,
                          subcategoryId: firstActiveChild?.id || '',
                        }))
                        setErrors((prev) => {
                          if (!prev.categoryId) return prev
                          const next = { ...prev }
                          delete next.categoryId
                          return next
                        })
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

                {/* Selected Category 3D Icon & Platform Fee Strip */}
                <div className={styles.selectedCategoryStrip}>
                  <div className={styles.selectedCategoryLeft}>
                    <div className={styles.selectedCategoryIconTile}>
                      <img
                        src={selectedCategoryIcon}
                        alt=""
                        aria-hidden="true"
                        className={styles.selectedCategory3dIcon}
                      />
                    </div>
                    <div>
                      <div className={styles.selectedCategoryPath}>
                        {selectedPrimaryCategory?.name || 'Service Category'}
                        {selectedSubcategory ? ` › ${selectedSubcategory.name}` : ''}
                      </div>
                      <div className={styles.fieldHelper}>
                        {availableSubcategories.filter((s) => s.active !== false).length} active subcategories
                        available
                      </div>
                    </div>
                  </div>
                  <span className={styles.countPill}>
                    Platform Fee: {payoutBreakdown.commissionPctLabel}
                  </span>
                </div>
              </section>

              <section className={styles.sectionCard}>
                <div className={styles.sectionTitleHeader}>
                  <div className={styles.sectionTitleLeft}>
                    <span className={styles.sectionNumBadge}>03</span>
                    <h2 className={styles.sectionHeading}>Scope &amp; Deliverables</h2>
                  </div>
                  {/* Integration slot for WP01 AI-003 (Kalana) Listing Description Assistant */}
                  <button
                    type="button"
                    className={styles.aiSuggestBtn}
                    disabled
                    title="AI Description Assistant — coming soon"
                  >
                    <IconSparkles size={14} />
                    <span>+ AI Suggest Description</span>
                  </button>
                </div>

                {screeningResult.flagged && (
                  <div className={styles.policyWarningBanner} role="alert">
                    <span className={styles.inlineIconText}>
                      <IconShieldAlert size={16} />
                      <span>Platform Policy: {screeningResult.reasons[0]}</span>
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
                    <span className={styles.charCounter}>
                      {form.description.length} / 3,000 characters (min 50 to publish)
                    </span>
                  </div>

                  <div className={styles.scopeStarterRow} role="group" aria-label="Description section starters">
                    {SCOPE_STARTER_TEMPLATES.map((tpl) => (
                      <button
                        key={tpl.id}
                        type="button"
                        className={styles.scopeStarterChip}
                        onClick={() => handleInsertScopeStarter(tpl.snippet)}
                      >
                        {tpl.label}
                      </button>
                    ))}
                  </div>

                  <textarea
                    id="listing-description"
                    ref={descriptionRef}
                    rows={5}
                    maxLength={3000}
                    className={`${styles.textareaInput} ${
                      errors.description ? `${styles.inputError} ${styles.fieldErrorShake}` : ''
                    }`}
                    placeholder="Describe inspection steps, tools used, safety guarantees, and what is included..."
                    value={form.description}
                    onChange={(event) => updateField('description', event.target.value)}
                  />

                  <div className={styles.scopeMeterRow}>
                    <div className={styles.scopeMeterTrack} aria-hidden="true">
                      <div
                        className={`${styles.scopeMeterFill} ${
                          scopeReadinessPct >= 100 ? styles.scopeMeterReady : ''
                        }`}
                        style={{ width: `${scopeReadinessPct}%` }}
                      />
                    </div>
                    <span
                      className={
                        scopeReadinessPct >= 100 ? styles.kpiMetaEmerald : styles.fieldHelper
                      }
                    >
                      {scopeReadinessPct >= 100
                        ? 'Ready to publish'
                        : `${scopeRemainingChars} more chars needed`}
                    </span>
                  </div>

                  {errors.description && <p className={styles.fieldErrorText}>{errors.description}</p>}
                </div>
              </section>
            </div>

            <section className={styles.sectionCard}>
              <div className={styles.sectionTitleHeader}>
                <div className={styles.sectionTitleLeft}>
                  <span className={styles.sectionNumBadge}>02</span>
                  <h2 className={styles.sectionHeading}>Fulfillment &amp; Coverage Radius</h2>
                </div>
              </div>

              <div className={styles.fieldGroup}>
                <span className={styles.fieldLabel}>Service Execution Model</span>
                <div ref={executionTrackRef} className={styles.executionSegmentedTrack}>
                  <span
                    className={styles.executionSlidingPill}
                    style={executionIndicatorStyle}
                    aria-hidden="true"
                  />
                  <button
                    data-slide-key="ON_SITE"
                    type="button"
                    aria-pressed={form.executionModel === 'ON_SITE'}
                    className={`${styles.executionSegmentedBtn} ${
                      form.executionModel === 'ON_SITE' ? styles.executionSegmentedBtnActive : ''
                    }`}
                    onClick={() => updateField('executionModel', 'ON_SITE')}
                  >
                    <IconHomePin size={15} />
                    <span>On-Site (Customer Location)</span>
                  </button>

                  <button
                    data-slide-key="WORKSHOP"
                    type="button"
                    aria-pressed={form.executionModel === 'WORKSHOP'}
                    className={`${styles.executionSegmentedBtn} ${
                      form.executionModel === 'WORKSHOP' ? styles.executionSegmentedBtnActive : ''
                    }`}
                    onClick={() => updateField('executionModel', 'WORKSHOP')}
                  >
                    <IconWorkshop size={15} />
                    <span>Provider Workshop / Bay</span>
                  </button>
                </div>
                <p className={styles.fieldHelper}>
                  {form.executionModel === 'WORKSHOP'
                    ? 'Customers visit or drop off items at your workshop; coverage radius defines your local search reach.'
                    : 'You travel directly to the customer’s home or business within your selected service radius.'}
                </p>
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
                    <IconGps size={15} className={styles.iconTeal} />
                    <span>Detect Via GPS</span>
                  </button>
                </div>
                <p className={styles.fieldHelper}>{gpsStatus}</p>
              </div>

              <div className={styles.fieldGroup}>
                <label htmlFor="base-city-input" className={styles.fieldLabel}>
                  Dispatch Neighbourhood / Base City *
                </label>
                <input
                  id="base-city-input"
                  type="text"
                  maxLength={100}
                  className={`${styles.textInput} ${
                    errors.baseCity ? `${styles.inputError} ${styles.fieldErrorShake}` : ''
                  }`}
                  placeholder={
                    form.executionModel === 'WORKSHOP'
                      ? 'e.g. Panchikawatte Workshop / Nugegoda Bay'
                      : 'e.g. Colombo 05 (Havelock Town)'
                  }
                  value={form.baseCity}
                  onChange={(event) => updateField('baseCity', event.target.value)}
                />
                {errors.baseCity && <p className={styles.fieldErrorText}>{errors.baseCity}</p>}
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
                  onChange={(event) => handleCoverageRadiusChange(event.target.value)}
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
                  onCoordinateChange={handleMapCoordinateChange}
                />
              </div>

              <div className={styles.fieldGroup}>
                <div className={styles.zoneHeaderRow}>
                  <span className={styles.fieldLabel}>
                    Covered Cities &amp; Zones * ({form.serviceAreas.length}/{MAX_SERVICE_AREAS})
                  </span>
                  <div className={styles.zoneHeaderActions}>
                    <label className={styles.autoSyncToggle}>
                      <input
                        type="checkbox"
                        checked={autoSyncZones}
                        onChange={(event) => handleToggleAutoSyncZones(event.target.checked)}
                      />
                      <span>Auto-sync with radius</span>
                    </label>
                    <button
                      type="button"
                      className={styles.zoneSyncBtn}
                      onClick={handleResetSyncZones}
                      aria-label="Sync zones from map"
                      title="Re-sync covered zones from current map pin & radius"
                    >
                      <IconRefresh size={12} />
                      <span>Sync</span>
                    </button>
                  </div>
                </div>

                <div className={styles.zonesList}>
                  {form.serviceAreas.map((area) => {
                    const isManual = manualZoneKeySet.has(area.toLowerCase())
                    return (
                      <span
                        key={area}
                        className={`${styles.zoneChip} ${isManual ? styles.zoneChipManual : ''}`}
                        title={
                          isManual
                            ? 'Manually added zone (preserved when radius changes)'
                            : 'Auto-matched from map radius (click × to exclude)'
                        }
                      >
                        <IconCheck size={12} />
                        <span>{area}</span>
                        <button
                          type="button"
                          className={styles.zoneRemoveBtn}
                          aria-label={`Remove ${area}`}
                          onClick={() => handleRemoveZone(area)}
                        >
                          <IconX size={11} />
                        </button>
                      </span>
                    )
                  })}

                  {!showZoneInput && (
                    <button
                      id="zone-add-trigger"
                      type="button"
                      className={styles.dashedChipBtn}
                      onClick={() => setShowZoneInput(true)}
                    >
                      + Add City
                    </button>
                  )}
                </div>

                {/* Smooth Accordion Drawer for Adding Custom Suburbs */}
                <div className={`${styles.accordionOuter} ${showZoneInput ? styles.accordionOpen : ''}`}>
                  <div className={styles.accordionInner}>
                    {showZoneInput && (
                      <div className={styles.zoneDrawerBox}>
                        <div className={styles.controlsGroup}>
                          <input
                            type="text"
                            list="wp03-suburb-suggestions"
                            className={styles.textInput}
                            style={{ flex: 1, minWidth: 160, padding: '7px 10px' }}
                            placeholder="Type or pick town..."
                            aria-label="New service area name"
                            value={newZoneText}
                            onChange={(event) => setNewZoneText(event.target.value)}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter') {
                                event.preventDefault()
                                handleAddZone()
                              } else if (event.key === 'Escape') {
                                setShowZoneInput(false)
                                setNewZoneText('')
                              }
                            }}
                          />
                          <datalist id="wp03-suburb-suggestions">
                            {suggestedDistrictTowns.allOptions.map((item) => (
                              <option key={`${item.district}-${item.name}`} value={item.name}>
                                {item.district}
                              </option>
                            ))}
                          </datalist>
                          <button type="button" className={styles.primaryBtn} onClick={handleAddZone}>
                            Add
                          </button>
                          <button
                            type="button"
                            className={styles.ghostBtn}
                            onClick={() => {
                              setShowZoneInput(false)
                              setNewZoneText('')
                            }}
                          >
                            Close
                          </button>
                        </div>

                        {suggestedDistrictTowns.quickPills.length > 0 && (
                          <div className={styles.quickSuburbSuggestions}>
                            <span className={styles.fieldHelper}>Quick add in {form.district}:</span>
                            {suggestedDistrictTowns.quickPills.map((town) => (
                              <button
                                key={town.name}
                                type="button"
                                className={styles.scopeStarterChip}
                                onClick={() => addZoneByName(town.name)}
                              >
                                + {town.name}
                              </button>
                            ))}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </div>

                <p className={styles.fieldHelper}>
                  {autoSyncZones
                    ? zoneSyncPreview.truncatedCount > 0
                      ? `Showing nearest ${MAX_SERVICE_AREAS} zones (+${zoneSyncPreview.truncatedCount} more within ${form.coverageRadiusKm} Km). Manually added cities stay pinned.`
                      : `Auto-synced within ${form.coverageRadiusKm} Km of map pin. Click × to exclude any zone or + Add City for custom ones.`
                    : 'Manual mode enabled — dragging the map or radius slider will not alter your selected zones.'}
                </p>
                {errors.serviceAreas && <p className={styles.fieldErrorText}>{errors.serviceAreas}</p>}
              </div>
            </section>
          </div>

          <div className={styles.wizardFooterBar}>
            <button type="button" className={styles.outlineBtn} onClick={handleCancelWizard}>
              Cancel
            </button>
            <button type="button" className={styles.primaryBtn} onClick={handleNextStep}>
              <span>Continue To Pricing &amp; Add-Ons</span>
              <IconArrowRight size={15} />
            </button>
          </div>
        </div>
      )}

      {/* STEP 2: PRICING & ADD-ONS */}
      {step === 2 && (
        <div key={`step-2-${shakeTick > 0 ? 'validated' : 'init'}`} className={stepTransitionClass}>
          <div className={styles.infoBanner}>
            <span className={styles.inlineIconText}>
              <IconInfo size={16} />
              <span>
                <strong>Price Changes:</strong> Updating your price or duration only applies to new bookings.
                Existing confirmed bookings keep their original rate.
              </span>
            </span>
          </div>

          <div className={styles.wizardStepGrid} style={{ marginTop: 16 }}>
            <div className={styles.wizardStackCol}>
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
                      aria-pressed={form.pricingModel === 'FIXED'}
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
                      aria-pressed={form.pricingModel === 'HOURLY'}
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
                    <div
                      className={`${styles.currencyInputBox} ${
                        errors.basePrice ? `${styles.inputError} ${styles.fieldErrorShake}` : ''
                      }`}
                    >
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

              {/* Live Animated Provider Net Payout & Escrow Breakdown Card */}
              <section className={styles.payoutPreviewCard} aria-label="Estimated provider payout breakdown">
                <div className={styles.payoutHeaderRow}>
                  <div>
                    <span className={styles.reviewLabel}>LIVE EARNINGS &amp; ESCROW SIMULATOR</span>
                    <h3 className={styles.payoutCardTitle}>Your Estimated Take-Home Payout</h3>
                  </div>
                  <span className={styles.countPill}>
                    {selectedSubcategory?.name || selectedPrimaryCategory?.name || 'Category'} ·{' '}
                    {payoutBreakdown.commissionPctLabel} Fee
                  </span>
                </div>

                {form.addOns.length > 0 && (
                  <div className={styles.payoutAddOnSimRow}>
                    <span className={styles.fieldHelper}>Test-stack add-ons in preview:</span>
                    <div className={styles.zonesList}>
                      {form.addOns.map((ao) => {
                        const checked = previewSelectedAddOnIds.includes(ao.addOnId)
                        return (
                          <button
                            key={ao.addOnId}
                            type="button"
                            aria-pressed={checked}
                            className={`${styles.scopeStarterChip} ${
                              checked ? styles.scopeStarterChipActive : ''
                            }`}
                            onClick={() => handleTogglePreviewAddOn(ao.addOnId)}
                          >
                            {checked ? '✓' : '+'} {ao.title} (+Rs. {ao.extraPrice})
                          </button>
                        )
                      })}
                    </div>
                  </div>
                )}

                <div className={styles.payoutRows}>
                  <div className={styles.payoutLine}>
                    <span>
                      Customer Pays ({form.pricingModel === 'HOURLY' ? 'Hourly Slot' : 'Fixed Package'} ·{' '}
                      {Number(form.durationMin || 60) + payoutBreakdown.addOnsDurationMin} mins)
                    </span>
                    <strong>{formatLkrDisplay(payoutBreakdown.customerTotal)}</strong>
                  </div>
                  <div className={styles.payoutLineMuted}>
                    <span>Marketplace Commission ({payoutBreakdown.commissionPctLabel})</span>
                    <span>- {formatLkrDisplay(payoutBreakdown.platformFee)}</span>
                  </div>
                  <div className={styles.payoutNetRow}>
                    <span>Net Escrow Payout to You</span>
                    <strong className={styles.payoutNetValue}>
                      {formatLkrDisplay(animatedNetPayout)}
                    </strong>
                  </div>
                </div>

                <div className={styles.payoutSplitBar} aria-hidden="true">
                  <div
                    className={styles.payoutSplitProvider}
                    style={{ width: `${payoutBreakdown.providerSharePct}%` }}
                  />
                </div>
                <div className={styles.fieldLabelRow}>
                  <span className={styles.kpiMetaEmerald}>
                    {payoutBreakdown.providerSharePct}% Provider Payout
                  </span>
                  <span className={styles.fieldHelper}>
                    {payoutBreakdown.commissionPctLabel} Platform Protection
                  </span>
                </div>
              </section>
            </div>

            <section className={styles.sectionCard}>
              <div className={styles.sectionTitleHeader}>
                <div className={styles.sectionTitleLeft}>
                  <span className={styles.sectionNumBadge}>02</span>
                  <h2 className={styles.sectionHeading}>Optional Add-On Packages (Extras)</h2>
                </div>
                <span className={styles.addOnsChip}>{form.addOns.length} configured</span>
              </div>

              {form.addOns.map((addOn) => {
                const isConfirmingDelete = pendingDeleteAddOnId === addOn.addOnId
                return (
                  <div key={addOn.addOnId} className={styles.addOnCard}>
                    <div className={styles.addOnLeftGroup}>
                      <span className={styles.addOnIconBadge} aria-hidden="true">
                        <IconPlus size={15} />
                      </span>
                      <div>
                        <strong>{addOn.title}</strong>
                        {addOn.description && <p className={styles.fieldHelper}>{addOn.description}</p>}
                      </div>
                    </div>
                    <div className={styles.controlsGroup}>
                      <div style={{ textAlign: 'right' }}>
                        <strong>
                          + Rs. {Number(addOn.extraPrice).toLocaleString('en-LK', { minimumFractionDigits: 2 })}
                        </strong>
                        <div className={styles.fieldHelper}>Duration: +{addOn.extraDurationMin} Mins</div>
                      </div>
                      {isConfirmingDelete ? (
                        <>
                          <button
                            type="button"
                            className={styles.outlineBtn}
                            onClick={() => setPendingDeleteAddOnId(null)}
                          >
                            Keep
                          </button>
                          <button
                            type="button"
                            className={styles.dangerBtn}
                            onClick={() => handleConfirmDeleteAddOn(addOn.addOnId)}
                          >
                            Confirm Remove
                          </button>
                        </>
                      ) : (
                        <>
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
                            onClick={() => setPendingDeleteAddOnId(addOn.addOnId)}
                          >
                            Delete
                          </button>
                        </>
                      )}
                    </div>
                  </div>
                )
              })}

              {/* Smooth Height Accordion for Inline Add-On Form */}
              <div className={`${styles.accordionOuter} ${showAddOnEditor ? styles.accordionOpen : ''}`}>
                <div className={styles.accordionInner}>
                  {showAddOnEditor && (
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
                  )}
                </div>
              </div>

              {!showAddOnEditor && (
                <button
                  type="button"
                  className={styles.dashedAddBtn}
                  onClick={() => handleOpenAddOnEditor(null)}
                >
                  <IconPlus size={15} />
                  <span>Add Another Extra / Add-On Package</span>
                </button>
              )}
            </section>
          </div>

          <div className={styles.wizardFooterBar}>
            <div className={styles.controlsGroup}>
              <button type="button" className={styles.outlineBtn} onClick={() => goToStep(1)}>
                <IconArrowLeft size={15} />
                <span>Back To Basic Details</span>
              </button>
              <button type="button" className={styles.ghostBtn} onClick={handleCancelWizard}>
                Cancel
              </button>
            </div>
            <button type="button" className={styles.primaryBtn} onClick={handleNextStep}>
              <span>Continue To Portfolio Media</span>
              <IconArrowRight size={15} />
            </button>
          </div>
        </div>
      )}

      {/* STEP 3: PORTFOLIO MEDIA */}
      {step === 3 && (
        <div key={`step-3-${shakeTick > 0 ? 'validated' : 'init'}`} className={stepTransitionClass}>
          <div className={styles.wizardMediaGrid}>
            <section className={styles.sectionCard}>
              <div className={styles.sectionTitleHeader}>
                <div className={styles.sectionTitleLeft}>
                  <span className={styles.sectionNumBadge}>01</span>
                  <h2 className={styles.sectionHeading}>Media Dropzone</h2>
                </div>
                <span className={styles.fieldHelper}>High-quality photos increase bookings cleanly</span>
              </div>

              <div
                className={`${styles.dropzoneBox} ${
                  isDragOverDropzone ? styles.dropzoneBoxActive : ''
                } ${errors.photos ? styles.fieldErrorShake : ''}`}
                onDragEnter={(event) => {
                  event.preventDefault()
                  setIsDragOverDropzone(true)
                }}
                onDragOver={(event) => {
                  event.preventDefault()
                  if (!isDragOverDropzone) setIsDragOverDropzone(true)
                }}
                onDragLeave={(event) => {
                  if (event.currentTarget === event.target) {
                    setIsDragOverDropzone(false)
                  }
                }}
                onDrop={(event) => {
                  event.preventDefault()
                  setIsDragOverDropzone(false)
                  handleFilesSelected(event.dataTransfer?.files)
                }}
              >
                <span
                  className={`${styles.dropzoneIconCircle} ${
                    isDragOverDropzone ? styles.dropzoneIconFloat : ''
                  }`}
                >
                  <IconUploadCloud size={24} />
                </span>
                <strong>{isDragOverDropzone ? 'Drop photos to upload' : 'Media Dropzone'}</strong>
                <p className={styles.fieldHelper}>
                  Drag &amp; drop high-resolution service photos here for secure cloud upload. Supports JPG, PNG, WEBP up
                  to 5MB each (Max 8 photos).
                </p>
                <input
                  ref={fileInputRef}
                  type="file"
                  multiple
                  accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
                  hidden
                  aria-label="Upload portfolio photos"
                  onChange={(event) => {
                    handleFilesSelected(event.target.files)
                    event.target.value = ''
                  }}
                />
                <button
                  id="select-photos-btn"
                  type="button"
                  className={styles.primaryBtn}
                  onClick={() => fileInputRef.current?.click()}
                >
                  <IconPlus size={15} />
                  <span>Select Photos From Computer</span>
                </button>
              </div>

              <div className={styles.districtRow}>
                <input
                  type="url"
                  className={styles.textInput}
                  placeholder="Or paste HTTPS image URL (e.g. https://res.cloudinary.com/.../work-1.jpg)"
                  aria-label="HTTPS image URL"
                  value={imageUrlInput}
                  onChange={(event) => setImageUrlInput(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') {
                      event.preventDefault()
                      handleAddImageUrl()
                    }
                  }}
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

              {form.photos.length === 0 ? (
                <div className={styles.emptyGalleryBox}>
                  <strong>No portfolio photos added yet</strong>
                  <p className={styles.fieldHelper}>
                    Upload at least 1 real photo of your tools, completed work, or workshop. Slot #1 automatically
                    becomes your public cover photo.
                  </p>
                </div>
              ) : (
                <div className={styles.photoGridCompact}>
                  {form.photos.map((photo, index) => {
                    const isCover = index === 0
                    return (
                      <div
                        key={photo.id}
                        draggable={!photo.uploading}
                        onDragStart={() => setDraggedPhotoId(photo.id)}
                        onDragOver={(event) => event.preventDefault()}
                        onDrop={() => handleDropReorderPhoto(photo.id)}
                        className={`${styles.photoCard} ${isCover ? styles.photoCardCover : ''} ${
                          draggedPhotoId === photo.id ? styles.photoCardDragging : ''
                        }`}
                      >
                        <div className={styles.photoPreviewWrap}>
                          <ListingCoverMedia
                            src={photo.previewUrl || photo.httpsUrl}
                            alt={photo.name}
                            categoryName={selectedSubcategory?.name || selectedPrimaryCategory?.name}
                          />
                          {isCover && <span className={styles.coverBadge}>PRIMARY COVER</span>}
                          {photo.uploading && (
                            <div className={styles.photoUploadingOverlay}>
                              <span>Uploading {photo.progress ?? 20}%</span>
                              <div className={styles.photoMiniProgressTrack}>
                                <div
                                  className={styles.photoMiniProgressFill}
                                  style={{ width: `${photo.progress ?? 20}%` }}
                                />
                              </div>
                            </div>
                          )}
                        </div>
                        <div className={styles.photoNameLine} title={photo.name}>
                          {photo.name}
                        </div>
                        <div className={styles.photoCardFooter}>
                          {isCover ? (
                            <span className={styles.kpiMetaEmerald}>Active Cover</span>
                          ) : (
                            <button
                              type="button"
                              className={styles.textBtnEmerald}
                              disabled={Boolean(photo.uploading)}
                              onClick={() => handleSetCoverPhoto(photo.id)}
                            >
                              Make Cover
                            </button>
                          )}

                          <div className={styles.photoOrderControls}>
                            <button
                              type="button"
                              className={styles.photoOrderBtn}
                              aria-label={`Move ${photo.name} left`}
                              title="Move photo earlier"
                              disabled={index === 0 || Boolean(photo.uploading)}
                              onClick={() => handleMovePhoto(photo.id, -1)}
                            >
                              <IconArrowLeft size={12} />
                            </button>
                            <button
                              type="button"
                              className={styles.photoOrderBtn}
                              aria-label={`Move ${photo.name} right`}
                              title="Move photo later"
                              disabled={index === form.photos.length - 1 || Boolean(photo.uploading)}
                              onClick={() => handleMovePhoto(photo.id, 1)}
                            >
                              <IconArrowRight size={12} />
                            </button>
                            <button
                              type="button"
                              className={styles.textBtnRed}
                              onClick={() => handleRemovePhoto(photo.id)}
                            >
                              Delete
                            </button>
                          </div>
                        </div>
                      </div>
                    )
                  })}
                </div>
              )}

              <div className={styles.archiveInfoCard}>
                <div className={styles.archiveInfoRow}>
                  <span className={styles.inlineIconText}>
                    <IconCheckCircle size={14} className={styles.iconTeal} />
                    <span>Direct Upload Status:</span>
                  </span>
                  <strong>{persistedPhotos.length} / {MAX_IMAGES}</strong>
                </div>
                <div className={styles.uploadBarTrack}>
                  <div
                    className={styles.uploadBarFill}
                    style={{ width: `${(persistedPhotos.length / MAX_IMAGES) * 100}%` }}
                  />
                </div>
                <span className={styles.fieldHelper}>
                  Tip: Drag cards or use arrow buttons to reorder your gallery sequence.
                </span>
              </div>
            </section>
          </div>

          <div className={styles.wizardFooterBar}>
            <div className={styles.controlsGroup}>
              <button type="button" className={styles.outlineBtn} onClick={() => goToStep(2)}>
                <IconArrowLeft size={15} />
                <span>Back To Pricing &amp; Add-Ons</span>
              </button>
              <button type="button" className={styles.ghostBtn} onClick={handleCancelWizard}>
                Cancel
              </button>
            </div>
            <button type="button" className={styles.primaryBtn} onClick={handleNextStep}>
              <span>Continue To Review &amp; Publish</span>
              <IconArrowRight size={15} />
            </button>
          </div>
        </div>
      )}

      {/* STEP 4: REVIEW & PUBLISH */}
      {step === 4 && (
        <div key="step-4" className={stepTransitionClass}>
          {/* 5-Point Cascading Pre-Publish Readiness Strip */}
          <div className={styles.readinessChecklistBar} aria-label="Pre-publish readiness checklist">
            {[
              { label: 'Title & Category', ok: form.title.trim().length >= 10 && !isSelectedCategoryInactive },
              { label: `${form.serviceAreas.length} Coverage Zones`, ok: form.serviceAreas.length > 0 },
              { label: `Net ${formatLkrDisplay(payoutBreakdown.providerNetPayout)}`, ok: Number(form.basePrice) >= 100 },
              { label: `${persistedPhotos.length} Portfolio Photo${persistedPhotos.length === 1 ? '' : 's'}`, ok: persistedPhotos.length >= 1 },
              {
                label: screeningResult.flagged ? 'Moderator Review Needed' : 'Policy Check Clean',
                ok: !screeningResult.flagged,
              },
            ].map((item, idx) => (
              <span
                key={item.label}
                style={{ '--check-idx': idx }}
                className={`${styles.readinessPill} ${
                  item.ok ? styles.readinessPillOk : styles.readinessPillWarn
                }`}
              >
                {item.ok ? <IconCheckCircle size={13} /> : <IconAlertTriangle size={13} />}
                <span>{item.label}</span>
              </span>
            ))}
          </div>

          <div className={styles.wizardStepGrid} style={{ marginTop: 16 }}>
            <div className={styles.wizardStackCol}>
              <section className={styles.sectionCard}>
                <div className={styles.sectionTitleHeader}>
                  <div className={styles.sectionTitleLeft}>
                    <span className={styles.sectionNumBadge}>01</span>
                    <h2 className={styles.sectionHeading}>Basic Details &amp; Geography</h2>
                  </div>
                  <button type="button" className={styles.outlineBtn} onClick={() => goToStep(1)}>
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
                  <p className={styles.modalBodyText} style={{ whiteSpace: 'pre-line' }}>
                    {form.description}
                  </p>
                </div>

                {screeningResult.flagged && (
                  <div className={styles.policyWarningBanner}>
                    <span className={styles.inlineIconText}>
                      <IconAlertTriangle size={16} />
                      <span>
                        Contact details or external links detected — remove them to publish immediately, otherwise
                        your listing will wait for moderator approval.
                      </span>
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
                  <button type="button" className={styles.outlineBtn} onClick={() => goToStep(2)}>
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
                    <p className={styles.reviewLabel}>BASE RATE &amp; NET PAYOUT</p>
                    <p className={styles.reviewValueEmerald}>
                      {formatLkrDisplay(form.basePrice)} LKR
                    </p>
                    <p className={styles.fieldHelper}>
                      Est. take-home: {formatLkrDisplay(payoutBreakdown.providerNetPayout)} ({payoutBreakdown.commissionPctLabel} fee)
                    </p>
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
            </div>

            <div className={styles.wizardStackCol}>
              {/* Live Customer-Eye Marketplace Card Preview (Zero Emojis, Clean Cover) */}
              <section className={styles.sectionCard} aria-label="Customer marketplace card preview">
                <div className={styles.sectionTitleHeader}>
                  <div className={styles.sectionTitleLeft}>
                    <span className={styles.sectionNumBadge}>
                      <IconEye size={14} />
                    </span>
                    <h2 className={styles.sectionHeading}>Customer Marketplace Preview</h2>
                  </div>
                  <span className={styles.countPill}>Live Card View</span>
                </div>

                <div className={styles.customerPreviewStage}>
                  <article className={styles.customerPreviewCard}>
                    <div className={styles.cardMediaWrap}>
                      <ListingCoverMedia
                        src={previewCoverSrc}
                        alt={form.title || 'Listing cover preview'}
                        categoryName={selectedSubcategory?.name || selectedPrimaryCategory?.name}
                      />
                    </div>

                    <div className={styles.cardBody}>
                      <div className={styles.cardCategoryRow}>
                        <span className={styles.categoryChip}>
                          {selectedSubcategory?.name || selectedPrimaryCategory?.name || 'Service'}
                        </span>
                        <span
                          className={`${styles.statusInlineBadge} ${
                            screeningResult.flagged ? styles.statusPaused : styles.statusActive
                          }`}
                        >
                          <span className={styles.statusDot} aria-hidden="true" />
                          <span>{screeningResult.flagged ? 'Pending Review' : 'Bookable'}</span>
                        </span>
                      </div>

                      <h3 className={styles.cardTitle}>
                        {form.title.trim() || 'Your Service Title Appears Here'}
                      </h3>

                      <div className={styles.cardPriceRow}>
                        <span>From</span>
                        <strong className={styles.cardPriceStrong}>
                          Rs. {Number(form.basePrice || 0).toLocaleString('en-LK')}
                        </strong>
                        <span>{form.pricingModel === 'HOURLY' ? '/ hr' : '/ fixed'}</span>
                        <span>· {selectedDurationLabel}</span>
                      </div>

                      <div className={styles.cardLocationStrip}>
                        <span className={styles.cardLocationItem}>
                          <IconMapPin size={13} />
                          <span>{form.baseCity || form.district}</span>
                        </span>
                        <span className={styles.cardLocationDot} aria-hidden="true">·</span>
                        <span className={styles.cardLocationItem}>
                          {form.executionModel === 'WORKSHOP' ? (
                            <>
                              <IconWorkshop size={13} />
                              <span>Workshop</span>
                            </>
                          ) : (
                            <>
                              <IconHomePin size={13} />
                              <span>On-Site</span>
                            </>
                          )}
                        </span>
                      </div>

                      <div className={styles.cardMetaRow}>
                        <span className={styles.addOnsChip}>{form.addOns.length} Optional Extras</span>
                        <span className={styles.kpiMetaEmerald}>Escrow Protected</span>
                      </div>
                    </div>
                  </article>
                </div>
              </section>

              <section className={styles.sectionCard}>
                <div className={styles.sectionTitleHeader}>
                  <div className={styles.sectionTitleLeft}>
                    <span className={styles.sectionNumBadge}>03</span>
                    <h2 className={styles.sectionHeading}>
                      Portfolio Media ({persistedPhotos.length} Photo{persistedPhotos.length === 1 ? '' : 's'} Attached)
                    </h2>
                  </div>
                  <button type="button" className={styles.outlineBtn} onClick={() => goToStep(3)}>
                    Edit
                  </button>
                </div>

                <div className={styles.photoGridCompact}>
                  {persistedPhotos.map((photo, index) => (
                    <div key={photo.id} className={styles.photoCard}>
                      <div className={styles.photoPreviewWrap}>
                        <ListingCoverMedia
                          src={photo.previewUrl || photo.httpsUrl}
                          alt={photo.name}
                          categoryName={selectedSubcategory?.name || selectedPrimaryCategory?.name}
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
                  Upload quota: <strong>{persistedPhotos.length} of 8</strong> media slots used.
                </p>
              </section>
            </div>
          </div>

          <div className={styles.wizardFooterBar}>
            <div className={styles.controlsGroup}>
              <button type="button" className={styles.outlineBtn} onClick={() => goToStep(3)}>
                <IconArrowLeft size={15} />
                <span>Back To Portfolio Media</span>
              </button>
              <button type="button" className={styles.ghostBtn} onClick={handleCancelWizard}>
                Cancel
              </button>
            </div>
            <button
              type="button"
              className={styles.primaryBtn}
              disabled={Boolean(submittingMode)}
              onClick={() => handleSubmitListing(false)}
            >
              <span>
                {submittingMode === 'publish'
                  ? isModeratedEdit
                    ? 'Resubmitting...'
                    : 'Publishing Listing...'
                  : isModeratedEdit
                    ? 'Save Fixes & Resubmit for Review'
                    : isEditMode
                      ? 'Save Changes & Publish'
                      : 'Publish Service Listing'}
              </span>
              {!submittingMode && <IconArrowRight size={15} />}
            </button>
          </div>
        </div>
      )}

      {/* Unsaved Changes Confirmation Modal (PR #52 F2 Parity) */}
      {showCancelConfirm && (
        <div
          className={styles.modalBackdrop}
          role="presentation"
          onClick={(event) => {
            if (event.target === event.currentTarget && !submittingMode) {
              setShowCancelConfirm(false)
            }
          }}
        >
          <div
            className={styles.modalDialog}
            role="dialog"
            aria-modal="true"
            aria-labelledby="discard-wizard-title"
          >
            <div className={styles.modalHeader}>
              <div className={styles.modalTitleGroup}>
                <span className={styles.modalIconAmber}>
                  <IconAlertTriangle size={20} />
                </span>
                <div>
                  <h2 id="discard-wizard-title" className={styles.modalTitle}>
                    Unsaved Listing Changes
                  </h2>
                  <p className={styles.modalSubtitle}>
                    You have unsaved edits in this listing wizard.
                  </p>
                </div>
              </div>
              <button
                type="button"
                className={styles.ghostBtn}
                aria-label="Close modal"
                onClick={() => setShowCancelConfirm(false)}
              >
                <IconX size={16} />
              </button>
            </div>

            <p className={styles.modalBodyText}>
              Leaving now will discard your unsaved changes. Would you like to keep editing
              {canSaveAsDraft ? ', save a private draft,' : ''} or discard changes?
            </p>

            <div className={styles.modalFooter}>
              <button
                type="button"
                className={styles.outlineBtn}
                onClick={() => setShowCancelConfirm(false)}
              >
                Keep Editing
              </button>
              {canSaveAsDraft && (
                <button
                  type="button"
                  className={styles.primaryBtn}
                  disabled={Boolean(submittingMode)}
                  onClick={() => handleSubmitListing(true)}
                >
                  Save Draft
                </button>
              )}
              <button
                type="button"
                className={styles.dangerBtn}
                onClick={handleConfirmDiscardAndLeave}
              >
                Discard Changes
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
