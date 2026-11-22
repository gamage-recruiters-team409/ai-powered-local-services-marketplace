export const PUBLIC_CITIES = Object.freeze([
  { id: 'Colombo', label: 'Colombo', availablePros: 42, region: 'Western Province' },
  { id: 'Kandy', label: 'Kandy', availablePros: 28, region: 'Central Province' },
  { id: 'Galle', label: 'Galle', availablePros: 19, region: 'Southern Province' },
  { id: 'Gampaha', label: 'Gampaha', availablePros: 31, region: 'Western Province' },
  { id: 'Other City', label: 'Other City', availablePros: 118, region: 'Islandwide (25 Districts)' },
])

export const PUBLIC_BRAND = Object.freeze({
  name: 'LocalServe',
  nameStart: 'Local',
  nameEnd: 'Serve',
  copyright: '© 2026 LocalServe - Sri Lanka',
  navLinks: [
    { label: 'Home', to: '/' },
    { label: 'Find Services', to: '/search' },
  ],
  footerLinks: [
    { label: 'About', href: '#how-market-works' },
    { label: 'Help Center', href: '#service-directory' },
    { label: 'Terms', href: '#verified-reviews' },
    { label: 'Privacy', href: '#top' },
  ],
})
