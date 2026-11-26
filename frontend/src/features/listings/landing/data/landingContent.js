export const HERO_FRAME_CONFIG = Object.freeze({
  basePath: '/frames',
  prefix: 'frame_',
  extension: '.webp',
  padLength: 4,
  startIndex: 1,
  /**
   * Verified count of numbered `.webp` frames in `/public/frames/`.
   * While `0`, the high-DPI canvas renders `/landing/hero-technician.jpg` with smooth
   * spring-scrubbed zoom/pan so zero 404 requests ever occur.
   */
  frameCount: 0,
  posterUrl: '/landing/hero-technician.jpg',
  eagerPreloadCount: 15,
})

export function buildFrameUrl(index) {
  const num = String(HERO_FRAME_CONFIG.startIndex + index).padStart(
    HERO_FRAME_CONFIG.padLength,
    '0'
  )
  return `${HERO_FRAME_CONFIG.basePath}/${HERO_FRAME_CONFIG.prefix}${num}${HERO_FRAME_CONFIG.extension}`
}

export const ROTATING_PLACEHOLDERS = Object.freeze([
  'Electrician...',
  'AC Repair...',
  'Plumbing...',
  'Home Cleaning...',
])

/**
 * 100% SRS-grounded platform trust standards (FR-PROV-003, BR-002, BR-007, BR-008,
 * BR-009, and SriLankaDistrictGeoService 25-district catalog). Zero fabricated stats.
 */
export const TRUST_METRICS = Object.freeze([
  {
    id: 'admin-verified-providers',
    label: 'Admin-Verified Providers',
    prefix: '',
    target: 100,
    suffix: '%',
    arcFill: 1.0,
    accent: '#0d9488',
    caption: 'Only approved providers can publish active listings',
  },
  {
    id: 'completion-review-window',
    label: 'Completion Review Window',
    prefix: '',
    target: 48,
    suffix: ' hrs',
    arcFill: 0.92,
    accent: '#0f766e',
    caption: 'Customer inspection window before auto-confirmation',
  },
  {
    id: 'dispute-protection-window',
    label: 'Escrow Dispute Protection',
    prefix: '',
    target: 72,
    suffix: ' hrs',
    arcFill: 0.96,
    accent: '#0d9488',
    caption: 'Funds held securely until dispute window clears',
    isOdometer: true,
  },
  {
    id: 'sri-lanka-districts',
    label: 'Sri Lankan Districts Supported',
    prefix: '',
    target: 25,
    suffix: '',
    arcFill: 0.94,
    accent: '#0f766e',
    caption: 'All 9 provinces with canonical GeoJSON coverage',
  },
])

export const HOW_IT_WORKS_STEPS = Object.freeze([
  {
    number: '01',
    title: 'Verified Providers Publish Services',
    description:
      'Independent technicians submit NIC/Passport, business registration, and trade documents for manual Admin approval before publishing fixed, hourly, or quote LKR packages with up to 8 portfolio photos.',
    highlightLeft: 'Fixed / Hourly / Quote',
    highlightRight: 'Admin-Approved Only',
    icon: 'wrench',
    imageUrl: '/landing/step-01-list.jpg',
    imageAlt: 'Verified Sri Lankan technician reviewing published service listings on a tablet',
  },
  {
    number: '02',
    title: 'Compare & Reserve Verified Slots',
    description:
      'Search by keyword, category, or Sri Lankan district, compare up to 3 verified providers side-by-side, inspect price-locked add-ons, and reserve real availability slots with atomic double-booking locks.',
    highlightLeft: 'Compare Up to 3 Pros',
    highlightRight: 'No Double-Booking',
    icon: 'award',
    imageUrl: '/landing/step-02-compare.jpg',
    imageAlt: 'Sri Lankan homeowners comparing verified technician profiles and rates on a laptop',
  },
  {
    number: '03',
    title: 'Masked In-App Chat & Escrow Release',
    description:
      'Coordinate safely via real-time in-app chat (contact details stay masked until booking confirmation). Payment stays held in platform escrow through the 48-hour confirmation and 72-hour dispute windows.',
    highlightLeft: 'Masked In-App Chat',
    highlightRight: '72h Dispute Protection',
    icon: 'shield',
    imageUrl: '/landing/step-03-escrow.jpg',
    imageAlt: 'Homeowner confirming completed service on phone to finalize booking lifecycle',
  },
])

/**
 * Exact SRS Booking Payment & Escrow Lifecycle (BR-004, BR-005, BR-007, BR-008, BR-009).
 * Illustrative policy walkthrough with zero fabricated job amounts.
 */
export const ESCROW_SIMULATOR_STAGES = Object.freeze([
  {
    id: 'held',
    step: 1,
    label: '1. Confirmed & Held',
    statusLabel: 'Payment Held in Escrow',
    statusTone: 'locked',
    amountHeld: '100% Held in Escrow',
    providerReceives: 'Locked (Not Released Yet)',
    summary:
      'Once the provider accepts your booking request and online payment is authorized, funds are captured and held securely in platform escrow — never sent upfront.',
  },
  {
    id: 'completion-pending',
    step: 2,
    label: '2. 48h Customer Review',
    statusLabel: 'Completion Pending (48h Window)',
    statusTone: 'progress',
    amountHeld: 'Held Through Review',
    providerReceives: 'Pending Customer Sign-Off',
    summary:
      'After the technician completes the job and marks completion, you receive 48 hours to inspect the work and confirm — or raise an issue before automatic confirmation.',
  },
  {
    id: 'released',
    step: 3,
    label: '3. 72h Cleared & Payout',
    statusLabel: 'Released to Provider Earnings',
    statusTone: 'released',
    amountHeld: 'Window Cleared (72h)',
    providerReceives: 'Net Payout (After Commission)',
    summary:
      'Once the 72-hour dispute window closes with zero open disputes, net earnings — after the transparent category platform commission (10% default) — become available for bank payout.',
  },
])

/**
 * Canonical Sri Lankan highlight districts matching `SriLankaDistrictGeoService.java`.
 * Replaced dynamically with all 25 official districts from `GET /api/v1/listings/locations/districts`.
 */
export const SRI_LANKA_FALLBACK_DISTRICTS = Object.freeze([
  {
    id: 'colombo',
    city: 'Colombo',
    province: 'Western Province',
    lat: 6.9271,
    lng: 79.8612,
    zoom: 11,
  },
  {
    id: 'gampaha',
    city: 'Gampaha',
    province: 'Western Province',
    lat: 7.0873,
    lng: 80.0144,
    zoom: 11,
  },
  {
    id: 'kandy',
    city: 'Kandy',
    province: 'Central Province',
    lat: 7.2906,
    lng: 80.6337,
    zoom: 11,
  },
  {
    id: 'galle',
    city: 'Galle',
    province: 'Southern Province',
    lat: 6.0535,
    lng: 80.221,
    zoom: 11,
  },
  {
    id: 'jaffna',
    city: 'Jaffna',
    province: 'Northern Province',
    lat: 9.6615,
    lng: 80.0255,
    zoom: 11,
  },
])

/**
 * 5 well-spaced canonical pin anchors displayed on Island View (plus any active user-selected district).
 */
export const SRI_LANKA_ISLAND_PIN_DIRECTIONS = Object.freeze({
  Colombo: 'left',
  Gampaha: 'top',
  Kandy: 'right',
  Galle: 'left',
  Jaffna: 'right',
})

export const DEFAULT_TRADE_FILTERS = Object.freeze([
  'All Services',
  'Electrical',
  'Plumbing',
  'AC & Cooling',
  'Home Cleaning',
])

export const PROVIDER_CONSTELLATION_TRADES = Object.freeze([
  { id: 'trade-1', code: 'EL', label: 'Electrical', detail: 'Wiring & Solar', tone: 'sky', position: 'topLeft' },
  { id: 'trade-2', code: 'AC', label: 'HVAC & AC', detail: 'Cooling & Repair', tone: 'amber', position: 'topRight' },
  { id: 'trade-3', code: 'PL', label: 'Plumbing', detail: 'Pipes & Pumps', tone: 'emerald', position: 'bottomLeft' },
  { id: 'trade-4', code: 'CL', label: 'Cleaning', detail: 'Deep Sanitization', tone: 'violet', position: 'bottomRight' },
])

export const PROVIDER_CONSTELLATION_BUBBLES = Object.freeze([
  { id: 'bub-1', text: 'Admin-Verified Badge', tone: 'mint', position: 'top' },
  { id: 'bub-2', text: 'AI Listing Assistant', tone: 'dark', position: 'right' },
  { id: 'bub-3', text: 'Escrow Bank Payouts', tone: 'amber', position: 'bottom' },
])

export const LANDING_FAQ_ITEMS = Object.freeze([
  {
    id: 'faq-escrow',
    question: 'How do escrow payments and the 72-hour dispute window work?',
    answer:
      'When a provider accepts your booking and payment is authorized, funds stay held securely in platform escrow. After work is completed, you have 48 hours to inspect and confirm, followed by a 72-hour dispute protection window before provider earnings become available for payout.',
  },
  {
    id: 'faq-verification',
    question: 'How are service providers verified before appearing on LocalServe?',
    answer:
      'Providers register as an individual or business and submit their NIC/Passport, business registration, and trade certificates to secure private storage. Admins manually review each submission — only approved providers receive the Verified badge and can publish active listings.',
  },
  {
    id: 'faq-pricing-commission',
    question: 'How do pricing, platform commissions, and cancellations work?',
    answer:
      'Providers publish transparent Fixed, Hourly, or Quote LKR prices plus optional add-ons that are price-locked when you book. A transparent platform commission (default 10%, configurable per category) is deducted from provider earnings. Customer cancellations receive a 100% refund over 24 hours ahead, 50% between 6–24 hours, and 100% if a provider cancels.',
  },
  {
    id: 'faq-provider-join',
    question: 'How can skilled technicians register and list their services?',
    answer:
      'Click "Become a Provider" to create your account, complete your provider profile and verification documents, and once approved by Admin, publish service listings with up to 8 portfolio images, weekly availability schedules, and AI-assisted service descriptions.',
  },
])
