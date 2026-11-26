export const PUBLIC_CITIES = Object.freeze([
  { id: 'Colombo', label: 'Colombo', region: 'Western Province', lat: 6.9271, lng: 79.8612 },
  { id: 'Gampaha', label: 'Gampaha', region: 'Western Province', lat: 7.0873, lng: 80.0144 },
  { id: 'Kandy', label: 'Kandy', region: 'Central Province', lat: 7.2906, lng: 80.6337 },
  { id: 'Galle', label: 'Galle', region: 'Southern Province', lat: 6.0535, lng: 80.221 },
  { id: 'Jaffna', label: 'Jaffna', region: 'Northern Province', lat: 9.6615, lng: 80.0255 },
  { id: 'All Districts', label: 'All Districts', region: 'Islandwide (25 Districts)', lat: 7.72, lng: 80.58 },
])

export const PUBLIC_BRAND = Object.freeze({
  name: 'LocalServe',
  nameStart: 'Local',
  nameEnd: 'Serve',
  operatingCompany: 'Gamage Recruiters (PVT) Ltd.',
  tagline:
    'Sri Lanka’s AI-powered local services marketplace by Gamage Recruiters (PVT) Ltd. — connecting customers with admin-verified providers under escrow and 72-hour dispute protection.',
  trustBadge: 'Admin-Verified Providers · Escrow & 72h Dispute Protection',
  supportEmail: 'support@localserve.lk',
  supportHours: 'Support Desk · Sri Lanka (9 Provinces · 25 Districts)',
  copyright: '© 2026 LocalServe · Gamage Recruiters (PVT) Ltd.',
  navLinks: [
    { label: 'Home', to: '/' },
    { label: 'Find Services', to: '/search' },
  ],
  footerLinks: [
    { label: 'How It Works', href: '#how-market-works' },
    { label: 'Help & FAQ', href: '#landing-faq' },
    { label: 'Reviews', href: '#verified-reviews' },
    { label: 'Top', href: '#top' },
  ],
  footerColumns: {
    services: [
      { label: 'Electrical & Solar', to: '/search?q=Electrical' },
      { label: 'Plumbing & Water', to: '/search?q=Plumbing' },
      { label: 'AC & Cooling', to: '/search?q=AC' },
      { label: 'Home Cleaning', to: '/search?q=Cleaning' },
      { label: 'Browse All Services', to: '/search' },
    ],
    cities: [
      { label: 'Colombo', to: '/search?location=Colombo' },
      { label: 'Gampaha', to: '/search?location=Gampaha' },
      { label: 'Kandy', to: '/search?location=Kandy' },
      { label: 'Galle', to: '/search?location=Galle' },
      { label: 'Jaffna', to: '/search?location=Jaffna' },
    ],
    platform: [
      { label: 'How It Works', href: '#how-market-works' },
      { label: 'Service Directory', href: '#service-directory' },
      { label: 'Featured Services', href: '#featured-services' },
      { label: 'Become a Provider', to: '/register?role=provider' },
      { label: 'Common Questions (FAQ)', href: '#landing-faq' },
    ],
  },
  legalLinks: [
    { label: 'Verified Reviews', href: '#verified-reviews' },
    { label: 'Escrow & Disputes', href: '#how-market-works' },
    { label: 'Privacy & Terms', href: '#landing-faq' },
  ],
})
