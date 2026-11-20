import { AnnIcon } from '../../admin-announcements/components/AnnIcon'

/**
 * Icons the admin users screens need on top of the shared admin set and the announcement extras (same 24px stroke
 * style). Any other name is passed on to AnnIcon, which falls back to the shared admin icons.
 */
const PATHS = {
  ban: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m5.6 5.6 12.8 12.8" />
    </>
  ),
  download: <path d="M12 4v11m0 0-4-4m4 4 4-4M5 19h14" />,
  mail: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="m3 7 9 6 9-6" />
    </>
  ),
  phone: <path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2Z" />,
  checkCircle: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m8 12 3 3 5-6" />
    </>
  ),
  mapPin: (
    <>
      <path d="M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11Z" />
      <circle cx="12" cy="10" r="2.5" />
    </>
  ),
  image: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <circle cx="9" cy="10" r="2" />
      <path d="m21 16-5-5-9 9" />
    </>
  ),
  eyeOff: (
    <>
      <path d="M3 3l18 18M10.6 6.1A9.8 9.8 0 0 1 12 6c5.5 0 9 6 9 6a16.7 16.7 0 0 1-3.2 3.9M6.3 7.6C4.1 9.1 3 12 3 12s3.5 6 9 6a9.4 9.4 0 0 0 4.1-.9" />
      <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
    </>
  ),
  inbox: (
    <>
      <path d="M3 13h5l1.5 3h5L16 13h5" />
      <path d="M5 5h14l2 8v6H3v-6l2-8Z" />
    </>
  ),
}

export function UsersIcon({ name, size = 16, className, strokeWidth = 1.8 }) {
  const path = PATHS[name]
  if (!path) return <AnnIcon name={name} size={size} className={className} strokeWidth={strokeWidth} />
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
