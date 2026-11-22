export const HERO_FRAME_CONFIG = Object.freeze({
  basePath: '/frames',
  prefix: 'frame_',
  extension: '.webp',
  padLength: 4,
  startIndex: 1,
  /**
   * Verified count of numbered `.webp` frames in `/public/frames/`.
   * Set to the exact count (e.g., 120) once `frame_0001.webp..frame_XXXX.webp` are added;
   * while `0`, the high-DPI canvas renders `/frames/hero-poster.webp` with smooth spring-scrubbed
   * cinematic zoom/pan so zero 404 requests ever occur.
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
  'Emergency Plumbing...',
  'Solar Technician...',
])

export const TRUST_METRICS = Object.freeze([
  {
    id: 'middleman-fees',
    label: 'Middleman Fees (Direct Booking)',
    prefix: '',
    target: 0,
    suffix: '%',
    arcFill: 0.92,
    accent: '#0d9488',
    caption: 'Pay technicians directly at published rates',
  },
  {
    id: 'vetted-pros',
    label: 'NIC & NVQ Vetted Pros',
    prefix: '',
    target: 100,
    suffix: '%',
    arcFill: 1.0,
    accent: '#0f766e',
    caption: 'Government ID & trade credentials verified',
  },
  {
    id: 'escrow-cover',
    label: 'Platform Escrow Cover',
    prefix: 'Rs. ',
    target: 1000000,
    suffix: '',
    arcFill: 0.88,
    accent: '#0d9488',
    caption: 'Protected until you inspect and sign off',
    isOdometer: true,
  },
  {
    id: 'prepayment-scams',
    label: 'Pre-Payment Scams',
    prefix: '',
    target: 0,
    suffix: '%',
    arcFill: 0.96,
    accent: '#0f766e',
    caption: 'Funds stay locked until work is complete',
  },
])

export const HOW_IT_WORKS_STEPS = Object.freeze([
  {
    number: '01',
    title: 'Providers List Their Services',
    description:
      'Certified technicians publish transparent fixed or hourly rates, verified NIC & NVQ trade credentials, clear scopes of work, and real portfolio photos.',
    highlightLeft: 'Fixed / Hourly Rates',
    highlightRight: 'Zero Hidden Markup',
    icon: 'wrench',
    imageUrl: '/landing/step-01-list.jpg',
    imageAlt: 'Verified Sri Lankan technician reviewing published service listings on a tablet',
  },
  {
    number: '02',
    title: 'Customers Compare & Select',
    description:
      'Compare direct rates side-by-side, inspect verified government ID badges, read authentic local reviews, and pick the best technician for your district.',
    highlightLeft: 'Verified Badges',
    highlightRight: 'Real Local Reviews',
    icon: 'award',
    imageUrl: '/landing/step-02-compare.jpg',
    imageAlt: 'Sri Lankan homeowners comparing verified technician profiles and rates on a laptop',
  },
  {
    number: '03',
    title: 'Direct Booking & Escrow Release',
    description:
      'Coordinate directly via chat or WhatsApp. Your booking payment stays safely locked in LocalServe escrow until you inspect and sign off on completed work.',
    highlightLeft: '100% Escrow Protection',
    highlightRight: 'Direct WhatsApp / Chat',
    icon: 'shield',
    imageUrl: '/landing/step-03-escrow.jpg',
    imageAlt: 'Homeowner approving completed air conditioning service on phone to release escrow payment',
  },
])

export const ESCROW_SIMULATOR_STAGES = Object.freeze([
  {
    id: 'locked',
    step: 1,
    label: '1. Book & Lock Funds',
    statusLabel: 'Locked Safely in Escrow',
    statusTone: 'locked',
    amountHeld: 'Rs. 25,000',
    providerReceives: 'Rs. 0',
    summary:
      'Customer books a Full AC & Electrical Tune-up. Rs. 25,000 is held securely in LocalServe Escrow — never paid upfront to an unverified stranger.',
  },
  {
    id: 'in-progress',
    step: 2,
    label: '2. Pro Completes Work',
    statusLabel: 'Work Verified On-Site',
    statusTone: 'progress',
    amountHeld: 'Rs. 25,000',
    providerReceives: 'Rs. 0 (Pending Sign-Off)',
    summary:
      'NIC & NVQ vetted technician arrives on schedule, completes diagnostics and repair, and submits completion photos through LocalServe.',
  },
  {
    id: 'released',
    step: 3,
    label: '3. Customer Signs Off',
    statusLabel: 'Escrow Released to Pro',
    statusTone: 'released',
    amountHeld: 'Rs. 0',
    providerReceives: 'Rs. 25,000 Released',
    summary:
      'Homeowner inspects the finished work and taps Approve. LocalServe immediately releases Rs. 25,000 to the technician with zero middleman markup.',
  },
])

export const CURATED_CATEGORIES = Object.freeze([
  {
    id: 'plumbing-water',
    name: 'Plumbing & Water Systems',
    slug: 'plumbing-and-water-systems',
    description: 'Licensed pipe, leak, bathroom fitting, and water tank specialists.',
    icon: 'water',
    subcategories: [
      { id: 'pipe-leak', name: 'Emergency Pipe Leak Repair', slug: 'emergency-pipe-leak-repair' },
      { id: 'bath-fitting', name: 'Bathroom Fitting & Fixtures', slug: 'bathroom-fitting-and-fixtures' },
      { id: 'water-tank', name: 'Water Tank & Motor Maintenance', slug: 'water-tank-and-motor-maintenance' },
      { id: 'drainage', name: 'Drainage Unclogging & Cleanout', slug: 'drainage-unclogging-and-cleanout' },
    ],
  },
  {
    id: 'electrical-solar',
    name: 'Electrical & Solar Systems',
    slug: 'electrical-and-solar-systems',
    description: 'NVQ-certified house wiring, breaker repair, and hybrid solar pros.',
    icon: 'sun',
    subcategories: [
      { id: 'circuit-breaker', name: 'Circuit & Breaker Repair', slug: 'circuit-and-breaker-repair' },
      { id: 'full-wiring', name: 'Full House Wiring', slug: 'full-house-wiring' },
      { id: 'solar-inverter', name: 'Solar Inverter Maintenance', slug: 'solar-inverter-maintenance' },
      { id: 'lighting-fans', name: 'Lighting & Ceiling Fans', slug: 'lighting-and-ceiling-fans' },
    ],
  },
  {
    id: 'ac-cooling',
    name: 'AC & Cooling Services',
    slug: 'ac-and-cooling-services',
    description: 'Residential split AC gas refills, servicing, installations, and duct care.',
    icon: 'temp',
    subcategories: [
      { id: 'ac-gas-refill', name: 'AC Gas Refill & Servicing', slug: 'ac-gas-refill-and-servicing' },
      { id: 'split-install', name: 'Split Unit Installation', slug: 'split-unit-installation' },
      { id: 'cooling-diag', name: 'Cooling Diagnostics', slug: 'cooling-diagnostics' },
      { id: 'duct-cleaning', name: 'Commercial Duct Cleaning', slug: 'commercial-duct-cleaning' },
    ],
  },
  {
    id: 'cleaning-pest',
    name: 'Home Deep Cleaning & Pest Control',
    slug: 'home-deep-cleaning-and-pest-control',
    description: 'Eco-safe deep cleaning, sofa steam sanitization, and pest protection.',
    icon: 'sparkles',
    subcategories: [
      { id: 'full-deep-clean', name: 'Full Home Deep Clean', slug: 'full-home-deep-clean' },
      { id: 'sofa-steam', name: 'Sofa & Mattress Steam', slug: 'sofa-and-mattress-steam' },
      { id: 'eco-pest', name: 'Eco Pest Control', slug: 'eco-pest-control' },
      { id: 'post-reno', name: 'Post-Renovation Cleanup', slug: 'post-renovation-cleanup' },
    ],
  },
  {
    id: 'carpentry-masonry',
    name: 'Carpentry, Masonry & Painting',
    slug: 'carpentry-masonry-and-painting',
    description: 'Custom teak pantry work, interior painting, waterproofing, and tiling.',
    icon: 'pencil',
    subcategories: [
      { id: 'painting', name: 'Interior/Exterior Painting', slug: 'interior-exterior-painting' },
      { id: 'waterproofing', name: 'Waterproofing & Roof Seal', slug: 'waterproofing-and-roof-seal' },
      { id: 'pantry-doors', name: 'Custom Pantry & Door Repair', slug: 'custom-pantry-and-door-repair' },
      { id: 'tile-grout', name: 'Tile & Grout Restoration', slug: 'tile-and-grout-restoration' },
    ],
  },
])

export const VERIFIED_REVIEWS = Object.freeze([
  {
    id: 'rev-1',
    initials: 'DP',
    customerName: 'Duminda P.',
    location: 'Havelock Town',
    rating: 5.0,
    serviceTag: 'Emergency Pipe Leak Repair',
    quote:
      'Main line burst on a Sunday morning. The technician arrived in Havelock Town within 40 minutes, quoted exact parts upfront, and completed pressure testing before I released escrow.',
    meta: 'Escrow Released · Verified Job #LK-9021 · 2 days ago',
  },
  {
    id: 'rev-2',
    initials: 'SR',
    customerName: 'Sanduni R.',
    location: 'Kandy',
    rating: 5.0,
    serviceTag: 'Solar Inverter Maintenance',
    quote:
      'Our 5kW hybrid inverter tripped after heavy rain. Booked an NVQ-verified electrician directly with zero broker fees — fixed the surge protector and restored grid export the same afternoon.',
    meta: 'Escrow Released · Verified Job #LK-8843 · 4 days ago',
  },
  {
    id: 'rev-3',
    initials: 'NF',
    customerName: 'Nihal F.',
    location: 'Galle',
    rating: 5.0,
    serviceTag: 'AC Gas Refill & Servicing',
    quote:
      'Serviced three split AC units at our coastal villa. Clear fixed rate per unit, spotless chemical wash, and escrow release took one tap once cooling temperatures were verified.',
    meta: 'Escrow Released · Verified Job #LK-8790 · 5 days ago',
  },
  {
    id: 'rev-4',
    initials: 'AM',
    customerName: 'Ayesha M.',
    location: 'Negombo',
    rating: 5.0,
    serviceTag: 'Full Home Deep Clean',
    quote:
      'Hired a verified 3-person sanitization crew before moving in. Every team member had NIC verification visible on their profile, arrived on time, and left the kitchen and bathrooms sparkling.',
    meta: 'Escrow Released · Verified Job #LK-8712 · 1 week ago',
  },
  {
    id: 'rev-5',
    initials: 'KW',
    customerName: 'Kasun W.',
    location: 'Dehiwala',
    rating: 5.0,
    serviceTag: 'Waterproofing & Roof Seal',
    quote:
      'Having Rs. 1,000,000 escrow cover gave us total peace of mind for our rooftop terrace waterproofing. The mason documented every coat with photos before we approved final payout.',
    meta: 'Escrow Released · Verified Job #LK-8645 · 1 week ago',
  },
  {
    id: 'rev-6',
    initials: 'TH',
    customerName: 'Tharindu H.',
    location: 'Gampaha',
    rating: 5.0,
    serviceTag: 'Circuit & Breaker Repair',
    quote:
      'Kitchen trip switch kept failing every evening. Compared two local pros side-by-side, booked direct at the published hourly rate, and had a faulty RCCB replaced cleanly in under an hour.',
    meta: 'Escrow Released · Verified Job #LK-8599 · 2 weeks ago',
  },
])
