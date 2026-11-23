import { useState, useEffect, useMemo, useCallback } from 'react'
import { AdminPage } from '../../../shared/layouts/admin'
import { ConfirmDialog, useToast } from '../../../shared/components/feedback'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import {
  getAdminCategories,
  createCategory,
  updateCategory,
  deactivateCategory,
  extractCatalogApiError,
} from '../services/catalogService'
import {
  iconCatElectrical,
  iconCatSolar,
  iconCatPlumbing,
  iconCatHvac,
  iconCatWoodwork,
  iconCatCivil,
  iconCatAppliance,
  iconCatPainting,
  iconCatCleaning,
  iconCatCctv,
  iconCatGardening,
  iconCatSafety,
  iconCatBeauty,
  iconCatBarbering,
  iconCatWellness,
  iconCatItTech,
  iconCatMoving,
  iconCatVideo,
} from '../assets'
import styles from '../styles/adminCategories.module.css'

const MAX_DESCRIPTION_LENGTH = 500

const PREDEFINED_ICONS = [
  { img: iconCatElectrical, name: 'Electrical & Power', id: 'electrician' },
  { img: iconCatSolar, name: 'Solar & Renewable Energy', id: 'sun' },
  { img: iconCatPlumbing, name: 'Plumbing & Water', id: 'water' },
  { img: iconCatHvac, name: 'HVAC & Climate', id: 'temp' },
  { img: iconCatWoodwork, name: 'Carpentry & Woodwork', id: 'pencil' },
  { img: iconCatCivil, name: 'Masonry & Civil', id: 'grid' },
  { img: iconCatAppliance, name: 'Appliance Repair', id: 'wrench' },
  { img: iconCatPainting, name: 'Painting & Finishing', id: 'roller' },
  { img: iconCatCleaning, name: 'Cleaning & Sanitization', id: 'sparkles' },
  { img: iconCatCctv, name: 'Security & CCTV', id: 'camera' },
  { img: iconCatGardening, name: 'Gardening & Landscaping', id: 'plant' },
  { img: iconCatSafety, name: 'Safety & Protection', id: 'shield' },
  { img: iconCatBeauty, name: 'Beauty & Salon', id: 'salon' },
  { img: iconCatBarbering, name: 'Hair & Grooming', id: 'scissors' },
  { img: iconCatWellness, name: 'Wellness & Spa', id: 'lotus' },
  { img: iconCatItTech, name: 'IT & Computer Repair', id: 'laptop' },
  { img: iconCatMoving, name: 'Moving & Assembly', id: 'package' },
  { img: iconCatVideo, name: 'Photography & Video', id: 'video' },
]

const ICON_ALIASES = {
  electrician: 'electrician',
  electrical: 'electrician',
  electricity: 'electrician',
  power: 'electrician',
  plug: 'electrician',
  sun: 'sun',
  solar: 'sun',
  renewable: 'sun',
  water: 'water',
  plumbing: 'water',
  temp: 'temp',
  hvac: 'temp',
  ac: 'temp',
  climate: 'temp',
  pencil: 'pencil',
  saw: 'pencil',
  carpentry: 'pencil',
  woodwork: 'pencil',
  grid: 'grid',
  masonry: 'grid',
  civil: 'grid',
  bricks: 'grid',
  wrench: 'wrench',
  appliance: 'wrench',
  roller: 'roller',
  paint: 'roller',
  painting: 'roller',
  sparkles: 'sparkles',
  clean: 'sparkles',
  cleaning: 'sparkles',
  sanitization: 'sparkles',
  camera: 'camera',
  cctv: 'camera',
  security: 'camera',
  plant: 'plant',
  garden: 'plant',
  gardening: 'plant',
  landscaping: 'plant',
  lawn: 'plant',
  shield: 'shield',
  safety: 'shield',
  protection: 'shield',
  pest: 'shield',
  salon: 'salon',
  beauty: 'salon',
  hairdryer: 'salon',
  dryer: 'salon',
  scissors: 'scissors',
  barber: 'scissors',
  barbering: 'scissors',
  grooming: 'scissors',
  haircut: 'scissors',
  comb: 'scissors',
  lotus: 'lotus',
  wellness: 'lotus',
  spa: 'lotus',
  massage: 'lotus',
  yoga: 'lotus',
  laptop: 'laptop',
  it: 'laptop',
  tech: 'laptop',
  computer: 'laptop',
  software: 'laptop',
  package: 'package',
  moving: 'package',
  delivery: 'package',
  assembly: 'package',
  courier: 'package',
  box: 'package',
  video: 'video',
  photography: 'video',
  videography: 'video',
  media: 'video',
  filming: 'video',
}

function findIconOption(cat) {
  if (!cat) return null
  const rawKey = (cat.icon || cat.id || '').toString().trim().toLowerCase()
  const mappedKey = ICON_ALIASES[rawKey]
  if (mappedKey) {
    const byId = PREDEFINED_ICONS.find((item) => item.id === mappedKey)
    if (byId) return byId
  }
  if (cat.iconImg) {
    const byImg = PREDEFINED_ICONS.find((item) => item.img === cat.iconImg)
    if (byImg) return byImg
  }
  const text = `${cat.id || ''} ${cat.slug || ''} ${cat.name || ''}`.toLowerCase()
  if (/(elec|wiring|switchboard|\bpower\b)/.test(text)) return PREDEFINED_ICONS[0]
  if (/(solar|renewable|\bsun\b)/.test(text)) return PREDEFINED_ICONS[1]
  if (/(mason|civil|tile|tiling|plaster|waterproof|brick|\bcat-mas\b)/.test(text)) return PREDEFINED_ICONS[5]
  if (/(plumb|pipe|drain|tank|bath|water(?!proof))/.test(text)) return PREDEFINED_ICONS[2]
  if (/(hvac|air[-_\s]?cond|\bac\b|climate|cooling)/.test(text)) return PREDEFINED_ICONS[3]
  if (/(carp|woodwork|furniture|door|window|\bsaw\b)/.test(text)) return PREDEFINED_ICONS[4]
  if (/(appliance|fridge|refrigerator|washing|microwave|oven|\bcat-app\b)/.test(text)) return PREDEFINED_ICONS[6]
  if (/(paint|finishing|roller)/.test(text)) return PREDEFINED_ICONS[7]
  if (/(clean|sanit|sparkle)/.test(text)) return PREDEFINED_ICONS[8]
  if (/(cctv|surveillance|doorbell)/.test(text)) return PREDEFINED_ICONS[9]
  if (/(garden|plant|landscape|lawn)/.test(text)) return PREDEFINED_ICONS[10]
  if (/(shield|safety|security|pest|protect)/.test(text)) return PREDEFINED_ICONS[11]
  if (/(salon|beauty|dryer|bridal|makeup)/.test(text)) return PREDEFINED_ICONS[12]
  if (/(barber|scissor|comb|grooming|haircut|trim)/.test(text)) return PREDEFINED_ICONS[13]
  if (/(wellness|spa|lotus|massage|yoga|therapy)/.test(text)) return PREDEFINED_ICONS[14]
  if (/(laptop|computer|\bit\b|tech|network|software)/.test(text)) return PREDEFINED_ICONS[15]
  if (/(moving|delivery|courier|package|assembly|relocat)/.test(text)) return PREDEFINED_ICONS[16]
  if (/(video|photo|film|media|shoot)/.test(text)) return PREDEFINED_ICONS[17]
  return null
}

/**
 * Hydrates category icons from the backend tree.
 * Subcategories inherit their parent root icon first so updating a root category icon
 * immediately reflects across all of its subcategories.
 */
function hydrateCategoryIcons(items, parentImg = null, isSub = false) {
  return items.map((item) => {
    const resolvedOption = findIconOption(item)
    const resolvedImg = isSub
      ? parentImg || resolvedOption?.img || item.iconImg
      : resolvedOption?.img || parentImg || item.iconImg
    return {
      ...item,
      icon: item.icon || resolvedOption?.id || null,
      iconImg: resolvedImg,
      subcategories: Array.isArray(item.subcategories)
        ? hydrateCategoryIcons(item.subcategories, resolvedImg, true)
        : [],
    }
  })
}

function slugify(text) {
  return text
    .toString()
    .toLowerCase()
    .trim()
    .replace(/&/g, 'and')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

function toCommissionPercentString(rate) {
  return rate != null && Number.isFinite(Number(rate)) ? (Number(rate) * 100).toFixed(2) : '10.00'
}

export default function AdminCategoriesPage({ entry } = {}) {
  const areaAccess = useAreaAccess()
  const toast = useToast()
  // Fail-closed least-privilege guard matching merged Admin pages (useAreaAccess defaults canEdit=false).
  const canEdit = Boolean(areaAccess?.canEdit)

  const [categories, setCategories] = useState([])
  const [activeTab, setActiveTab] = useState('ALL')
  const [searchTerm, setSearchTerm] = useState('')
  const [expandedNodes, setExpandedNodes] = useState({})
  const [selectedId, setSelectedId] = useState(null)
  const [syncStatus, setSyncStatus] = useState('loading') // 'loading' | 'live' | 'error'
  const [syncError, setSyncError] = useState(null)
  const [togglingId, setTogglingId] = useState(null)
  const [confirmDeactivateItem, setConfirmDeactivateItem] = useState(null)
  const [pendingEditDeactivate, setPendingEditDeactivate] = useState(null)
  const [isInitialReveal, setIsInitialReveal] = useState(true)

  useEffect(() => {
    const timer = setTimeout(() => setIsInitialReveal(false), 1800)
    return () => clearTimeout(timer)
  }, [])

  // Form State
  const [formName, setFormName] = useState('')
  const [formSlug, setFormSlug] = useState('')
  const [formSlugAuto, setFormSlugAuto] = useState(true)
  const [formDescription, setFormDescription] = useState('')
  const [formNodeType, setFormNodeType] = useState('ROOT') // 'ROOT' | 'SUBCATEGORY'
  const [formParentId, setFormParentId] = useState('')
  const [formCommission, setFormCommission] = useState('10.00')
  const [selectedIconObj, setSelectedIconObj] = useState(PREDEFINED_ICONS[1]) // Solar default
  const [formVisiblePublic, setFormVisiblePublic] = useState(true)
  const [formLoading, setFormLoading] = useState(false)
  const [formError, setFormError] = useState(null)

  const isEditing = Boolean(selectedId)
  const selectedCategory = useMemo(() => {
    if (!selectedId) return null
    for (const root of categories) {
      if (root.id === selectedId) return root
      const subMatch = (root.subcategories || []).find((sub) => sub.id === selectedId)
      if (subMatch) return subMatch
    }
    return null
  }, [categories, selectedId])
  const activeIconObj = findIconOption(selectedIconObj) || selectedIconObj
  const selectedParentRoot =
    formNodeType === 'SUBCATEGORY' ? categories.find((c) => c.id === formParentId) || null : null
  const inheritedParentIconObj = selectedParentRoot
    ? findIconOption(selectedParentRoot) || PREDEFINED_ICONS[1]
    : null
  const effectiveIconObj = inheritedParentIconObj || activeIconObj

  const applySyncSuccess = useCallback((data) => {
    const hydrated = Array.isArray(data) ? hydrateCategoryIcons(data) : []
    setCategories(hydrated)
    setExpandedNodes((prev) => {
      if (hydrated.length === 0) return prev
      const hasExpandedMatch = hydrated.some((root) => prev[root.id])
      return hasExpandedMatch ? prev : { ...prev, [hydrated[0].id]: true }
    })
    setSyncStatus('live')
    setSyncError(null)
    return hydrated
  }, [])

  const applySyncError = useCallback((err) => {
    const parsed = extractCatalogApiError(err, 'Unable to load categories from backend')
    if (parsed.cancelled) return null
    setSyncStatus('error')
    if (parsed.status === 401) {
      setSyncError('Your admin session is missing or expired. Please sign in and retry.')
    } else if (parsed.status === 403) {
      setSyncError('You do not have permission to view categories in this admin area.')
    } else {
      setSyncError(parsed.message)
    }
    return null
  }, [])

  const syncCategoriesFromBackend = useCallback(
    async (signal) => {
      setSyncStatus('loading')
      try {
        const data = await getAdminCategories(signal)
        return applySyncSuccess(data)
      } catch (err) {
        return applySyncError(err)
      }
    },
    [applySyncError, applySyncSuccess]
  )

  useEffect(() => {
    const controller = new AbortController()
    getAdminCategories(controller.signal).then(applySyncSuccess, applySyncError)
    return () => controller.abort()
  }, [applySyncError, applySyncSuccess])

  // Live KPI Metrics computed directly from MongoDB category tree
  const kpiMetrics = useMemo(() => {
    const allSubs = categories.flatMap((root) => root.subcategories || [])
    const allNodes = [...categories, ...allSubs]
    const activeNodes = allNodes.filter((node) => node.active)
    const inactiveCount = allNodes.length - activeNodes.length

    const activeRates = activeNodes
      .map((node) => Number(node.commissionRate))
      .filter((rate) => Number.isFinite(rate))
    const avgCommissionPct =
      activeRates.length > 0
        ? ((activeRates.reduce((sum, r) => sum + r, 0) / activeRates.length) * 100).toFixed(1)
        : '0.0'
    const minCommissionPct =
      activeRates.length > 0 ? (Math.min(...activeRates) * 100).toFixed(1) : '0.0'
    const maxCommissionPct =
      activeRates.length > 0 ? (Math.max(...activeRates) * 100).toFixed(1) : '0.0'

    const activeSharePct =
      allNodes.length > 0 ? Math.round((activeNodes.length / allNodes.length) * 100) : 0

    return {
      totalRoots: categories.length,
      totalSubs: allSubs.length,
      totalNodes: allNodes.length,
      activeCount: activeNodes.length,
      inactiveCount,
      avgCommissionPct,
      minCommissionPct,
      maxCommissionPct,
      activeSharePct,
      hasMultipleRates: activeRates.length > 1 && minCommissionPct !== maxCommissionPct,
    }
  }, [categories])

  // Handle Form Name Change -> Auto Slug
  const handleNameChange = (e) => {
    const val = e.target.value
    setFormName(val)
    if (formSlugAuto) {
      setFormSlug(slugify(val))
    }
  }

  // Handle Selecting a Category from Tree for Editing
  const handleSelectCategory = (cat, isSub = false, parentRoot = null) => {
    setSelectedId(cat.id)
    setFormName(cat.name || '')
    setFormSlug(cat.slug || '')
    setFormSlugAuto(false)
    setFormDescription(cat.description || '')
    setFormNodeType(isSub ? 'SUBCATEGORY' : 'ROOT')
    setFormParentId(isSub ? cat.parentId || parentRoot?.id || '' : '')
    setFormCommission(toCommissionPercentString(cat.commissionRate))
    const resolvedParent = isSub
      ? parentRoot || categories.find((c) => c.id === cat.parentId) || null
      : null
    setSelectedIconObj(
      (resolvedParent && findIconOption(resolvedParent)) || findIconOption(cat) || PREDEFINED_ICONS[1]
    )
    setFormVisiblePublic(cat.active !== false)
    setFormError(null)
    setPendingEditDeactivate(null)
  }

  // Reset Form for "+ Add New Category" or dashed card
  const handleResetNewCategory = () => {
    setSelectedId(null)
    setFormName('')
    setFormSlug('')
    setFormSlugAuto(true)
    setFormDescription('')
    setFormNodeType('ROOT')
    setFormParentId('')
    setFormCommission('10.00')
    setSelectedIconObj(PREDEFINED_ICONS[1])
    setFormVisiblePublic(true)
    setFormError(null)
    setPendingEditDeactivate(null)
  }

  // Switch Form to "Add Subcategory Under <Root>"
  const handleStartAddSubcategory = (parentRoot) => {
    if (!parentRoot.active) {
      const msg = `Activate "${parentRoot.name}" before adding a subcategory under it.`
      setFormError(msg)
      toast.error(msg)
      return
    }
    setSelectedId(null)
    setFormName('')
    setFormSlug('')
    setFormSlugAuto(true)
    setFormDescription('')
    setFormNodeType('SUBCATEGORY')
    setFormParentId(parentRoot.id)
    setFormCommission(toCommissionPercentString(parentRoot.commissionRate))
    setSelectedIconObj(findIconOption(parentRoot) || PREDEFINED_ICONS[1])
    setFormVisiblePublic(true)
    setFormError(null)
    setPendingEditDeactivate(null)
    setExpandedNodes((prev) => ({ ...prev, [parentRoot.id]: true }))
  }

  // Toggle Single Node Expand/Collapse
  const toggleExpand = (id) => {
    setExpandedNodes((prev) => ({
      ...prev,
      [id]: !prev[id],
    }))
  }

  // Expand All / Collapse All
  const handleToggleExpandAll = () => {
    const allExpanded = categories.length > 0 && categories.every((c) => expandedNodes[c.id])
    const next = {}
    categories.forEach((c) => {
      next[c.id] = !allExpanded
    })
    setExpandedNodes(next)
  }

  // Execute Active <-> Inactive Toggle against Real Backend API
  const executeToggleCategoryActive = async (item, nextActive) => {
    if (!canEdit || !item?.id || togglingId === item.id) return
    setTogglingId(item.id)
    setFormError(null)

    try {
      if (nextActive) {
        await updateCategory(item.id, {
          name: item.name,
          slug: item.slug,
          description: item.description ?? null,
          icon: item.icon ?? null,
          commissionRate: item.commissionRate != null ? Number(item.commissionRate) : 0.1,
          active: true,
        })
      } else {
        await deactivateCategory(item.id)
      }

      await syncCategoriesFromBackend()
      if (selectedId === item.id) setFormVisiblePublic(nextActive)
      toast.success(`"${item.name}" is now ${nextActive ? 'Active' : 'Inactive'}.`)
    } catch (err) {
      const parsed = extractCatalogApiError(err, 'Failed to update category status')
      setFormError(parsed.message)
      toast.error(parsed.message)
    } finally {
      setTogglingId(null)
      setConfirmDeactivateItem(null)
    }
  }

  // Status Pill Click -> Confirm before Deactivating; reactivate directly
  const handleStatusPillClick = (item, e) => {
    if (e) e.stopPropagation()
    if (!canEdit || !item?.id || togglingId === item.id) return

    if (item.active) {
      setConfirmDeactivateItem(item)
    } else {
      executeToggleCategoryActive(item, true)
    }
  }

  // Execute category update against backend API (reused by normal edits and confirmed Edit-form deactivation)
  const executeUpdateCategory = async (categoryId, updatePayload, fallbackName) => {
    setFormLoading(true)
    setFormError(null)
    try {
      const updated = await updateCategory(categoryId, updatePayload)
      await syncCategoriesFromBackend()
      toast.success(`Category "${updated?.name || fallbackName}" updated successfully.`)
    } catch (err) {
      const parsed = extractCatalogApiError(err, 'Operation failed')
      setFormError(parsed.message)
      toast.error(parsed.message)
    } finally {
      setFormLoading(false)
      setPendingEditDeactivate(null)
    }
  }

  // Form Submission (Create or Update against Real Backend API)
  const handleSubmitForm = async (e) => {
    e.preventDefault()
    if (!canEdit) return

    const trimmedName = formName.trim()
    const cleanSlug = formSlug.trim().toLowerCase()
    const trimmedDescription = formDescription.trim()
    const ratePct = formCommission.trim() === '' ? NaN : parseFloat(formCommission)
    const rateDecimal = Number.isFinite(ratePct) ? Number((ratePct / 100).toFixed(4)) : NaN

    if (trimmedName.length < 2 || trimmedName.length > 100) {
      setFormError('Category name must be between 2 and 100 characters')
      return
    }
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(cleanSlug)) {
      setFormError('URL slug must contain only lowercase letters, numbers, and hyphens')
      return
    }
    if (trimmedDescription.length > MAX_DESCRIPTION_LENGTH) {
      setFormError(`Description cannot exceed ${MAX_DESCRIPTION_LENGTH} characters`)
      return
    }
    if (!Number.isFinite(rateDecimal) || rateDecimal < 0 || rateDecimal > 0.5) {
      setFormError('Platform commission rate must be between 0% and 50%')
      return
    }
    if (formNodeType === 'SUBCATEGORY' && !formParentId) {
      setFormError('Please select a parent category reference')
      return
    }
    if (
      !isEditing &&
      formNodeType === 'SUBCATEGORY' &&
      selectedParentRoot &&
      !selectedParentRoot.active
    ) {
      setFormError('Cannot create a subcategory under an inactive parent category')
      return
    }
    if (
      isEditing &&
      formNodeType === 'SUBCATEGORY' &&
      formVisiblePublic &&
      selectedParentRoot &&
      !selectedParentRoot.active
    ) {
      setFormError('Cannot activate a subcategory while its parent category is inactive')
      return
    }

    if (isEditing) {
      const updatePayload = {
        name: trimmedName,
        slug: cleanSlug,
        description: trimmedDescription || null,
        icon: effectiveIconObj.id,
        commissionRate: rateDecimal,
        active: formVisiblePublic,
      }
      // Require the same explicit confirmation when Edit form transitions Active -> Inactive
      if (selectedCategory?.active && !formVisiblePublic) {
        setPendingEditDeactivate({
          id: selectedId,
          name: selectedCategory.name || trimmedName,
          parentId: selectedCategory.parentId || null,
          payload: updatePayload,
        })
        return
      }
      await executeUpdateCategory(selectedId, updatePayload, trimmedName)
      return
    }

    setFormLoading(true)
    setFormError(null)

    try {
      const createPayload = {
        name: trimmedName,
        slug: cleanSlug,
        description: trimmedDescription || null,
        icon: effectiveIconObj.id,
        parentId: formNodeType === 'SUBCATEGORY' ? formParentId : null,
        commissionRate: rateDecimal,
        active: formVisiblePublic,
      }
      const created = await createCategory(createPayload)
      await syncCategoriesFromBackend()
      if (createPayload.parentId) {
        setExpandedNodes((prev) => ({ ...prev, [createPayload.parentId]: true }))
      }
      toast.success(
        `Category "${created?.name || trimmedName}" created${!formVisiblePublic ? ' as Inactive' : ''}.`
      )
      handleResetNewCategory()
    } catch (err) {
      const parsed = extractCatalogApiError(err, 'Operation failed')
      setFormError(parsed.message)
      toast.error(parsed.message)
    } finally {
      setFormLoading(false)
    }
  }

  // Filter categories by search term and tabs while preserving each root's true totalSubCount
  const filteredCategories = useMemo(() => {
    const term = searchTerm.toLowerCase().trim()
    return categories
      .map((root) => {
        const allRootSubs = root.subcategories || []
        const rootMatchesTab =
          activeTab === 'ALL' ||
          activeTab === 'ROOT' ||
          (activeTab === 'ACTIVE' && root.active) ||
          (activeTab === 'INACTIVE' && !root.active)
        const rootMatchesSearch =
          !term ||
          root.name.toLowerCase().includes(term) ||
          root.slug.toLowerCase().includes(term) ||
          (root.description || '').toLowerCase().includes(term)

        const visibleSubs = allRootSubs.filter((sub) => {
          const subMatchesTab =
            activeTab === 'ALL' ||
            activeTab === 'SUB' ||
            (activeTab === 'ACTIVE' && sub.active) ||
            (activeTab === 'INACTIVE' && !sub.active)
          const subMatchesSearch =
            !term ||
            sub.name.toLowerCase().includes(term) ||
            sub.slug.toLowerCase().includes(term) ||
            (sub.description || '').toLowerCase().includes(term)
          return subMatchesTab && subMatchesSearch
        })

        if (activeTab === 'SUB') {
          return visibleSubs.length > 0
            ? {
                ...root,
                totalSubCount: allRootSubs.length,
                visibleSubcategories: visibleSubs,
                hideRoot: true,
              }
            : null
        }

        if (activeTab === 'ROOT') {
          return rootMatchesSearch
            ? {
                ...root,
                totalSubCount: allRootSubs.length,
                visibleSubcategories: [],
                hideRoot: false,
              }
            : null
        }

        if ((rootMatchesTab && rootMatchesSearch) || visibleSubs.length > 0) {
          return {
            ...root,
            totalSubCount: allRootSubs.length,
            visibleSubcategories: visibleSubs,
            hideRoot: false,
          }
        }
        return null
      })
      .filter(Boolean)
  }, [categories, activeTab, searchTerm])

  const isFiltered = activeTab !== 'ALL' || Boolean(searchTerm.trim())
  const shownRoots = filteredCategories.filter((c) => !c.hideRoot).length
  const shownSubs = filteredCategories.reduce(
    (sum, c) => sum + (c.visibleSubcategories?.length || 0),
    0
  )

  const breadcrumbs = entry
    ? [entry.section, entry.group?.label, entry.label].filter(Boolean)
    : ['Management', 'Categories']

  const addNewAction = canEdit ? (
    <button type="button" className={styles.btnAddNew} onClick={handleResetNewCategory}>
      <span className={styles.btnPlus}>+</span>
      <span>Add New Category</span>
    </button>
  ) : null

  const isInitialLoading = syncStatus === 'loading' && categories.length === 0
  const hasAnyActiveParent = categories.some((root) => root.active)

  const activeConfirmDeactivate = confirmDeactivateItem || pendingEditDeactivate
  const isEditDeactivateConfirm = Boolean(pendingEditDeactivate)
  const confirmDeactivateMessage = activeConfirmDeactivate
    ? activeConfirmDeactivate.parentId
      ? `"${activeConfirmDeactivate.name}" will be hidden from the public marketplace catalog. Existing listings using it will not be bookable until reactivated.`
      : `"${activeConfirmDeactivate.name}" will be hidden from the public marketplace catalog. Note: root categories with active subcategories must have all subcategories deactivated first.`
    : ''

  return (
    <AdminPage
      title="Service Categories & Commission"
      subtitle="Manage platform category hierarchy, commission rates, subcategory mappings, and marketplace visibility."
      breadcrumbs={breadcrumbs}
      actions={addNewAction}
      plain
    >
      <div className={styles.dashboardBody}>
        {/* Backend Error Banner */}
        {syncError && (
          <div className={styles.connectionBanner} role="alert">
            <span>⚠️ {syncError}</span>
            <button
              type="button"
              className={styles.retryBtn}
              disabled={syncStatus === 'loading'}
              onClick={() => syncCategoriesFromBackend()}
            >
              {syncStatus === 'loading' ? 'Retrying...' : 'Retry Sync'}
            </button>
          </div>
        )}

        {/* 3 Live Metric KPI Cards */}
        <div className={styles.metricsGrid}>
          {/* Card 1: Avg Commission (Active) */}
          <div
            className={`${styles.metricCard} ${isInitialReveal ? styles.revealTopItem : ''}`}
            style={isInitialReveal ? { animationDelay: '0ms' } : undefined}
          >
            <div className={styles.metricTop}>
              <span className={styles.metricLabel}>AVG COMMISSION (ACTIVE)</span>
              <div className={styles.metricIconBoxGreen}>
                <svg className={styles.trendArrow} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </div>
            </div>
            {isInitialLoading ? (
              <div className={styles.skeletonMetric} aria-label="Loading average commission" />
            ) : (
              <>
                <div className={styles.metricMiddle}>
                  <span className={styles.metricBig}>{kpiMetrics.avgCommissionPct}%</span>
                  <span className={styles.metricBadgeGreen}>
                    {kpiMetrics.hasMultipleRates
                      ? `Range ${kpiMetrics.minCommissionPct}%–${kpiMetrics.maxCommissionPct}%`
                      : kpiMetrics.activeCount > 0
                        ? 'Uniform Rate'
                        : 'No Active Rates'}
                  </span>
                </div>
                <div className={styles.metricBottom}>
                  Across {kpiMetrics.activeCount} Active {kpiMetrics.activeCount === 1 ? 'Category' : 'Categories'}
                </div>
              </>
            )}
          </div>

          {/* Card 2: Catalog Hierarchy */}
          <div
            className={`${styles.metricCard} ${isInitialReveal ? styles.revealTopItem : ''}`}
            style={isInitialReveal ? { animationDelay: '130ms' } : undefined}
          >
            <div className={styles.metricTop}>
              <span className={styles.metricLabel}>CATALOG HIERARCHY</span>
              <div className={styles.metricIconBoxBlue}>
                <svg className={styles.trendArrow} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path d="M4 6h16M4 12h10M4 18h14" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </div>
            </div>
            {isInitialLoading ? (
              <div className={styles.skeletonMetric} aria-label="Loading catalog hierarchy" />
            ) : (
              <>
                <div className={styles.metricMiddle}>
                  <span className={styles.metricBig}>{kpiMetrics.totalRoots}</span>
                  <span className={styles.metricSubtier}>
                    Root {kpiMetrics.totalRoots === 1 ? 'Category' : 'Categories'} • {kpiMetrics.totalSubs}{' '}
                    {kpiMetrics.totalSubs === 1 ? 'Subcategory' : 'Subcategories'}
                  </span>
                </div>
                <div className={styles.metricBottom}>
                  {kpiMetrics.totalNodes} Total {kpiMetrics.totalNodes === 1 ? 'Node' : 'Nodes'} in Marketplace Tree
                </div>
              </>
            )}
          </div>

          {/* Card 3: Active vs Inactive Visibility */}
          <div
            className={`${styles.metricCard} ${isInitialReveal ? styles.revealTopItem : ''}`}
            style={isInitialReveal ? { animationDelay: '260ms' } : undefined}
          >
            <div className={styles.metricTop}>
              <span className={styles.metricLabel}>CATALOG VISIBILITY</span>
              <div className={styles.metricIconBoxGreen}>
                <svg className={styles.trendArrow} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </div>
            </div>
            {isInitialLoading ? (
              <div className={styles.skeletonMetric} aria-label="Loading visibility metrics" />
            ) : (
              <>
                <div className={styles.metricMiddle}>
                  <span className={styles.metricBig}>{kpiMetrics.activeCount}</span>
                  <span className={styles.metricBadgeNeutral}>
                    {kpiMetrics.inactiveCount} Inactive
                  </span>
                </div>
                <div className={styles.metricBottom}>
                  {kpiMetrics.activeSharePct}% visible in public consumer catalog
                </div>
              </>
            )}
          </div>
        </div>

        {/* 2-Column Split Layout */}
        <div className={styles.splitLayout}>
          {/* LEFT COLUMN: Tree List & Filters */}
          <div className={styles.leftCol}>
            {/* Tabs */}
            <div className={styles.tabsRow} role="tablist" aria-label="Filter categories by type or status">
              {[
                { id: 'ALL', label: 'All' },
                { id: 'ROOT', label: 'Root Categories' },
                { id: 'SUB', label: 'Subcategories' },
                { id: 'ACTIVE', label: 'Active' },
                { id: 'INACTIVE', label: 'Inactive' },
              ].map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  role="tab"
                  aria-selected={activeTab === tab.id}
                  className={`${styles.tabPill} ${activeTab === tab.id ? styles.tabPillActive : ''}`}
                  onClick={() => setActiveTab(tab.id)}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            {/* Search Bar */}
            <div className={styles.searchBarWrapper}>
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="#64748b"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                style={{ position: 'absolute', left: '12px', pointerEvents: 'none' }}
                aria-hidden="true"
              >
                <circle cx="11" cy="11" r="8" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
              <input
                type="text"
                className={styles.treeSearchInput}
                placeholder="Filter categories by name, slug, or description..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
              />
              {searchTerm && (
                <button
                  type="button"
                  className={styles.clearSearchBtn}
                  onClick={() => setSearchTerm('')}
                  aria-label="Clear search"
                >
                  ✕
                </button>
              )}
            </div>

            {/* Display Meta & Expand/Collapse Toggle */}
            <div className={styles.metaRow}>
              <span>
                {isFiltered
                  ? activeTab === 'SUB'
                    ? `Showing ${shownSubs} of ${kpiMetrics.totalSubs} Subcategories`
                    : `Showing ${shownRoots} of ${kpiMetrics.totalRoots} Roots • ${shownSubs} of ${kpiMetrics.totalSubs} Subcategories`
                  : `Displaying ${kpiMetrics.totalRoots} Root Nodes • ${kpiMetrics.totalSubs} Subcategories`}
              </span>
              {activeTab !== 'SUB' && activeTab !== 'ROOT' && categories.length > 0 && (
                <button type="button" className={styles.btnToggleExpand} onClick={handleToggleExpandAll}>
                  Expand All / Collapse All
                </button>
              )}
            </div>

            {/* Dotted "New Category" Quick Add Box */}
            {canEdit && (
              <div
                className={`${styles.newCategoryDotted} ${!isEditing && formNodeType === 'ROOT' ? styles.newCategoryDottedActive : ''} ${isInitialReveal ? styles.revealLeftItem : ''}`}
                style={isInitialReveal ? { animationDelay: '70ms' } : undefined}
                onClick={handleResetNewCategory}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    handleResetNewCategory()
                  }
                }}
              >
                <div className={styles.dottedLeft}>
                  <div className={styles.plusBox} aria-hidden="true">
                    <svg
                      width="18"
                      height="18"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2.5"
                      strokeLinecap="round"
                    >
                      <line x1="12" y1="5" x2="12" y2="19" />
                      <line x1="5" y1="12" x2="19" y2="12" />
                    </svg>
                  </div>
                  <div>
                    <div className={styles.dottedTitle}>
                      <span>New Category</span>
                      <span className={styles.pingWrapper}>
                        <span className={styles.pingRing} />
                        <span className={styles.pingDot} />
                      </span>
                    </div>
                    <div className={styles.dottedSub}>Configuring details & rules...</div>
                  </div>
                </div>
                <div className={styles.dottedRight}>
                  <span>-- %</span>
                  <span className={styles.statusDotGray} />
                </div>
              </div>
            )}

            {/* Categories Tree List */}
            <div className={styles.treeList}>
              {isInitialLoading ? (
                <>
                  {[0, 1, 2].map((idx) => (
                    <div key={idx} className={styles.skeletonRow} aria-label="Loading categories">
                      <div className={styles.skeletonIcon} />
                      <div className={styles.skeletonTextCol}>
                        <div className={styles.skeletonBar} style={{ width: '58%' }} />
                        <div className={styles.skeletonBar} style={{ width: '34%', height: '10px' }} />
                      </div>
                      <div className={styles.skeletonPill} />
                    </div>
                  ))}
                </>
              ) : (
                <>
                  {filteredCategories.map((root, rootIndex) => {
                    const isExpanded = Boolean(expandedNodes[root.id])
                    const isSelected = selectedId === root.id
                    const visibleSubcategories = root.visibleSubcategories || []
                    const rootRate =
                      root.commissionRate != null ? (Number(root.commissionRate) * 100).toFixed(1) : '10.0'
                    const rootIconImg = findIconOption(root)?.img || root.iconImg
                    const canExpandRoot =
                      activeTab !== 'ROOT' && (root.totalSubCount > 0 || canEdit)
                    const shouldShowSubList =
                      activeTab === 'SUB'
                        ? visibleSubcategories.length > 0
                        : activeTab !== 'ROOT' &&
                          (isExpanded || Boolean(searchTerm.trim() && visibleSubcategories.length > 0)) &&
                          (visibleSubcategories.length > 0 || canEdit)

                    return (
                      <div
                        key={root.id}
                        className={`${styles.rootCard} ${isSelected ? styles.rootCardSelected : ''} ${isInitialReveal ? styles.revealLeftItem : ''}`}
                        style={
                          isInitialReveal
                            ? { animationDelay: `${160 + Math.min(rootIndex, 8) * 100}ms` }
                            : undefined
                        }
                      >
                        {/* Root Node Header */}
                        {!root.hideRoot && (
                          <div className={styles.rootHeader}>
                            <div className={styles.rootLeft}>
                              {canExpandRoot ? (
                                <button
                                  type="button"
                                  className={styles.chevronBtn}
                                  onClick={(e) => {
                                    e.stopPropagation()
                                    toggleExpand(root.id)
                                  }}
                                  aria-label={isExpanded ? `Collapse ${root.name}` : `Expand ${root.name}`}
                                  aria-expanded={isExpanded}
                                >
                                  <svg
                                    className={`${styles.chevronIcon} ${isExpanded ? styles.chevronIconExpanded : ''}`}
                                    width="14"
                                    height="14"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="#64748b"
                                    strokeWidth="2.5"
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                  >
                                    <polyline points="9 18 15 12 9 6" />
                                  </svg>
                                </button>
                              ) : (
                                <span style={{ width: '14px', display: 'inline-block' }} />
                              )}

                              <button
                                type="button"
                                className={styles.nodeSelectBtn}
                                onClick={() => handleSelectCategory(root, false)}
                                aria-pressed={isSelected}
                              >
                                <div className={styles.iconSquare}>
                                  {rootIconImg ? (
                                    <img src={rootIconImg} alt="" width="34" height="34" />
                                  ) : (
                                    <span>📁</span>
                                  )}
                                </div>

                                <div className={styles.rootInfo}>
                                  <div className={styles.rootNameRow}>
                                    <span className={styles.rootName}>{root.name}</span>
                                    {isSelected ? (
                                      <span className={styles.badgeEditing}>Editing</span>
                                    ) : (
                                      <span className={styles.badgeRoot}>Root Category</span>
                                    )}
                                  </div>
                                  <span className={styles.rootSubCount}>
                                    {root.totalSubCount}{' '}
                                    {root.totalSubCount === 1 ? 'Subcategory' : 'Subcategories'}
                                  </span>
                                </div>
                              </button>
                            </div>

                            <div className={styles.rootRight}>
                              <span className={styles.ratePill}>{rootRate}%</span>
                              <button
                                type="button"
                                className={styles.statusDotBtn}
                                disabled={!canEdit || togglingId === root.id}
                                onClick={(e) => handleStatusPillClick(root, e)}
                                title={
                                  canEdit
                                    ? root.active
                                      ? 'Status: Active (Click to deactivate)'
                                      : 'Status: Inactive (Click to reactivate)'
                                    : root.active
                                      ? 'Status: Active'
                                      : 'Status: Inactive'
                                }
                              >
                                <span className={root.active ? styles.activeDot : styles.statusDotGray} />
                                <span className={styles.statusLabelText}>
                                  {togglingId === root.id ? 'Updating...' : root.active ? 'Active' : 'Inactive'}
                                </span>
                              </button>
                            </div>
                          </div>
                        )}

                        {/* Subcategories under Root */}
                        {shouldShowSubList && (
                          <div className={styles.subList}>
                            {visibleSubcategories.map((sub) => {
                              const isSubSelected = selectedId === sub.id
                              const subRate =
                                sub.commissionRate != null
                                  ? (Number(sub.commissionRate) * 100).toFixed(1)
                                  : rootRate
                              const subIconImg = rootIconImg || findIconOption(sub)?.img || sub.iconImg
                              const rateMatchLabel =
                                subRate === rootRate ? 'Matches parent rate' : 'Custom rate'

                                return (
                                  <div
                                    key={sub.id}
                                    className={`${styles.subCard} ${isSubSelected ? styles.subCardSelected : ''}`}
                                  >
                                    <button
                                      type="button"
                                      className={`${styles.subLeft} ${styles.nodeSelectBtn}`}
                                      onClick={() => handleSelectCategory(sub, true, root)}
                                      aria-pressed={isSubSelected}
                                    >
                                      <div className={styles.subIconSquare}>
                                        {subIconImg ? (
                                          <img src={subIconImg} alt="" width="28" height="28" />
                                        ) : (
                                          <span>↳</span>
                                        )}
                                      </div>
                                      <div className={styles.subInfo}>
                                        <div className={styles.subNameRow}>
                                          <span className={styles.subName}>{sub.name}</span>
                                          {isSubSelected && <span className={styles.badgeEditing}>Editing</span>}
                                        </div>
                                        <div className={styles.subMetaLine}>
                                          <span className={styles.subDetails}>
                                            {activeTab === 'SUB' ? root.name : 'Subcategory'}
                                          </span>
                                          <span className={styles.subDetails} aria-hidden="true">•</span>
                                          <span className={styles.subDetails}>{rateMatchLabel}</span>
                                        </div>
                                      </div>
                                    </button>

                                  <div className={styles.rootRight}>
                                    <span className={styles.ratePill}>{subRate}%</span>
                                    <button
                                      type="button"
                                      className={styles.statusDotBtn}
                                      disabled={!canEdit || togglingId === sub.id}
                                      onClick={(e) => handleStatusPillClick(sub, e)}
                                      title={
                                        canEdit
                                          ? sub.active
                                            ? 'Status: Active (Click to deactivate)'
                                            : 'Status: Inactive (Click to reactivate)'
                                          : sub.active
                                            ? 'Status: Active'
                                            : 'Status: Inactive'
                                      }
                                    >
                                      <span className={sub.active ? styles.activeDot : styles.statusDotGray} />
                                      <span className={styles.statusLabelText}>
                                        {togglingId === sub.id ? 'Updating...' : sub.active ? 'Active' : 'Inactive'}
                                      </span>
                                    </button>
                                  </div>
                                </div>
                              )
                            })}
                            {canEdit && activeTab !== 'SUB' && (
                              <button
                                type="button"
                                className={styles.btnAddSubInline}
                                disabled={!root.active}
                                title={
                                  root.active
                                    ? `Create a subcategory under ${root.name}`
                                    : `Activate "${root.name}" before adding a subcategory`
                                }
                                onClick={(e) => {
                                  e.stopPropagation()
                                  handleStartAddSubcategory(root)
                                }}
                              >
                                {root.active
                                  ? `+ Add Subcategory Under ${root.name}`
                                  : `Activate ${root.name} to add subcategories`}
                              </button>
                            )}
                          </div>
                        )}
                      </div>
                    )
                  })}
                  {filteredCategories.length === 0 && (
                    <div className={styles.emptyTreeState}>
                      {syncStatus === 'error'
                        ? 'Unable to display categories right now. Click "Retry Sync" above once connected.'
                        : categories.length === 0
                          ? 'No categories found in the database yet. Use "+ Add New Category" to add your first category.'
                          : 'No categories match the current search or filter.'}
                    </div>
                  )}
                </>
              )}
            </div>
          </div>

          {/* RIGHT COLUMN: Create / Edit Category Form */}
          <div className={`${styles.rightCol} ${isInitialReveal ? styles.revealRightCol : ''}`}>
            <div className={styles.formHeader}>
              <div>
                <h2 className={styles.formTitle}>
                  {isEditing
                    ? 'Category Details & Commission Rules'
                    : formNodeType === 'SUBCATEGORY'
                      ? 'Create New Subcategory'
                      : 'Create New Service Category'}
                </h2>
                <p className={styles.formSubtitle}>
                  Configure hierarchy attributes, SEO canonical slug, description, and fee deductions.
                </p>
              </div>
              <span className={styles.formHeaderBadge}>
                {isEditing
                  ? formNodeType === 'SUBCATEGORY'
                    ? 'Editing Subcategory'
                    : 'Editing Root Category'
                  : formNodeType === 'SUBCATEGORY'
                    ? 'Creating Subcategory'
                    : 'Creating Root Category'}
              </span>
            </div>

            {formError && (
              <div className={styles.formErrorBox} role="alert">
                ⚠️ {formError}
              </div>
            )}

            <form onSubmit={handleSubmitForm} className={styles.formSection}>
              <fieldset disabled={!canEdit} className={styles.formFieldset}>
                {/* Category Name */}
                <div className={styles.inputGroup}>
                  <label htmlFor="category-name-input" className={styles.inputLabel}>
                    Category Name <span className={styles.redStar}>*</span>
                  </label>
                  <input
                    id="category-name-input"
                    type="text"
                    className={styles.textInput}
                    placeholder="e.g. Solar & Renewable Energy, Pest Control, Painting..."
                    value={formName}
                    onChange={handleNameChange}
                    required
                  />
                  <span className={styles.inputHelper}>Public display name (2–100 characters)</span>
                </div>

                {/* Url Slug Canonical Path */}
                <div className={styles.inputGroup}>
                  <label htmlFor="category-slug-input" className={styles.inputLabel}>
                    URL Slug Canonical Path <span className={styles.redStar}>*</span>
                  </label>
                  <div className={styles.slugContainer}>
                    <span className={styles.slugPrefix}>localserve.lk/categories/</span>
                    <input
                      id="category-slug-input"
                      type="text"
                      className={styles.slugInput}
                      placeholder="auto-generated-from-name"
                      value={formSlug}
                      onChange={(e) => {
                        setFormSlug(e.target.value)
                        setFormSlugAuto(false)
                      }}
                      required
                    />
                  </div>
                </div>

                {/* Optional Description (Synced with CreateCategoryRequest & UpdateCategoryRequest) */}
                <div className={styles.inputGroup}>
                  <div className={styles.labelRow}>
                    <label htmlFor="category-description-input" className={styles.inputLabel}>
                      Category Description <span className={styles.optionalTag}>(Optional)</span>
                    </label>
                    <span className={styles.charCounter}>
                      {formDescription.length}/{MAX_DESCRIPTION_LENGTH}
                    </span>
                  </div>
                  <textarea
                    id="category-description-input"
                    className={styles.textareaInput}
                    rows={3}
                    maxLength={MAX_DESCRIPTION_LENGTH}
                    placeholder="Brief summary of services covered under this category..."
                    value={formDescription}
                    onChange={(e) => setFormDescription(e.target.value)}
                  />
                </div>

                {/* Hierarchy Node Type & Parent Category Reference */}
                <div className={styles.twoColRow}>
                  {/* Node Type */}
                  <div className={styles.inputGroup}>
                    <span className={styles.inputLabel}>
                      Hierarchy Node Type <span className={styles.redStar}>*</span>
                    </span>
                    <div className={styles.radioRow}>
                      <label
                        className={`${styles.radioLabel} ${isEditing ? styles.radioLabelDisabled : ''}`}
                      >
                        <input
                          type="radio"
                          name="nodeType"
                          value="ROOT"
                          checked={formNodeType === 'ROOT'}
                          disabled={isEditing}
                          onChange={() => {
                            setFormNodeType('ROOT')
                            setFormParentId('')
                          }}
                        />
                        <span>Root Category</span>
                      </label>

                      <label
                        className={`${styles.radioLabel} ${isEditing || (!isEditing && !hasAnyActiveParent) ? styles.radioLabelDisabled : ''}`}
                      >
                        <input
                          type="radio"
                          name="nodeType"
                          value="SUBCATEGORY"
                          checked={formNodeType === 'SUBCATEGORY'}
                          disabled={isEditing || (!isEditing && !hasAnyActiveParent)}
                          onChange={() => {
                            setFormNodeType('SUBCATEGORY')
                            if (!formParentId && categories.length > 0) {
                              const activeRoot = categories.find((c) => c.active && c.id !== selectedId)
                              if (activeRoot) {
                                setFormParentId(activeRoot.id)
                                setSelectedIconObj(findIconOption(activeRoot) || PREDEFINED_ICONS[1])
                                setFormCommission(toCommissionPercentString(activeRoot.commissionRate))
                              }
                            }
                          }}
                        />
                        <span>Subcategory</span>
                        <span className={styles.badgeInheritsTiny}>Inherits Icon</span>
                      </label>
                    </div>
                    {isEditing && (
                      <span className={styles.lockedFieldNote}>
                        Hierarchy level and parent cannot be changed after creation.
                      </span>
                    )}
                  </div>

                  {/* Parent Category Reference */}
                  <div className={styles.inputGroup}>
                    <label htmlFor="parent-category-select" className={styles.inputLabel}>
                      Parent Category Reference <span className={styles.redStar}>*</span>
                    </label>
                    <select
                      id="parent-category-select"
                      className={styles.selectBox}
                      value={formParentId}
                      onChange={(e) => {
                        const nextParentId = e.target.value
                        setFormParentId(nextParentId)
                        const nextParent = categories.find((c) => c.id === nextParentId)
                        if (nextParent) {
                          setSelectedIconObj(findIconOption(nextParent) || PREDEFINED_ICONS[1])
                          if (!isEditing && nextParent.commissionRate != null) {
                            setFormCommission(toCommissionPercentString(nextParent.commissionRate))
                          }
                        }
                      }}
                      disabled={formNodeType === 'ROOT' || isEditing}
                    >
                      <option value="">None (Top-Level Root Node)</option>
                      {categories
                        .filter((c) => c.id !== selectedId)
                        .map((root) => (
                          <option
                            key={root.id}
                            value={root.id}
                            disabled={!isEditing && !root.active}
                          >
                            {root.active ? root.name : `${root.name} (Inactive)`}
                          </option>
                        ))}
                    </select>
                    {formNodeType === 'SUBCATEGORY' && selectedParentRoot && (
                      <span className={styles.inheritHelperGreen}>
                        ✓ Inherits parent icon and baseline fee
                      </span>
                    )}
                  </div>
                </div>

                {/* Platform Commission Rate */}
                <div className={styles.inputGroup}>
                  <label htmlFor="commission-rate-input" className={styles.inputLabel}>
                    Platform Commission Rate (%) <span className={styles.redStar}>*</span>
                  </label>
                  <div className={styles.commissionWrapper}>
                    <input
                      id="commission-rate-input"
                      type="number"
                      step="0.01"
                      min="0"
                      max="50"
                      className={styles.commissionInput}
                      value={formCommission}
                      onChange={(e) => setFormCommission(e.target.value)}
                      required
                    />
                    <span className={styles.commissionSuffix}>%</span>
                  </div>
                  <span className={styles.inputHelper}>Allowed range: 0% to 50%</span>
                </div>

                {/* Category Vector Icon (Svg) Section */}
                {formNodeType === 'SUBCATEGORY' ? (
                  <div className={styles.inputGroup}>
                    <div className={styles.iconSectionHeaderRow}>
                      <div>
                        <span className={styles.inputLabel}>
                          Category Vector Icon (SVG) <span className={styles.redStar}>*</span>
                        </span>
                        <p className={styles.iconCardDesc}>
                          Root icon selected • Subcategories inherit this icon automatically (change in parent)
                        </p>
                      </div>
                      <span className={styles.badgeAutoInherit}>Auto-Inheriting from Parent</span>
                    </div>

                    <div className={styles.lockedIconCard}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
                        <img
                          src={effectiveIconObj.img || iconCatSolar}
                          alt={effectiveIconObj.name}
                          width="48"
                          height="48"
                        />
                        <div className={styles.iconCardInfo}>
                          <span className={styles.iconNameBold}>
                            Selected Icon: {effectiveIconObj.name}
                          </span>
                          <span className={styles.iconCardDesc}>
                            {selectedParentRoot
                              ? `Inherited from ${selectedParentRoot.name} • Edit in Root Category`
                              : 'Select a Parent Category above to inherit its icon'}
                          </span>
                        </div>
                      </div>
                      <span className={styles.lockedBadge}>Locked</span>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className={styles.inputGroup}>
                      <span className={styles.inputLabel}>
                        Category Vector Icon (SVG) <span className={styles.redStar}>*</span>
                      </span>
                      <div className={styles.iconPreviewCard}>
                        <div className={styles.iconBigBox}>
                          <img
                            src={activeIconObj.img || iconCatSolar}
                            alt={activeIconObj.name}
                            width="62"
                            height="62"
                          />
                        </div>

                        <div className={styles.iconCardInfo}>
                          <div className={styles.iconTitleRow}>
                            <span className={styles.iconNameBold}>{activeIconObj.name}</span>
                            <span className={styles.badgeSelectedIcon}>Selected Predefined Icon</span>
                          </div>
                          <p className={styles.iconCardDesc}>
                            Select from 18 curated 3D pastel vector service icons for your category.
                          </p>
                          <p className={styles.iconCardNote}>
                            Subcategories automatically inherit and share their parent category&apos;s vector icon,
                            maintaining consistent visual identity across discovery.
                          </p>
                        </div>
                      </div>
                    </div>

                    {/* Popular Predefined Icons Grid (18 Pure-Vector SVGs) */}
                    <div className={styles.popularIconsRow}>
                      <div className={styles.popularHeader}>
                        <span className={styles.popularTitle}>Popular Predefined Icons</span>
                        <span className={styles.popularSelectedTag}>
                          Selected: {activeIconObj.name.split(' ')[0]}
                        </span>
                      </div>

                      <div className={styles.iconPillBar}>
                        {PREDEFINED_ICONS.map((item) => (
                          <button
                            key={item.id}
                            type="button"
                            className={`${styles.iconOptionBtn} ${activeIconObj.id === item.id ? styles.iconOptionSelected : ''}`}
                            onClick={() => setSelectedIconObj(item)}
                            title={item.name}
                            aria-label={item.name}
                            aria-pressed={activeIconObj.id === item.id}
                          >
                            <img src={item.img} alt="" width="46" height="46" />
                          </button>
                        ))}
                      </div>
                    </div>
                  </>
                )}

                {/* Category Status & Marketplace Visibility Checkbox */}
                <div className={styles.statusToggleContainer}>
                  <label htmlFor="category-visible-checkbox" className={styles.visibleCheckboxLabel}>
                    <input
                      id="category-visible-checkbox"
                      type="checkbox"
                      className={styles.srOnlyCheckbox}
                      checked={formVisiblePublic}
                      disabled={!canEdit}
                      onChange={(e) => setFormVisiblePublic(e.target.checked)}
                    />
                    <span
                      className={`${styles.customCheckbox} ${formVisiblePublic ? styles.customCheckboxChecked : ''}`}
                      aria-hidden="true"
                    >
                      {formVisiblePublic && (
                        <svg
                          width="13"
                          height="13"
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="#ffffff"
                          strokeWidth="3.5"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                        >
                          <polyline points="20 6 9 17 4 12" />
                        </svg>
                      )}
                    </span>

                    <div className={styles.checkboxTextGroup}>
                      <div className={styles.checkboxTitleRow}>
                        <span className={styles.checkboxTitle}>
                          Immediately visible in public consumer catalog once published
                        </span>
                        <span
                          className={
                            formVisiblePublic
                              ? styles.badgeActiveStatus
                              : styles.badgeDraftStatus
                          }
                        >
                          {formVisiblePublic ? 'Active' : 'Inactive'}
                        </span>
                      </div>
                      <div className={styles.checkboxSub}>
                        {formVisiblePublic
                          ? 'Published live — Customers can browse and book services in this category'
                          : 'Hidden from public browse — Saved as an inactive category'}
                      </div>
                    </div>
                  </label>
                </div>

                {/* Form Action Buttons */}
                {canEdit && (
                  <div className={styles.formActionsRow}>
                    <button
                      type="button"
                      className={styles.btnCancel}
                      onClick={handleResetNewCategory}
                      disabled={formLoading}
                    >
                      Cancel
                    </button>
                    <button type="submit" className={styles.btnCreatePublish} disabled={formLoading}>
                      {!isEditing && <span className={styles.btnPlus}>+</span>}
                      <span>
                        {formLoading
                          ? 'Saving...'
                          : isEditing
                            ? 'Save Category Changes'
                            : formNodeType === 'SUBCATEGORY'
                              ? 'Create & Publish Subcategory'
                              : 'Create & Publish Category'}
                      </span>
                    </button>
                  </div>
                )}
              </fieldset>
            </form>
          </div>
        </div>
      </div>

      {/* Shared Accessible Confirmation Dialog for Category Deactivation (Status Pill & Edit Form) */}
      <ConfirmDialog
        open={Boolean(activeConfirmDeactivate)}
        title={
          activeConfirmDeactivate
            ? `Deactivate "${activeConfirmDeactivate.name}"?`
            : 'Deactivate category?'
        }
        message={confirmDeactivateMessage}
        confirmLabel="Deactivate"
        cancelLabel="Cancel"
        variant="destructive"
        loading={
          isEditDeactivateConfirm
            ? formLoading
            : Boolean(confirmDeactivateItem && togglingId === confirmDeactivateItem.id)
        }
        onConfirm={() => {
          if (pendingEditDeactivate) {
            executeUpdateCategory(
              pendingEditDeactivate.id,
              pendingEditDeactivate.payload,
              pendingEditDeactivate.name
            )
          } else if (confirmDeactivateItem) {
            executeToggleCategoryActive(confirmDeactivateItem, false)
          }
        }}
        onCancel={() => {
          if (pendingEditDeactivate) {
            setPendingEditDeactivate(null)
            setFormVisiblePublic(true)
          }
          setConfirmDeactivateItem(null)
        }}
      />
    </AdminPage>
  )
}
