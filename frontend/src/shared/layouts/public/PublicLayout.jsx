import { useEffect } from 'react'
import { PublicNavbar } from './PublicNavbar'
import { PublicFooter } from './PublicFooter'

const DISPLAY_FONT_LINK_ID = 'localserve-bricolage-font'
const DISPLAY_FONT_HREF =
  'https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:wght@600;700;800&display=swap'

export function PublicLayout({
  selectedCity,
  onCityChange,
  children,
}) {
  // Load Bricolage Grotesque strictly from PublicLayout so shared frontend/index.html stays untouched
  useEffect(() => {
    if (typeof document === 'undefined' || document.getElementById(DISPLAY_FONT_LINK_ID)) {
      return
    }
    const link = document.createElement('link')
    link.id = DISPLAY_FONT_LINK_ID
    link.rel = 'stylesheet'
    link.href = DISPLAY_FONT_HREF
    document.head.appendChild(link)
  }, [])

  return (
    <div id="top">
      <PublicNavbar selectedCity={selectedCity} onCityChange={onCityChange} />
      <main>{children}</main>
      <PublicFooter />
    </div>
  )
}
