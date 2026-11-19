import { Icon } from '../../../shared/layouts/admin'

/**
 * Icons the shared admin icon set does not have yet (same 24px stroke style). Names that exist in the shared set
 * are passed through to it, so pages can use one component for both.
 */
const PATHS = {
  globe: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21a8 8 0 0 1 16 0" />
    </>
  ),
  briefcase: (
    <>
      <rect x="3" y="7" width="18" height="13" rx="2" />
      <path d="M9 7V5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2M3 13h18" />
    </>
  ),
  megaphone: (
    <>
      <path d="M3 11v2a1 1 0 0 0 1 1h3l6 4V6L7 10H4a1 1 0 0 0-1 1Z" />
      <path d="M17 9a4 4 0 0 1 0 6M19.5 6.5a8 8 0 0 1 0 11" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  pencil: <path d="m4 20 4-1 11-11-3-3L5 16l-1 4ZM14 6l3 3" />,
  trash: <path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6" />,
  search: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </>
  ),
  filter: <path d="M4 5h16l-6 8v5l-4 2v-7L4 5Z" />,
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5M12 8h.01" />
    </>
  ),
  send: <path d="M4 12 20 4l-6 16-3-7-7-1Z" />,
  bookmark: <path d="M6 3h12v18l-6-4-6 4V3Z" />,
  arrowLeft: <path d="M19 12H5M11 6l-6 6 6 6" />,
  bold: <path d="M7 4h6a4 4 0 0 1 0 8H7V4ZM7 12h7a4 4 0 0 1 0 8H7v-8Z" />,
  italic: <path d="M14 4h-4M14 20h-4M15 4 9 20" />,
  link: <path d="M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1" />,
  list: <path d="M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01" />,
  listOrdered: <path d="M10 6h10M10 12h10M10 18h10M4 4v4M3 14h2l-2 3h2M3 4h1" />,
  pin: <path d="M12 17v4M8 3h8l-1 6 3 3v2H6v-2l3-3-1-6Z" />,
  archive: (
    <>
      <rect x="3" y="4" width="18" height="4" rx="1" />
      <path d="M5 8v11h14V8M10 12h4" />
    </>
  ),
  alertTriangle: <path d="M12 3 2 20h20L12 3ZM12 10v4M12 17h.01" />,
  refresh: <path d="M20 11a8 8 0 0 0-14.3-4.9L4 8M4 4v4h4M4 13a8 8 0 0 0 14.3 4.9L20 16M20 20v-4h-4" />,
}

export function AnnIcon({ name, size = 16, className, strokeWidth = 1.8 }) {
  const path = PATHS[name]
  if (!path) {
    return <Icon name={name} size={size} className={className} strokeWidth={strokeWidth} />
  }
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {path}
    </svg>
  )
}
