/**
 * Brand settings for the admin layout. Names, logo and footer may change:
 * update them here only.
 *
 * logoSrc: image URL for a custom logo; null uses the default house icon.
 */
export const ADMIN_BRAND = Object.freeze({
  name: 'Local Services Marketplace',
  portalLabel: 'Admin portal',
  logoSrc: null,
  footerText: `© ${new Date().getFullYear()} Local Services Marketplace`,
  footerLinks: [
    { label: 'Help', href: '/help' },
    { label: 'Terms', href: '/terms' },
    { label: 'Privacy', href: '/privacy' },
  ],
})
