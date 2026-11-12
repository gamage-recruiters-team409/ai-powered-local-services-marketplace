/**
 * Brand settings for the admin layout. Names, logo and footer may change:
 * update them here only.
 *
 * logoSrc: image URL for a custom logo; null uses the default wrench icon.
 */
export const ADMIN_BRAND = Object.freeze({
  name: 'LocalServe',
  // Ending of the name shown in the primary colour ("Local" dark, "Serve" teal). Empty = one colour.
  nameHighlight: 'Serve',
  portalLabel: 'Admin portal',
  logoSrc: null,
  footerText: `© ${new Date().getFullYear()} Local Services Marketplace`,
  footerLinks: [
    { label: 'Help', href: '/help' },
    { label: 'Terms', href: '/terms' },
    { label: 'Privacy', href: '/privacy' },
  ],
})
