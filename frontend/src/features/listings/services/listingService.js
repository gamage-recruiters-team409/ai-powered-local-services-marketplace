import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
const LOCAL_STORAGE_KEY = 'wp03.providerListings.v1'

export const listingHttp = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
})

listingHttp.interceptors.request.use((config) => {
  try {
    const token =
      window.sessionStorage?.getItem('dev.accessToken') ||
      window.localStorage?.getItem('accessToken') ||
      window.localStorage?.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
  } catch {
    // Storage access unavailable
  }
  return config
})

export const DURATION_OPTIONS = Object.freeze([
  { label: '30 Mins', value: 30 },
  { label: '1 Hour', value: 60 },
  { label: '1 - 2 Hours', value: 90 },
  { label: '2 - 3 Hours', value: 150 },
  { label: 'Half Day (4 Hours)', value: 240 },
  { label: 'Full Day (8 Hours)', value: 480 },
])

export const AI_SUGGESTION_CHIPS = Object.freeze([
  '30-Day Workmanship Warranty',
  'NVQ-4 Certified Technician',
  'Thermal & Pressure Diagnostic',
  'Upfront Transparent Quotation',
  'Post-Job Clean-up Included',
])

export const FALLBACK_CATEGORIES = Object.freeze([
  {
    id: 'cat-plumbing',
    name: 'Plumbing & Water Systems',
    slug: 'plumbing-water-systems',
    active: true,
    children: [
      { id: 'cat-leak-repair', name: 'Emergency Leak Repair', slug: 'emergency-leak-repair', parentId: 'cat-plumbing', active: true },
      { id: 'cat-pipe-drain', name: 'Drainage & Sewer Cleaning', slug: 'drainage-sewer-cleaning', parentId: 'cat-plumbing', active: false },
      { id: 'cat-tank-motor', name: 'Water Tank & Motor Service', slug: 'water-tank-motor-service', parentId: 'cat-plumbing', active: true },
    ],
  },
  {
    id: 'cat-electrical',
    name: 'Electrical & Wiring',
    slug: 'electrical-wiring',
    active: true,
    children: [
      { id: 'cat-breaker', name: 'Circuit Breaker & Wiring', slug: 'circuit-breaker-wiring', parentId: 'cat-electrical', active: true },
      { id: 'cat-solar', name: 'Solar & Inverter Maintenance', slug: 'solar-inverter-maintenance', parentId: 'cat-electrical', active: true },
    ],
  },
  {
    id: 'cat-hvac',
    name: 'Air Conditioning & HVAC',
    slug: 'air-conditioning-hvac',
    active: true,
    children: [
      { id: 'cat-ac-wash', name: 'Split AC Chemical Wash', slug: 'split-ac-chemical-wash', parentId: 'cat-hvac', active: true },
      { id: 'cat-ac-gas', name: 'Inverter Gas Refill & Tune-up', slug: 'inverter-gas-refill', parentId: 'cat-hvac', active: true },
    ],
  },
  {
    id: 'cat-cleaning',
    name: 'Cleaning & Janitorial',
    slug: 'cleaning-janitorial',
    active: true,
    children: [
      { id: 'cat-deep-clean', name: 'Deep Home Sanitation', slug: 'deep-home-sanitation', parentId: 'cat-cleaning', active: true },
    ],
  },
  {
    id: 'cat-carpentry',
    name: 'Carpentry & Woodwork',
    slug: 'carpentry-woodwork',
    active: true,
    children: [
      { id: 'cat-cabinet', name: 'Door, Lock & Cabinet Repair', slug: 'door-lock-cabinet-repair', parentId: 'cat-carpentry', active: true },
    ],
  },
  {
    id: 'cat-painting',
    name: 'Painting & Finishing',
    slug: 'painting-finishing',
    active: true,
    children: [
      { id: 'cat-interior-paint', name: 'Interior & Waterproof Painting', slug: 'interior-waterproof-painting', parentId: 'cat-painting', active: true },
    ],
  },
])

export const SRI_LANKA_PROVINCES_FALLBACK = Object.freeze([
  {
    name: 'Western Province',
    districts: [
      { name: 'Colombo', province: 'Western Province', latitude: 6.9271, longitude: 79.8612, defaultZoom: 12 },
      { name: 'Gampaha', province: 'Western Province', latitude: 7.0873, longitude: 80.0144, defaultZoom: 12 },
      { name: 'Kalutara', province: 'Western Province', latitude: 6.5854, longitude: 79.9607, defaultZoom: 12 },
    ],
  },
  {
    name: 'Central Province',
    districts: [
      { name: 'Kandy', province: 'Central Province', latitude: 7.2906, longitude: 80.6337, defaultZoom: 12 },
      { name: 'Matale', province: 'Central Province', latitude: 7.4675, longitude: 80.6234, defaultZoom: 12 },
      { name: 'Nuwara Eliya', province: 'Central Province', latitude: 6.9497, longitude: 80.7891, defaultZoom: 12 },
    ],
  },
  {
    name: 'Southern Province',
    districts: [
      { name: 'Galle', province: 'Southern Province', latitude: 6.0535, longitude: 80.221, defaultZoom: 12 },
      { name: 'Matara', province: 'Southern Province', latitude: 5.9549, longitude: 80.555, defaultZoom: 12 },
      { name: 'Hambantota', province: 'Southern Province', latitude: 6.1241, longitude: 81.1185, defaultZoom: 12 },
    ],
  },
  {
    name: 'Northern Province',
    districts: [
      { name: 'Jaffna', province: 'Northern Province', latitude: 9.6615, longitude: 80.0255, defaultZoom: 12 },
      { name: 'Kilinochchi', province: 'Northern Province', latitude: 9.3803, longitude: 80.377, defaultZoom: 12 },
      { name: 'Mannar', province: 'Northern Province', latitude: 8.981, longitude: 79.9044, defaultZoom: 12 },
      { name: 'Vavuniya', province: 'Northern Province', latitude: 8.7514, longitude: 80.4971, defaultZoom: 12 },
      { name: 'Mullaitivu', province: 'Northern Province', latitude: 9.2671, longitude: 80.8142, defaultZoom: 12 },
    ],
  },
  {
    name: 'Eastern Province',
    districts: [
      { name: 'Batticaloa', province: 'Eastern Province', latitude: 7.731, longitude: 81.6747, defaultZoom: 12 },
      { name: 'Ampara', province: 'Eastern Province', latitude: 7.2975, longitude: 81.682, defaultZoom: 12 },
      { name: 'Trincomalee', province: 'Eastern Province', latitude: 8.5874, longitude: 81.2152, defaultZoom: 12 },
    ],
  },
  {
    name: 'North Western Province',
    districts: [
      { name: 'Kurunegala', province: 'North Western Province', latitude: 7.4863, longitude: 80.3623, defaultZoom: 12 },
      { name: 'Puttalam', province: 'North Western Province', latitude: 8.0362, longitude: 79.8283, defaultZoom: 12 },
    ],
  },
  {
    name: 'North Central Province',
    districts: [
      { name: 'Anuradhapura', province: 'North Central Province', latitude: 8.3114, longitude: 80.4037, defaultZoom: 12 },
      { name: 'Polonnaruwa', province: 'North Central Province', latitude: 7.9403, longitude: 81.0188, defaultZoom: 12 },
    ],
  },
  {
    name: 'Uva Province',
    districts: [
      { name: 'Badulla', province: 'Uva Province', latitude: 6.9934, longitude: 81.055, defaultZoom: 12 },
      { name: 'Monaragala', province: 'Uva Province', latitude: 6.8728, longitude: 81.3507, defaultZoom: 12 },
    ],
  },
  {
    name: 'Sabaragamuwa Province',
    districts: [
      { name: 'Ratnapura', province: 'Sabaragamuwa Province', latitude: 6.6828, longitude: 80.3992, defaultZoom: 12 },
      { name: 'Kegalle', province: 'Sabaragamuwa Province', latitude: 7.2513, longitude: 80.3464, defaultZoom: 12 },
    ],
  },
])

function createServicePreviewArt(label, accentHex, bgHex) {
  const safeLabel = String(label || 'Service')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 380"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="${bgHex}"/><stop offset="100%" stop-color="#0f172a"/></linearGradient></defs><rect width="640" height="380" fill="url(#g)"/><circle cx="540" cy="70" r="95" fill="${accentHex}" opacity="0.22"/><circle cx="110" cy="310" r="115" fill="${accentHex}" opacity="0.16"/><rect x="44" y="248" width="128" height="34" rx="17" fill="${accentHex}" opacity="0.92"/><text x="108" y="270" text-anchor="middle" fill="#ffffff" font-family="system-ui,sans-serif" font-size="14" font-weight="700">LOCALSERVE PRO</text><text x="44" y="180" fill="#ffffff" font-family="system-ui,sans-serif" font-size="30" font-weight="700">${safeLabel}</text><text x="44" y="216" fill="#cbd5e1" font-family="system-ui,sans-serif" font-size="16">Verified Local Trade Portfolio</text></svg>`
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

export const DEFAULT_PORTFOLIO_THUMBNAILS = Object.freeze({
  plumbing: createServicePreviewArt('Plumbing & Pipe Leak Repair', '#0f766e', '#134e4a'),
  electrical: createServicePreviewArt('Electrical & Breaker Wiring', '#0284c7', '#1e293b'),
  hvac: createServicePreviewArt('Split AC Chemical Wash', '#0d9488', '#0f2744'),
  cleaning: createServicePreviewArt('Water Tank & Home Cleaning', '#16a34a', '#14532d'),
})

function createSeedListings() {
  return [
    {
      id: 'lst-1001',
      providerId: 'prov-nimal',
      categoryId: 'cat-leak-repair',
      categoryName: 'Plumbing & Water Systems',
      subcategoryName: 'Emergency Leak Repair',
      parentCategoryId: 'cat-plumbing',
      title: 'Emergency Pipe Leak Repair & Waterline Diagnostic',
      description:
        'Comprehensive on-site emergency water pipe leakage diagnostic and immediate temporary sealing. Includes pressure isolation checks, acoustic listening detection for concealed masonry pipes, and full transparent quotation before permanent replacement.',
      pricingModel: 'FIXED',
      basePrice: 2800,
      durationMin: 90,
      executionModel: 'ON_SITE',
      district: 'Colombo',
      baseCity: 'Colombo 05 (Havelock Town)',
      location: { longitude: 79.8612, latitude: 6.9271 },
      coverageRadiusKm: 15,
      serviceAreas: ['Colombo 01-15', 'Dehiwala', 'Mount Lavinia', 'Battaramulla'],
      images: [DEFAULT_PORTFOLIO_THUMBNAILS.plumbing],
      coverImageUrl: DEFAULT_PORTFOLIO_THUMBNAILS.plumbing,
      addOns: [
        {
          addOnId: 'ao-1',
          title: 'Emergency Night Callout',
          description: 'Expedited response outside typical daylight business hours (8 PM - 6 AM).',
          extraPrice: 1500,
          extraDurationMin: 0,
          active: true,
        },
        {
          addOnId: 'ao-2',
          title: 'Heavy Duty CPVC Pipe Supply',
          description: 'Includes high-pressure CPVC fittings and solvent weld compound.',
          extraPrice: 1200,
          extraDurationMin: 30,
          active: true,
        },
      ],
      status: 'ACTIVE',
      availableForBooking: true,
      categoryActive: true,
      activeAddOnCount: 2,
      moderationFlags: [],
      ratingAvg: 4.9,
      ratingCount: 34,
      inquiryCount: 14,
      viewCount: 248,
      createdAt: '2026-11-16T08:15:00Z',
      updatedAt: '2026-11-18T09:10:00Z',
    },
    {
      id: 'lst-1002',
      providerId: 'prov-nimal',
      categoryId: 'cat-breaker',
      categoryName: 'Electrical & Wiring',
      subcategoryName: 'Circuit Breaker & Wiring',
      parentCategoryId: 'cat-electrical',
      title: 'Home Circuit Breaker & Distribution Board Wiring',
      description:
        'Safe diagnosis and replacement of tripping MCBs, RCCBs, and overloaded domestic sub-circuits. Tested for earth leakage compliance with full load balancing report.',
      pricingModel: 'FIXED',
      basePrice: 3500,
      durationMin: 90,
      executionModel: 'ON_SITE',
      district: 'Colombo',
      baseCity: 'Nugegoda',
      location: { longitude: 79.8891, latitude: 6.8649 },
      coverageRadiusKm: 20,
      serviceAreas: ['Nugegoda', 'Maharagama', 'Kotte'],
      images: [DEFAULT_PORTFOLIO_THUMBNAILS.electrical],
      coverImageUrl: DEFAULT_PORTFOLIO_THUMBNAILS.electrical,
      addOns: [
        {
          addOnId: 'ao-3',
          title: 'Surge Protector Installation',
          description: 'Supply & fit Type-2 single-phase surge arrestor.',
          extraPrice: 2200,
          extraDurationMin: 45,
          active: true,
        },
        {
          addOnId: 'ao-4',
          title: 'Full Earthing Resistance Test',
          description: 'Digital clamp meter verification of ground rod impedance.',
          extraPrice: 950,
          extraDurationMin: 20,
          active: true,
        },
      ],
      status: 'ACTIVE',
      availableForBooking: true,
      categoryActive: true,
      activeAddOnCount: 2,
      moderationFlags: [],
      ratingAvg: 4.9,
      ratingCount: 29,
      inquiryCount: 9,
      viewCount: 194,
      createdAt: '2026-11-15T11:20:00Z',
      updatedAt: '2026-11-18T08:30:00Z',
    },
    {
      id: 'lst-1003',
      providerId: 'prov-nimal',
      categoryId: 'cat-ac-wash',
      categoryName: 'Air Conditioning & HVAC',
      subcategoryName: 'Split AC Chemical Wash',
      parentCategoryId: 'cat-hvac',
      title: 'Inverter Split AC Deep Chemical Wash & Coil Flush',
      description:
        'Complete indoor evaporator and outdoor condenser high-pressure chemical wash using waterproof jacket containment, drain line vacuum flush, and airflow temperature check.',
      pricingModel: 'HOURLY',
      basePrice: 2400,
      durationMin: 60,
      executionModel: 'ON_SITE',
      district: 'Gampaha',
      baseCity: 'Wattala',
      location: { longitude: 79.8919, latitude: 6.9892 },
      coverageRadiusKm: 18,
      serviceAreas: ['Wattala', 'Kelaniya', 'Negombo Road'],
      images: [DEFAULT_PORTFOLIO_THUMBNAILS.hvac],
      coverImageUrl: DEFAULT_PORTFOLIO_THUMBNAILS.hvac,
      addOns: [
        {
          addOnId: 'ao-5',
          title: 'Antibacterial Coil Coating',
          description: 'Anti-fungal protective mist for evaporator fins.',
          extraPrice: 850,
          extraDurationMin: 15,
          active: true,
        },
      ],
      status: 'ACTIVE',
      availableForBooking: true,
      categoryActive: true,
      activeAddOnCount: 1,
      moderationFlags: [],
      ratingAvg: 4.8,
      ratingCount: 18,
      inquiryCount: 5,
      viewCount: 162,
      createdAt: '2026-11-14T14:00:00Z',
      updatedAt: '2026-11-17T16:40:00Z',
    },
    {
      id: 'lst-1004',
      providerId: 'prov-nimal',
      categoryId: 'cat-tank-motor',
      categoryName: 'Plumbing & Water Systems',
      subcategoryName: 'Water Tank & Motor Service',
      parentCategoryId: 'cat-plumbing',
      title: 'Overhead Water Tank Sterilization & Pressure Pump Tune-up',
      description:
        'High-pressure scrubbing, sludge drainage, food-grade chlorination flush, float valve calibration, and domestic booster pump air-lock bleeding.',
      pricingModel: 'FIXED',
      basePrice: 4200,
      durationMin: 150,
      executionModel: 'ON_SITE',
      district: 'Colombo',
      baseCity: 'Dehiwala',
      location: { longitude: 79.8654, latitude: 6.8511 },
      coverageRadiusKm: 12,
      serviceAreas: ['Dehiwala', 'Wellawatte', 'Ratmalana'],
      images: [DEFAULT_PORTFOLIO_THUMBNAILS.cleaning],
      coverImageUrl: DEFAULT_PORTFOLIO_THUMBNAILS.cleaning,
      addOns: [],
      status: 'PAUSED',
      availableForBooking: false,
      categoryActive: true,
      activeAddOnCount: 0,
      moderationFlags: [],
      ratingAvg: 4.7,
      ratingCount: 11,
      inquiryCount: 0,
      viewCount: 86,
      createdAt: '2026-11-12T09:10:00Z',
      updatedAt: '2026-11-17T12:15:00Z',
    },
    {
      id: 'lst-1005',
      providerId: 'prov-nimal',
      categoryId: 'cat-pipe-drain',
      categoryName: 'Plumbing & Water Systems',
      subcategoryName: 'Drainage & Sewer Cleaning',
      parentCategoryId: 'cat-plumbing',
      title: 'Main Sewer Line Hydro-Jetting & Drain Cleaning',
      description:
        'Motorized auger and hydro-jet clearance for kitchen grease traps, bathroom floor gullies, and exterior inspection manholes.',
      pricingModel: 'FIXED',
      basePrice: 6500,
      durationMin: 120,
      executionModel: 'ON_SITE',
      district: 'Colombo',
      baseCity: 'Colombo 07',
      location: { longitude: 79.8653, latitude: 6.9125 },
      coverageRadiusKm: 15,
      serviceAreas: ['Colombo 03', 'Colombo 07', 'Borella'],
      images: [DEFAULT_PORTFOLIO_THUMBNAILS.plumbing],
      coverImageUrl: DEFAULT_PORTFOLIO_THUMBNAILS.plumbing,
      addOns: [
        {
          addOnId: 'ao-6',
          title: 'Grease Trap Enzyme Treatment',
          description: 'Bio-safe enzyme dosing to prevent recurring grease buildup.',
          extraPrice: 1100,
          extraDurationMin: 15,
          active: true,
        },
      ],
      status: 'ACTIVE',
      availableForBooking: false,
      categoryActive: false,
      activeAddOnCount: 1,
      moderationFlags: [],
      ratingAvg: 4.7,
      ratingCount: 15,
      inquiryCount: 0,
      viewCount: 42,
      createdAt: '2026-11-11T15:00:00Z',
      updatedAt: '2026-11-16T10:05:00Z',
    },
  ]
}

function readLocalListings() {
  try {
    const raw = window.sessionStorage?.getItem(LOCAL_STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed)) {
        return parsed
      }
    }
  } catch {
    // Fallback to seed data
  }
  const seeded = createSeedListings()
  writeLocalListings(seeded)
  return seeded
}

function writeLocalListings(listings) {
  try {
    window.sessionStorage?.setItem(LOCAL_STORAGE_KEY, JSON.stringify(listings))
  } catch {
    // Storage write unavailable
  }
}

export function resetLocalListingsDemoStore() {
  const seeded = createSeedListings()
  writeLocalListings(seeded)
  return seeded
}

function shouldFallbackToLocal(error) {
  if (!error) return false
  if (!error.response) return true
  const status = error.response.status
  return status === 404 || status === 401 || status === 502 || status === 503
}

/**
 * Mirrors backend ListingContentScreeningService rules (FR-LIST-006 / BR-011)
 * to warn providers before submission if prohibited phone numbers, emails, or external links appear.
 */
export function screenListingContent({ title = '', description = '', addOns = [] } = {}) {
  const parts = [title, description]
  if (Array.isArray(addOns)) {
    addOns.forEach((item) => {
      if (item?.title) parts.push(item.title)
      if (item?.description) parts.push(item.description)
    })
  }
  const combined = parts.join(' ').trim()
  if (!combined) {
    return { flagged: false, reasons: [] }
  }

  const normalized = combined
    .normalize('NFKC')
    .replace(/[\u200B-\u200D\uFEFF\u00AD]/g, '')

  const reasons = []
  const phoneCandidate = normalized
    .replace(/(?<=\d)[\s.\-_()/]+(?=\d)/g, '')
    .replace(/(?<=\+94)[\s.\-_()/]+(?=\d)/g, '')

  if (
    /(?<!\d)(?:(?:\+|00)?94|0)(?:7[01245678]|11|2[134567]|3[1-8]|4[157]|5[12457]|6[3567]|81|91)\d{7}(?!\d)/.test(
      phoneCandidate,
    )
  ) {
    reasons.push('Direct phone numbers are prohibited in listing descriptions.')
  }

  const emailCandidate = normalized
    .replace(/(?:\s*\[\s*at\s*\]\s*|\s*\(\s*at\s*\)\s*|\s*\{\s*at\s*\}\s*|\s+at\s+)/gi, '@')
    .replace(/(?:\s*\[\s*dot\s*\]\s*|\s*\(\s*dot\s*\)\s*|\s*\{\s*dot\s*\}\s*|\s+dot\s+)/gi, '.')

  if (/[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}/i.test(emailCandidate)) {
    reasons.push('Direct email addresses are prohibited in listing descriptions.')
  }

  if (
    /(?:https?:\/\/|www\.|wa\.me\/|chat\.whatsapp\.com|whatsapp\.com\/|t\.me\/|telegram\.me\/|facebook\.com\/|fb\.com\/|instagram\.com\/|linkedin\.com\/|whatsapp\s*(?:me|us|at|on|:|-)\s*[+\d])/i.test(
      normalized,
    )
  ) {
    reasons.push('External URLs or WhatsApp links are prohibited in listing descriptions.')
  }

  return {
    flagged: reasons.length > 0,
    reasons,
  }
}

export function findNearestSriLankaDistrict(latitude, longitude, provinces = SRI_LANKA_PROVINCES_FALLBACK) {
  let best = provinces[0].districts[0]
  let bestDistSq = Number.POSITIVE_INFINITY
  provinces.forEach((prov) => {
    prov.districts.forEach((dist) => {
      const dLat = dist.latitude - latitude
      const dLng = dist.longitude - longitude
      const distSq = dLat * dLat + dLng * dLng
      if (distSq < bestDistSq) {
        bestDistSq = distSq
        best = dist
      }
    })
  })
  return best
}

/**
 * Advisory AI-003 Listing Description Assistant (BR-016: never persisted without explicit provider review).
 */
export function generateAiDescriptionSuggestion({
  title = '',
  categoryName = '',
  subcategoryName = '',
  executionModel = 'ON_SITE',
  district = 'Colombo',
  coverageRadiusKm = 15,
} = {}) {
  const serviceLabel = title.trim() || subcategoryName || categoryName || 'Professional Local Service'
  const deliveryClause =
    executionModel === 'WORKSHOP'
      ? `Service completed at our equipped ${district} workshop bay with bench-tested diagnostics and careful handling.`
      : `Full on-site inspection and execution across ${district} and surrounding zones within a ${coverageRadiusKm} km radius.`

  return (
    `${serviceLabel} delivered with certified diagnostics, clean tool setup, and itemized scope verification before work begins. ` +
    `${deliveryClause} ` +
    `Includes safety seal checks, tidy post-job cleanup, and an upfront LKR quotation aligned with platform escrow standards.`
  )
}

function buildLocalSummary(listings) {
  return listings.reduce(
    (acc, item) => {
      acc.totalListings += 1
      if (item.status === 'ACTIVE') acc.activeCount += 1
      if (item.status === 'PAUSED') acc.pausedCount += 1
      if (item.status === 'DRAFT') acc.draftCount += 1
      if (item.status === 'ARCHIVED') acc.archivedCount += 1
      if (item.status === 'FLAGGED') acc.flaggedCount += 1
      if (item.status === 'REJECTED') acc.rejectedCount += 1
      if (item.categoryActive === false) acc.categoryInactiveCount += 1
      return acc
    },
    {
      totalListings: 0,
      activeCount: 0,
      pausedCount: 0,
      draftCount: 0,
      archivedCount: 0,
      flaggedCount: 0,
      rejectedCount: 0,
      categoryInactiveCount: 0,
    },
  )
}

function resolveCategoryMetadata(categoryId, categories = FALLBACK_CATEGORIES) {
  for (const root of categories) {
    if (root.id === categoryId) {
      return {
        categoryName: root.name,
        parentCategoryId: null,
        categoryActive: Boolean(root.active),
      }
    }
    const child = (root.children || []).find((sub) => sub.id === categoryId)
    if (child) {
      return {
        categoryName: root.name,
        subcategoryName: child.name,
        parentCategoryId: root.id,
        categoryActive: Boolean(root.active) && Boolean(child.active),
      }
    }
  }
  return {
    categoryName: 'Service Category',
    parentCategoryId: null,
    categoryActive: true,
  }
}

export async function getPublicCategories(signal) {
  try {
    const response = await listingHttp.get('/categories', { signal })
    if (Array.isArray(response.data) && response.data.length > 0) {
      return { categories: response.data, isFallback: false }
    }
  } catch (error) {
    if (error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED') {
      throw error
    }
    if (!shouldFallbackToLocal(error)) {
      throw error
    }
  }
  return { categories: FALLBACK_CATEGORIES, isFallback: true }
}

export async function getDistrictLocations(signal) {
  try {
    const response = await listingHttp.get('/listings/locations/districts', { signal })
    if (Array.isArray(response.data) && response.data.length > 0) {
      return { provinces: response.data, isFallback: false }
    }
  } catch (error) {
    if (error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED') {
      throw error
    }
    if (!shouldFallbackToLocal(error)) {
      throw error
    }
  }
  return { provinces: SRI_LANKA_PROVINCES_FALLBACK, isFallback: true }
}

export async function getMyListingsSummary(signal) {
  try {
    const response = await listingHttp.get('/listings/me/summary', { signal })
    return { summary: response.data, isFallback: false }
  } catch (error) {
    if (error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED') {
      throw error
    }
    if (!shouldFallbackToLocal(error)) {
      throw error
    }
    const items = readLocalListings()
    return { summary: buildLocalSummary(items), isFallback: true }
  }
}

export async function getMyListings(
  { status = 'ALL', search = '', page = 0, size = 8, sortBy = 'newest', categoryInactiveOnly = false } = {},
  signal,
) {
  try {
    const params = { page, size }
    if (status && status !== 'ALL' && !categoryInactiveOnly) {
      params.status = status
    }
    if (search.trim()) {
      params.search = search.trim()
    }
    if (sortBy === 'price_asc') params.sort = 'basePrice,asc'
    else if (sortBy === 'price_desc') params.sort = 'basePrice,desc'
    else if (sortBy === 'rating_desc') params.sort = 'ratingAvg,desc'
    else params.sort = 'createdAt,desc'

    const response = await listingHttp.get('/listings/me', { params, signal })
    const rawContent = Array.isArray(response.data?.content) ? response.data.content : []
    const content = categoryInactiveOnly
      ? rawContent.filter((item) => item.categoryActive === false)
      : rawContent

    return {
      pageData: {
        content,
        totalElements: categoryInactiveOnly ? content.length : (response.data?.totalElements ?? content.length),
        totalPages: Math.max(1, response.data?.totalPages ?? 1),
        number: response.data?.number ?? page,
        size: response.data?.size ?? size,
      },
      isFallback: false,
    }
  } catch (error) {
    if (error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED') {
      throw error
    }
    if (!shouldFallbackToLocal(error)) {
      throw error
    }

    let filtered = [...readLocalListings()]
    if (categoryInactiveOnly) {
      filtered = filtered.filter((item) => item.categoryActive === false)
    } else if (status && status !== 'ALL') {
      filtered = filtered.filter((item) => item.status === status)
    }

    const q = search.trim().toLowerCase()
    if (q) {
      filtered = filtered.filter(
        (item) =>
          item.title.toLowerCase().includes(q) ||
          (item.categoryName || '').toLowerCase().includes(q) ||
          (item.district || '').toLowerCase().includes(q) ||
          (item.baseCity || '').toLowerCase().includes(q),
      )
    }

    filtered.sort((a, b) => {
      if (sortBy === 'price_asc') return Number(a.basePrice || 0) - Number(b.basePrice || 0)
      if (sortBy === 'price_desc') return Number(b.basePrice || 0) - Number(a.basePrice || 0)
      if (sortBy === 'rating_desc') return Number(b.ratingAvg || 0) - Number(a.ratingAvg || 0)
      return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    })

    const totalElements = filtered.length
    const totalPages = Math.max(1, Math.ceil(totalElements / size))
    const safePage = Math.min(page, totalPages - 1)
    const content = filtered.slice(safePage * size, safePage * size + size)

    return {
      pageData: {
        content,
        totalElements,
        totalPages,
        number: safePage,
        size,
      },
      isFallback: true,
    }
  }
}

export async function getListingById(id, signal) {
  try {
    const response = await listingHttp.get(`/listings/${id}`, { signal })
    return { listing: response.data, isFallback: false }
  } catch (error) {
    if (error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED') {
      throw error
    }
    if (!shouldFallbackToLocal(error)) {
      throw error
    }
    const found = readLocalListings().find((item) => item.id === id)
    if (!found) {
      const notFoundError = new Error('Listing not found')
      notFoundError.response = { status: 404, data: { message: 'Listing not found' } }
      throw notFoundError
    }
    return { listing: found, isFallback: true }
  }
}

export async function createListing(payload, categories = FALLBACK_CATEGORIES) {
  try {
    const response = await listingHttp.post('/listings', payload)
    return { listing: response.data, isFallback: false }
  } catch (error) {
    if (!shouldFallbackToLocal(error)) {
      throw error
    }

    const screening = screenListingContent({
      title: payload.title,
      description: payload.description,
      addOns: payload.addOns,
    })
    const catMeta = resolveCategoryMetadata(payload.categoryId, categories)
    const now = new Date().toISOString()
    const nextStatus = payload.saveAsDraft ? 'DRAFT' : screening.flagged ? 'FLAGGED' : 'ACTIVE'
    const newListing = {
      id: `lst-${Date.now()}`,
      providerId: 'prov-nimal',
      categoryId: payload.categoryId,
      categoryName: catMeta.categoryName,
      subcategoryName: catMeta.subcategoryName,
      parentCategoryId: catMeta.parentCategoryId,
      title: payload.title,
      description: payload.description || '',
      pricingModel: payload.pricingModel || 'FIXED',
      basePrice: Number(payload.basePrice || 0),
      durationMin: Number(payload.durationMin || 60),
      executionModel: payload.executionModel || 'ON_SITE',
      district: payload.district,
      baseCity: payload.baseCity || payload.district,
      location: payload.location || { longitude: 79.8612, latitude: 6.9271 },
      coverageRadiusKm: Number(payload.coverageRadiusKm ?? 15),
      serviceAreas: Array.isArray(payload.serviceAreas) ? payload.serviceAreas : [],
      images: Array.isArray(payload.images) && payload.images.length > 0 ? payload.images : [DEFAULT_PORTFOLIO_THUMBNAILS.plumbing],
      coverImageUrl: payload.coverImageUrl || payload.images?.[0] || DEFAULT_PORTFOLIO_THUMBNAILS.plumbing,
      addOns: Array.isArray(payload.addOns) ? payload.addOns : [],
      status: nextStatus,
      availableForBooking: nextStatus === 'ACTIVE' && catMeta.categoryActive,
      categoryActive: catMeta.categoryActive,
      activeAddOnCount: (payload.addOns || []).filter((a) => a.active !== false).length,
      moderationFlags: screening.reasons,
      ratingAvg: 0,
      ratingCount: 0,
      inquiryCount: 0,
      viewCount: 0,
      createdAt: now,
      updatedAt: now,
    }
    const existing = readLocalListings()
    writeLocalListings([newListing, ...existing])
    return { listing: newListing, isFallback: true }
  }
}

export async function updateListing(id, payload, categories = FALLBACK_CATEGORIES) {
  try {
    const response = await listingHttp.put(`/listings/${id}`, payload)
    return { listing: response.data, isFallback: false }
  } catch (error) {
    if (!shouldFallbackToLocal(error)) {
      throw error
    }

    const screening = screenListingContent({
      title: payload.title,
      description: payload.description,
      addOns: payload.addOns,
    })
    const catMeta = resolveCategoryMetadata(payload.categoryId, categories)
    const now = new Date().toISOString()
    const existing = readLocalListings()
    const index = existing.findIndex((item) => item.id === id)
    if (index === -1) {
      throw error
    }

    const current = existing[index]
    const nextStatus = payload.saveAsDraft
      ? 'DRAFT'
      : screening.flagged
        ? 'FLAGGED'
        : current.status === 'DRAFT'
          ? 'ACTIVE'
          : current.status

    const updated = {
      ...current,
      ...payload,
      categoryName: catMeta.categoryName,
      subcategoryName: catMeta.subcategoryName,
      parentCategoryId: catMeta.parentCategoryId,
      basePrice: Number(payload.basePrice ?? current.basePrice ?? 0),
      durationMin: Number(payload.durationMin ?? current.durationMin ?? 60),
      status: nextStatus,
      categoryActive: catMeta.categoryActive,
      availableForBooking: nextStatus === 'ACTIVE' && catMeta.categoryActive,
      activeAddOnCount: (payload.addOns || []).filter((a) => a.active !== false).length,
      moderationFlags: screening.reasons,
      updatedAt: now,
    }
    const nextList = [...existing]
    nextList[index] = updated
    writeLocalListings(nextList)
    return { listing: updated, isFallback: true }
  }
}

export async function updateListingStatus(id, { status, reason } = {}) {
  try {
    const response = await listingHttp.patch(`/listings/${id}/status`, { status, reason })
    return { listing: response.data, isFallback: false }
  } catch (error) {
    if (!shouldFallbackToLocal(error)) {
      throw error
    }

    const existing = readLocalListings()
    const index = existing.findIndex((item) => item.id === id)
    if (index === -1) {
      throw error
    }
    const current = existing[index]
    const updated = {
      ...current,
      status,
      availableForBooking: status === 'ACTIVE' && current.categoryActive !== false,
      updatedAt: new Date().toISOString(),
    }
    const nextList = [...existing]
    nextList[index] = updated
    writeLocalListings(nextList)
    return { listing: updated, isFallback: true }
  }
}

export function extractListingApiError(error, fallbackMessage = 'Request failed') {
  const data = error?.response?.data
  if (data?.fieldErrors?.length > 0) {
    const first = data.fieldErrors[0]
    return first.message || data.message || fallbackMessage
  }
  return data?.message || error?.message || fallbackMessage
}
