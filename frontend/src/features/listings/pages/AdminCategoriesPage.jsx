import { useState, useEffect, useMemo } from 'react'
import { AdminPage } from '../../../shared/layouts/admin'
import { useAreaAccess } from '../../disputes-admin/access/useAdminAccess'
import {
  getAdminCategories,
  createCategory,
  updateCategory,
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

function hydrateCategoryIcons(items, parentImg = null) {
  return items.map((item) => {
    const resolvedOption = findIconOption(item)
    const resolvedImg = resolvedOption?.img || parentImg || item.iconImg
    return {
      ...item,
      icon: item.icon || resolvedOption?.id,
      iconImg: resolvedImg,
      subcategories: Array.isArray(item.subcategories)
        ? hydrateCategoryIcons(item.subcategories, resolvedImg)
        : [],
    }
  })
}

// Default seed categories matching the Figma design mockup 100%
const INITIAL_FIGMA_CATEGORIES = [
  {
    id: 'cat-elec',
    name: 'Electrical & Power',
    slug: 'electrical-and-power',
    icon: 'electrician',
    iconImg: iconCatElectrical,
    parentId: null,
    commissionRate: 0.1,
    active: true,
    subcategories: [
      {
        id: 'sub-wiring',
        name: 'Wiring & Rewiring',
        slug: 'wiring-and-rewiring',
        icon: 'electrician',
        iconImg: iconCatElectrical,
        parentId: 'cat-elec',
        commissionRate: 0.1,
        active: true,
      },
      {
        id: 'sub-switch',
        name: 'Switchboards & Circuit Breakers',
        slug: 'switchboards-and-circuit-breakers',
        icon: 'electrician',
        iconImg: iconCatElectrical,
        parentId: 'cat-elec',
        commissionRate: 0.1,
        active: true,
      },
    ],
  },
  {
    id: 'cat-plumb',
    name: 'Plumbing & Water Systems',
    slug: 'plumbing-and-water-systems',
    icon: 'water',
    iconImg: iconCatPlumbing,
    parentId: null,
    commissionRate: 0.1,
    active: true,
    subcategories: [
      {
        id: 'sub-pipe',
        name: 'Pipe Leakage & Repairs',
        slug: 'pipe-leakage-repairs',
        icon: 'water',
        iconImg: iconCatPlumbing,
        parentId: 'cat-plumb',
        commissionRate: 0.1,
        active: true,
      },
      {
        id: 'sub-drain',
        name: 'Drainage & Sewerage Cleaning',
        slug: 'drainage-cleaning',
        icon: 'water',
        iconImg: iconCatPlumbing,
        parentId: 'cat-plumb',
        commissionRate: 0.1,
        active: true,
      },
      {
        id: 'sub-tank',
        name: 'Water Tank & Pump Installation',
        slug: 'water-tank-installation',
        icon: 'water',
        iconImg: iconCatPlumbing,
        parentId: 'cat-plumb',
        commissionRate: 0.1,
        active: true,
      },
      {
        id: 'sub-bath',
        name: 'Bathroom Fitting & Sanware',
        slug: 'bathroom-fitting',
        icon: 'water',
        iconImg: iconCatPlumbing,
        parentId: 'cat-plumb',
        commissionRate: 0.1,
        active: true,
      },
    ],
  },
  {
    id: 'cat-hvac',
    name: 'Air Conditioning & HVAC',
    slug: 'air-conditioning-and-hvac',
    icon: 'temp',
    iconImg: iconCatHvac,
    parentId: null,
    commissionRate: 0.09,
    active: true,
    subcategories: [
      {
        id: 'sub-ac-serv',
        name: 'AC Routine Servicing',
        slug: 'ac-servicing',
        icon: 'temp',
        iconImg: iconCatHvac,
        parentId: 'cat-hvac',
        commissionRate: 0.09,
        active: true,
      },
      {
        id: 'sub-ac-rep',
        name: 'AC Gas Refill & Leak Fixes',
        slug: 'ac-gas-refill',
        icon: 'temp',
        iconImg: iconCatHvac,
        parentId: 'cat-hvac',
        commissionRate: 0.09,
        active: true,
      },
      {
        id: 'sub-ac-inst',
        name: 'Inverter AC Installation',
        slug: 'inverter-ac-installation',
        icon: 'temp',
        iconImg: iconCatHvac,
        parentId: 'cat-hvac',
        commissionRate: 0.09,
        active: true,
      },
    ],
  },
  {
    id: 'cat-carp',
    name: 'Carpentry & Woodwork',
    slug: 'carpentry-and-woodwork',
    icon: 'pencil',
    iconImg: iconCatWoodwork,
    parentId: null,
    commissionRate: 0.08,
    active: true,
    subcategories: [
      {
        id: 'sub-furn',
        name: 'Custom Furniture Making',
        slug: 'custom-furniture',
        icon: 'pencil',
        iconImg: iconCatWoodwork,
        parentId: 'cat-carp',
        commissionRate: 0.08,
        active: true,
      },
      {
        id: 'sub-door',
        name: 'Door & Window Fitting',
        slug: 'door-window-fitting',
        icon: 'pencil',
        iconImg: iconCatWoodwork,
        parentId: 'cat-carp',
        commissionRate: 0.08,
        active: true,
      },
    ],
  },
  {
    id: 'cat-mas',
    name: 'Masonry & Civil Maintenance',
    slug: 'masonry-and-civil-maintenance',
    icon: 'grid',
    iconImg: iconCatCivil,
    parentId: null,
    commissionRate: 0.075,
    active: true,
    subcategories: [
      {
        id: 'sub-tile',
        name: 'Floor & Wall Tiling',
        slug: 'wall-tiling',
        icon: 'grid',
        iconImg: iconCatCivil,
        parentId: 'cat-mas',
        commissionRate: 0.075,
        active: true,
      },
      {
        id: 'sub-plaster',
        name: 'Plastering & Bricklaying',
        slug: 'plastering-bricklaying',
        icon: 'grid',
        iconImg: iconCatCivil,
        parentId: 'cat-mas',
        commissionRate: 0.075,
        active: true,
      },
      {
        id: 'sub-waterproof',
        name: 'Roof Waterproofing',
        slug: 'roof-waterproofing',
        icon: 'grid',
        iconImg: iconCatCivil,
        parentId: 'cat-mas',
        commissionRate: 0.075,
        active: true,
      },
    ],
  },
  {
    id: 'cat-app',
    name: 'Appliance Repair',
    slug: 'appliance-repair',
    icon: 'wrench',
    iconImg: iconCatAppliance,
    parentId: null,
    commissionRate: 0.1,
    active: true,
    subcategories: [
      {
        id: 'sub-fridge',
        name: 'Refrigerator & Deep Freezer',
        slug: 'refrigerator-repair',
        icon: 'wrench',
        iconImg: iconCatAppliance,
        parentId: 'cat-app',
        commissionRate: 0.1,
        active: true,
      },
      {
        id: 'sub-wash',
        name: 'Washing Machine Repair',
        slug: 'washing-machine-repair',
        icon: 'wrench',
        iconImg: iconCatAppliance,
        parentId: 'cat-app',
        commissionRate: 0.1,
        active: true,
      },
      {
        id: 'sub-micro',
        name: 'Microwave & Oven Repair',
        slug: 'microwave-oven-repair',
        icon: 'wrench',
        iconImg: iconCatAppliance,
        parentId: 'cat-app',
        commissionRate: 0.1,
        active: true,
      },
    ],
  },
]

function slugify(text) {
  return text
    .toString()
    .toLowerCase()
    .trim()
    .replace(/&/g, 'and')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

export default function AdminCategoriesPage({ entry } = {}) {
  const areaAccess = useAreaAccess()
  // AreaAccessContext defaults to { area: null, canEdit: false } outside <RequireAreaAccess>.
  // Enforce RBAC strictly when mounted under an admin area; allow editing on standalone dev preview routes.
  const canEdit = areaAccess?.area ? areaAccess.canEdit : true
  const [categories, setCategories] = useState(INITIAL_FIGMA_CATEGORIES)
  const [activeTab, setActiveTab] = useState('ALL')
  const [searchTerm, setSearchTerm] = useState('')
  const [expandedNodes, setExpandedNodes] = useState({ 'cat-elec': true })
  const [selectedId, setSelectedId] = useState(null)
  const [toastMessage, setToastMessage] = useState(null)
  const [isInitialReveal, setIsInitialReveal] = useState(true)

  useEffect(() => {
    const timer = setTimeout(() => setIsInitialReveal(false), 2200)
    return () => clearTimeout(timer)
  }, [])

  // Form State
  const [formName, setFormName] = useState('')
  const [formSlug, setFormSlug] = useState('')
  const [formSlugAuto, setFormSlugAuto] = useState(true)
  const [formNodeType, setFormNodeType] = useState('ROOT') // 'ROOT' | 'SUBCATEGORY'
  const [formParentId, setFormParentId] = useState('')
  const [formCommission, setFormCommission] = useState('10.00')
  const [selectedIconObj, setSelectedIconObj] = useState(PREDEFINED_ICONS[1]) // Sun icon
  const [formVisiblePublic, setFormVisiblePublic] = useState(true)
  const [formLoading, setFormLoading] = useState(false)
  const [formError, setFormError] = useState(null)
  const activeIconObj = findIconOption(selectedIconObj) || selectedIconObj
  const selectedParentRoot =
    formNodeType === 'SUBCATEGORY' ? categories.find((c) => c.id === formParentId) || null : null
  const inheritedParentIconObj = selectedParentRoot
    ? findIconOption(selectedParentRoot) || PREDEFINED_ICONS[1]
    : null
  const effectiveIconObj = inheritedParentIconObj || activeIconObj

  // Fetch real categories from backend on mount (falls back seamlessly to mock)
  useEffect(() => {
    let active = true
    getAdminCategories()
      .then((data) => {
        if (active && Array.isArray(data) && data.length > 0) {
          setCategories(hydrateCategoryIcons(data))
        }
      })
      .catch(() => {
        // Backend not yet running; keep initial mock data
      })
    return () => {
      active = false
    }
  }, [])

  const showToast = (msg) => {
    setToastMessage(msg)
    setTimeout(() => setToastMessage(null), 3500)
  }

  // Handle Form Name Change -> Auto Slug
  const handleNameChange = (e) => {
    const val = e.target.value
    setFormName(val)
    if (formSlugAuto) {
      setFormSlug(slugify(val))
    }
  }

  // Handle Selecting a Category from Tree for Editing
  const handleSelectCategory = (cat, isSub = false) => {
    setSelectedId(cat.id)
    setFormName(cat.name || '')
    setFormSlug(cat.slug || '')
    setFormSlugAuto(false)
    setFormNodeType(isSub ? 'SUBCATEGORY' : 'ROOT')
    setFormParentId(cat.parentId || '')
    const rate = cat.commissionRate != null ? (Number(cat.commissionRate) * 100).toFixed(2) : '10.00'
    setFormCommission(rate)
    setSelectedIconObj(findIconOption(cat) || PREDEFINED_ICONS[1])
    setFormVisiblePublic(cat.active !== false)
    setFormError(null)
  }

  // Reset Form for "+ Add New Category" or dashed card
  const handleResetNewCategory = () => {
    setSelectedId(null)
    setFormName('')
    setFormSlug('')
    setFormSlugAuto(true)
    setFormNodeType('ROOT')
    setFormParentId('')
    setFormCommission('10.00')
    setSelectedIconObj(PREDEFINED_ICONS[1])
    setFormVisiblePublic(true)
    setFormError(null)
  }

  // Switch Form to "Add Subcategory Under <Root>"
  const handleStartAddSubcategory = (parentRoot) => {
    setSelectedId(null)
    setFormName('')
    setFormSlug('')
    setFormSlugAuto(true)
    setFormNodeType('SUBCATEGORY')
    setFormParentId(parentRoot.id)
    const rate =
      parentRoot.commissionRate != null
        ? (Number(parentRoot.commissionRate) * 100).toFixed(2)
        : '10.00'
    setFormCommission(rate)
    setSelectedIconObj(findIconOption(parentRoot) || PREDEFINED_ICONS[1])
    setFormVisiblePublic(true)
    setFormError(null)
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
    const allExpanded = categories.every((c) => expandedNodes[c.id])
    const next = {}
    categories.forEach((c) => {
      next[c.id] = !allExpanded
    })
    setExpandedNodes(next)
  }

  // Toggle Active / Draft Status directly from Tree
  const handleToggleCategoryActive = (id, e) => {
    if (e) e.stopPropagation()
    if (!canEdit) return
    setCategories((prev) =>
      prev.map((root) => {
        if (root.id === id) {
          const nextActive = !root.active
          showToast(`"${root.name}" status updated to ${nextActive ? 'Active' : 'Draft'}.`)
          if (selectedId === id) setFormVisiblePublic(nextActive)
          return { ...root, active: nextActive }
        }
        if (root.subcategories) {
          return {
            ...root,
            subcategories: root.subcategories.map((sub) => {
              if (sub.id === id) {
                const nextActive = !sub.active
                showToast(`"${sub.name}" status updated to ${nextActive ? 'Active' : 'Draft'}.`)
                if (selectedId === id) setFormVisiblePublic(nextActive)
                return { ...sub, active: nextActive }
              }
              return sub
            }),
          }
        }
        return root
      })
    )
  }

  // Form Submission (Create or Update)
  const handleSubmitForm = async (e) => {
    e.preventDefault()
    if (!canEdit) return
    if (!formName.trim()) {
      setFormError('Category name is required')
      return
    }
    if (!formSlug.trim()) {
      setFormError('URL slug is required')
      return
    }
    if (formNodeType === 'SUBCATEGORY' && !formParentId) {
      setFormError('Please select a parent category reference')
      return
    }

    setFormLoading(true)
    setFormError(null)

    const payload = {
      name: formName.trim(),
      slug: formSlug.trim(),
      icon: effectiveIconObj.id,
      parentId: formNodeType === 'SUBCATEGORY' ? formParentId : null,
      commissionRate: parseFloat(formCommission) / 100,
      active: formVisiblePublic,
    }

    try {
      if (selectedId) {
        await updateCategory(selectedId, payload).catch(() => null)
        setCategories((prev) =>
          prev.map((root) => {
            if (root.id === selectedId) {
              return { ...root, ...payload, iconImg: effectiveIconObj.img }
            }
            if (root.subcategories) {
              return {
                ...root,
                subcategories: root.subcategories.map((sub) =>
                  sub.id === selectedId ? { ...sub, ...payload, iconImg: effectiveIconObj.img } : sub
                ),
              }
            }
            return root
          })
        )
        showToast(`Category "${payload.name}" updated successfully.`)
      } else {
        const newId = `cat-${Date.now()}`
        await createCategory(payload).catch(() => null)
        if (payload.parentId) {
          setCategories((prev) =>
            prev.map((root) =>
              root.id === payload.parentId
                ? {
                  ...root,
                  subcategories: [
                    ...(root.subcategories || []),
                    { id: newId, ...payload, iconImg: effectiveIconObj.img, subcategories: [] },
                  ],
                }
                : root
            )
          )
          setExpandedNodes((prev) => ({ ...prev, [payload.parentId]: true }))
        } else {
          setCategories((prev) => [
            ...prev,
            { id: newId, ...payload, iconImg: effectiveIconObj.img, subcategories: [] },
          ])
        }
        showToast(`Category "${payload.name}" published successfully.`)
        handleResetNewCategory()
      }
    } catch (err) {
      setFormError(err.message || 'Operation failed')
    } finally {
      setFormLoading(false)
    }
  }

  // Filter categories by search term and tabs
  const filteredCategories = useMemo(() => {
    const term = searchTerm.toLowerCase().trim()
    return categories
      .map((root) => {
        const rootMatchesTab =
          activeTab === 'ALL' ||
          activeTab === 'ROOT' ||
          (activeTab === 'ACTIVE' && root.active) ||
          (activeTab === 'DRAFT' && !root.active)
        const rootMatchesSearch =
          !term || root.name.toLowerCase().includes(term) || root.slug.toLowerCase().includes(term)

        const subs = (root.subcategories || []).filter((sub) => {
          const subMatchesTab =
            activeTab === 'ALL' ||
            activeTab === 'SUB' ||
            (activeTab === 'ACTIVE' && sub.active) ||
            (activeTab === 'DRAFT' && !sub.active)
          const subMatchesSearch =
            !term || sub.name.toLowerCase().includes(term) || sub.slug.toLowerCase().includes(term)
          return subMatchesTab && subMatchesSearch
        })

        if (activeTab === 'SUB') {
          return subs.length > 0 ? { ...root, subcategories: subs, hideRoot: true } : null
        }

        if ((rootMatchesTab && rootMatchesSearch) || subs.length > 0) {
          return {
            ...root,
            subcategories: subs,
            hideRoot: activeTab === 'ROOT' ? false : activeTab === 'SUB',
          }
        }
        return null
      })
      .filter(Boolean)
  }, [categories, activeTab, searchTerm])

  const totalRoots = categories.length
  const totalSubs = categories.reduce((sum, c) => sum + (c.subcategories?.length || 0), 0)
  const breadcrumbs = entry
    ? [entry.section, entry.group?.label, entry.label].filter(Boolean)
    : ['Management', 'Categories']
  const addNewAction = canEdit ? (
    <button type="button" className={styles.btnAddNew} onClick={handleResetNewCategory}>
      <span className={styles.btnPlus}>+</span>
      <span>Add New Category</span>
    </button>
  ) : null

  return (
    <AdminPage
      title="Service Categories & Commission"
      subtitle="Manage platform category hierarchy, commission rates, subcategory mappings, and marketplace visibility."
      breadcrumbs={breadcrumbs}
      actions={addNewAction}
      plain
    >
      <div className={styles.dashboardBody}>
        {/* Toast Notification */}
        {toastMessage && (
          <div
            style={{
              position: 'fixed',
              top: '84px',
              right: '36px',
              zIndex: 9999,
              background: '#0f5132',
              color: '#ffffff',
              padding: '12px 20px',
              borderRadius: '8px',
              fontSize: '13px',
              fontWeight: 600,
              boxShadow: '0 8px 24px rgba(15, 81, 50, 0.2)',
            }}
          >
            ✅ {toastMessage}
          </div>
        )}

        {/* 3 Top Metric KPI Cards (100% Exact to Stitch Preview) */}
        <div className={styles.metricsGrid}>
          {/* Card 1 */}
          <div
            className={`${styles.metricCard} ${isInitialReveal ? styles.revealTopItem : ''}`}
            style={isInitialReveal ? { animationDelay: '0ms' } : undefined}
          >
            <div className={styles.metricTop}>
              <span className={styles.metricLabel}>AVG COMMISSION (OVERALL)</span>
              <div className={styles.metricIconBoxGreen}>
                <svg className={styles.trendArrow} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </div>
            </div>
            <div className={styles.metricMiddle}>
              <span className={styles.metricBig}>8.5%</span>
              <span className={styles.metricBadgeGreen}>+0.2% vs last month</span>
            </div>
            <div className={styles.metricBottom}>Across 24 Active Subcategories</div>
          </div>

          {/* Card 2 */}
          <div
            className={`${styles.metricCard} ${isInitialReveal ? styles.revealTopItem : ''}`}
            style={isInitialReveal ? { animationDelay: '130ms' } : undefined}
          >
            <div className={styles.metricTop}>
              <span className={styles.metricLabel}>AVG COMMISSION (HOME SERVICES)</span>
              <div className={styles.metricIconBoxBlue}>
                <svg className={styles.trendArrow} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </div>
            </div>
            <div className={styles.metricMiddle}>
              <span className={styles.metricBig}>10.0%</span>
              <span className={styles.metricSubtier}>Standard Tier</span>
            </div>
            <div className={styles.metricBottom}>Plumbing, Electrical, HVAC benchmark</div>
          </div>

          {/* Card 3 */}
          <div
            className={`${styles.metricCard} ${isInitialReveal ? styles.revealTopItem : ''}`}
            style={isInitialReveal ? { animationDelay: '260ms' } : undefined}
          >
            <div className={styles.metricTop}>
              <span className={styles.metricLabel}>AVG COMMISSION (OVERALL)</span>
              <div className={styles.metricIconBoxGreen}>
                <svg className={styles.trendArrow} fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24">
                  <path d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </div>
            </div>
            <div className={styles.metricMiddle}>
              <span className={styles.metricBig}>8.5%</span>
              <span className={styles.metricBadgeGreen}>+0.2% vs last month</span>
            </div>
            <div className={styles.metricBottom}>Across 24 Active Subcategories</div>
          </div>
        </div>

        {/* 2-Column Split Layout */}
        <div className={styles.splitLayout}>
          {/* LEFT COLUMN: Tree List & Filters */}
          <div className={styles.leftCol}>
            {/* Tabs */}
            <div className={styles.tabsRow}>
              {[
                { id: 'ALL', label: 'All' },
                { id: 'ROOT', label: 'Root Categories' },
                { id: 'SUB', label: 'Subcategories' },
                { id: 'ACTIVE', label: 'Active' },
                { id: 'DRAFT', label: 'Draft' },
              ].map((tab) => (
                <button
                  key={tab.id}
                  type="button"
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
                placeholder="Filter categories tree..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
              />
              {searchTerm && (
                <button type="button" className={styles.clearSearchBtn} onClick={() => setSearchTerm('')}>
                  ✕
                </button>
              )}
            </div>

            {/* Display Meta & Expand/Collapse Toggle */}
            <div className={styles.metaRow}>
              <span>
                Displaying {totalRoots} Root Nodes • {totalSubs} Subcategories
              </span>
              <button type="button" className={styles.btnToggleExpand} onClick={handleToggleExpandAll}>
                Expand All / Collapse All
              </button>
            </div>

            {/* Dotted "New Category" Quick Add Box */}
            {canEdit && (
              <div
                className={`${styles.newCategoryDotted} ${!selectedId && formNodeType === 'ROOT' ? styles.newCategoryDottedActive : ''} ${isInitialReveal ? styles.revealLeftItem : ''}`}
                style={isInitialReveal ? { animationDelay: '70ms' } : undefined}
                onClick={handleResetNewCategory}
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
              {filteredCategories.map((root, rootIndex) => {
                const isExpanded = expandedNodes[root.id]
                const isSelected = selectedId === root.id
                const subcategories = root.subcategories || []
                const rootRate =
                  root.commissionRate != null ? (Number(root.commissionRate) * 100).toFixed(1) : '10.0'
                const rootIconImg = findIconOption(root)?.img || root.iconImg

                return (
                  <div
                    key={root.id}
                    className={`${styles.rootCard} ${isSelected ? styles.rootCardSelected : ''} ${isInitialReveal ? styles.revealLeftItem : ''}`}
                    style={
                      isInitialReveal
                        ? { animationDelay: `${160 + Math.min(rootIndex, 8) * 115}ms` }
                        : undefined
                    }
                  >
                    {/* Root Node Header */}
                    {!root.hideRoot && (
                      <div className={styles.rootHeader} onClick={() => handleSelectCategory(root, false)}>
                        <div className={styles.rootLeft}>
                          {subcategories.length > 0 ? (
                            <button
                              type="button"
                              className={styles.chevronBtn}
                              onClick={(e) => {
                                e.stopPropagation()
                                toggleExpand(root.id)
                              }}
                              aria-label={isExpanded ? 'Collapse category' : 'Expand category'}
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
                              {subcategories.length} Subcategories
                            </span>
                          </div>
                        </div>

                        <div className={styles.rootRight}>
                          <span className={styles.ratePill}>{rootRate}%</span>
                          <button
                            type="button"
                            className={styles.statusDotBtn}
                            disabled={!canEdit}
                            onClick={(e) => handleToggleCategoryActive(root.id, e)}
                            title={
                              canEdit
                                ? root.active
                                  ? 'Status: Active (Click to toggle Draft)'
                                  : 'Status: Draft (Click to toggle Active)'
                                : root.active
                                  ? 'Status: Active'
                                  : 'Status: Draft'
                            }
                          >
                            <span className={root.active ? styles.activeDot : styles.statusDotGray} />
                            <span className={styles.statusLabelText}>{root.active ? 'Active' : 'Draft'}</span>
                          </button>
                        </div>
                      </div>
                    )}

                    {/* Subcategories under Root */}
                    {isExpanded && (subcategories.length > 0 || canEdit) && (
                      <div className={styles.subList}>
                        {subcategories.map((sub) => {
                          const isSubSelected = selectedId === sub.id
                          const subRate =
                            sub.commissionRate != null
                              ? (Number(sub.commissionRate) * 100).toFixed(1)
                              : rootRate
                          const subIconImg = findIconOption(sub)?.img || sub.iconImg || rootIconImg

                          return (
                            <div
                              key={sub.id}
                              className={`${styles.subCard} ${isSubSelected ? styles.subCardSelected : ''}`}
                              onClick={() => handleSelectCategory(sub, true)}
                            >
                              <div className={styles.subLeft}>
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
                                    <span className={styles.badgeInherited}>Inherited Icon</span>
                                  </div>
                                  <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                                    <span className={styles.subDetails}>Subcategory</span>
                                    <span className={styles.subDetails} aria-hidden="true">•</span>
                                    <span className={styles.subDetails}>Standard fee</span>
                                  </div>
                                </div>
                              </div>

                              <div className={styles.rootRight}>
                                <span className={styles.ratePill}>{subRate}%</span>
                                <button
                                  type="button"
                                  className={styles.statusDotBtn}
                                  disabled={!canEdit}
                                  onClick={(e) => handleToggleCategoryActive(sub.id, e)}
                                  title={
                                    canEdit
                                      ? sub.active
                                        ? 'Status: Active (Click to toggle Draft)'
                                        : 'Status: Draft (Click to toggle Active)'
                                      : sub.active
                                        ? 'Status: Active'
                                        : 'Status: Draft'
                                  }
                                >
                                  <span className={sub.active ? styles.activeDot : styles.statusDotGray} />
                                  <span className={styles.statusLabelText}>{sub.active ? 'Active' : 'Draft'}</span>
                                </button>
                              </div>
                            </div>
                          )
                        })}
                        {canEdit && (
                          <button
                            type="button"
                            className={styles.btnAddSubInline}
                            onClick={(e) => {
                              e.stopPropagation()
                              handleStartAddSubcategory(root)
                            }}
                          >
                            + Add Subcategory Under {root.name}
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          </div>

          {/* RIGHT COLUMN: Create / Edit Category Form */}
          <div className={`${styles.rightCol} ${isInitialReveal ? styles.revealRightCol : ''}`}>
            <div className={styles.formHeader}>
              <div>
                <h2 className={styles.formTitle}>
                  {selectedId
                    ? 'Category Details & Commission Rules'
                    : formNodeType === 'SUBCATEGORY'
                      ? 'Create New Subcategory'
                      : 'Create New Service Category'}
                </h2>
                <p className={styles.formSubtitle}>
                  Configure hierarchy attributes, SEO canonical slug, and fee deductions.
                </p>
              </div>
              <span className={styles.formHeaderBadge}>
                {selectedId
                  ? formNodeType === 'SUBCATEGORY'
                    ? 'Editing Subcategory'
                    : 'Editing Root Category'
                  : formNodeType === 'SUBCATEGORY'
                    ? 'Creating Subcategory'
                    : 'Creating Root Category'}
              </span>
            </div>

            {formError && (
              <div
                style={{
                  background: '#fdecea',
                  border: '1px solid #f7c3be',
                  color: '#b42318',
                  padding: '10px 14px',
                  borderRadius: '8px',
                  fontSize: '13px',
                }}
              >
                ⚠️ {formError}
              </div>
            )}

            <form onSubmit={handleSubmitForm} className={styles.formSection}>
              <fieldset disabled={!canEdit} className={styles.formFieldset}>
              {/* Category Name */}
              <div className={styles.inputGroup}>
                <label className={styles.inputLabel}>
                  Category Name <span className={styles.redStar}>*</span>
                </label>
                <input
                  type="text"
                  className={styles.textInput}
                  placeholder="e.g. Solar & Renewable Energy, Pest Control, Painting..."
                  value={formName}
                  onChange={handleNameChange}
                  required
                />
                <span className={styles.inputHelper}>Public display name</span>
              </div>

              {/* Url Slug Canonical Path */}
              <div className={styles.inputGroup}>
                <label className={styles.inputLabel}>
                  Url Slug Canonical Path <span className={styles.redStar}>*</span>
                </label>
                <div className={styles.slugContainer}>
                  <span className={styles.slugPrefix}>localserve.lk/categories/</span>
                  <input
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

              {/* Hierarchy Node Type & Parent Category Reference */}
              <div className={styles.twoColRow}>
                {/* Node Type */}
                <div className={styles.inputGroup}>
                  <label className={styles.inputLabel}>
                    Hierarchy Node Type <span className={styles.redStar}>*</span>
                  </label>
                  <div className={styles.radioRow}>
                    <label className={styles.radioLabel}>
                      <input
                        type="radio"
                        name="nodeType"
                        value="ROOT"
                        checked={formNodeType === 'ROOT'}
                        onChange={() => {
                          setFormNodeType('ROOT')
                          setFormParentId('')
                        }}
                      />
                      <span>Root Category</span>
                    </label>

                    <label className={styles.radioLabel}>
                      <input
                        type="radio"
                        name="nodeType"
                        value="SUBCATEGORY"
                        checked={formNodeType === 'SUBCATEGORY'}
                        onChange={() => {
                          setFormNodeType('SUBCATEGORY')
                          if (!formParentId && categories.length > 0) {
                            const fallbackRoot = categories.find((c) => c.id !== selectedId) || categories[0]
                            if (fallbackRoot) {
                              setFormParentId(fallbackRoot.id)
                              if (!selectedId) {
                                setSelectedIconObj(findIconOption(fallbackRoot) || PREDEFINED_ICONS[1])
                                const rate =
                                  fallbackRoot.commissionRate != null
                                    ? (Number(fallbackRoot.commissionRate) * 100).toFixed(2)
                                    : '10.00'
                                setFormCommission(rate)
                              }
                            }
                          }
                        }}
                      />
                      <span>Subcategory</span>
                      <span className={styles.badgeInheritsTiny}>Inherits Icon</span>
                    </label>
                  </div>
                </div>

                {/* Parent Category Reference */}
                <div className={styles.inputGroup}>
                  <label className={styles.inputLabel}>
                    Parent Category Reference <span className={styles.redStar}>*</span>
                  </label>
                  <select
                    className={styles.selectBox}
                    value={formParentId}
                    onChange={(e) => {
                      const nextParentId = e.target.value
                      setFormParentId(nextParentId)
                      const nextParent = categories.find((c) => c.id === nextParentId)
                      if (nextParent) {
                        setSelectedIconObj(findIconOption(nextParent) || PREDEFINED_ICONS[1])
                        if (!selectedId && nextParent.commissionRate != null) {
                          setFormCommission((Number(nextParent.commissionRate) * 100).toFixed(2))
                        }
                      }
                    }}
                    disabled={formNodeType === 'ROOT'}
                  >
                    <option value="">None (Top-Level Root Node)</option>
                    {categories
                      .filter((c) => c.id !== selectedId)
                      .map((root) => (
                        <option key={root.id} value={root.id}>
                          {root.name}
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
                <label className={styles.inputLabel}>
                  Platform Commission Rate (%) <span className={styles.redStar}>*</span>
                </label>
                <div className={styles.commissionWrapper}>
                  <input
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
                <span className={styles.inputHelper}>Platform fee rate</span>
              </div>

              {/* Category Vector Icon (Svg) Section */}
              {formNodeType === 'SUBCATEGORY' ? (
                <div className={styles.inputGroup}>
                  <div className={styles.iconSectionHeaderRow}>
                    <div>
                      <label className={styles.inputLabel}>
                        Category Vector Icon (Svg) <span className={styles.redStar}>*</span>
                      </label>
                      <p className={styles.iconCardDesc}>
                        Root icon selected • Subcategories inherit this icon automatically (can be changed in parent)
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
                    <label className={styles.inputLabel}>
                      Category Vector Icon (Svg) <span className={styles.redStar}>*</span>
                    </label>
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
                          Select from system-curated 3D pastel vector service icons for your category.
                        </p>
                        <p className={styles.iconCardNote}>
                          Subcategories automatically inherit and share their parent category's vector icon,
                          maintaining consistent visual identity across discovery.
                        </p>
                      </div>
                    </div>
                  </div>

                  {/* Popular Predefined Icons Grid with 12 Pure-Vector SVGs */}
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
                        >
                          <img src={item.img} alt={item.name} width="46" height="46" />
                        </button>
                      ))}
                    </div>
                  </div>
                </>
              )}

              {/* Category Status & Marketplace Visibility Checkbox */}
              <div className={styles.statusToggleContainer}>
                <label className={styles.visibleCheckboxLabel}>
                  <div
                    className={`${styles.customCheckbox} ${formVisiblePublic ? styles.customCheckboxChecked : ''}`}
                    onClick={() => {
                      if (canEdit) setFormVisiblePublic(!formVisiblePublic)
                    }}
                    role="checkbox"
                    aria-checked={formVisiblePublic}
                    aria-disabled={!canEdit}
                    tabIndex={canEdit ? 0 : -1}
                    onKeyDown={(e) => {
                      if (canEdit && (e.key === ' ' || e.key === 'Enter')) {
                        e.preventDefault()
                        setFormVisiblePublic(!formVisiblePublic)
                      }
                    }}
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
                  </div>

                  <div
                    className={styles.checkboxTextGroup}
                    onClick={() => {
                      if (canEdit) setFormVisiblePublic(!formVisiblePublic)
                    }}
                  >
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
                        {formVisiblePublic ? 'Active' : 'Draft / Inactive'}
                      </span>
                    </div>
                    <div className={styles.checkboxSub}>
                      {formVisiblePublic
                        ? 'Published live — Customers can browse and book services in this category'
                        : 'Hidden from public browse — Preserved as an internal admin draft'}
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
                    <span className={styles.btnPlus}>+</span>
                    <span>
                      {formLoading
                        ? 'Publishing...'
                        : selectedId
                          ? 'Save & Publish Category'
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
    </AdminPage>
  )
}
