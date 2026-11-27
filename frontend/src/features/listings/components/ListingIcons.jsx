/**
 * Crisp zero-dependency stroke SVG icons for WP03 Provider Listings UI.
 * Eliminates inconsistent OS emojis and aligns with LocalServe High-Fi Figma styling.
 */

function SvgIcon({ size = 16, className = '', children, ...rest }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.85"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className={className}
      {...rest}
    >
      {children}
    </svg>
  )
}

export function IconLayers(props) {
  return (
    <SvgIcon {...props}>
      <polygon points="12 2 2 7 12 12 22 7 12 2" />
      <polyline points="2 17 12 22 22 17" />
      <polyline points="2 12 12 17 22 12" />
    </SvgIcon>
  )
}

export function IconCheckCircle(props) {
  return (
    <SvgIcon {...props}>
      <circle cx="12" cy="12" r="9.5" />
      <polyline points="8.5 12.5 11 15 16 9.5" />
    </SvgIcon>
  )
}

export function IconPauseCircle(props) {
  return (
    <SvgIcon {...props}>
      <circle cx="12" cy="12" r="9.5" />
      <line x1="10" y1="9" x2="10" y2="15" />
      <line x1="14" y1="9" x2="14" y2="15" />
    </SvgIcon>
  )
}

export function IconShieldAlert(props) {
  return (
    <SvgIcon {...props}>
      <path d="M12 2.5 4.5 5.8v5.7c0 4.8 3.2 8.9 7.5 10 4.3-1.1 7.5-5.2 7.5-10V5.8L12 2.5z" />
      <line x1="12" y1="8.5" x2="12" y2="12.5" />
      <circle cx="12" cy="16" r="0.7" fill="currentColor" />
    </SvgIcon>
  )
}

export function IconSearch(props) {
  return (
    <SvgIcon {...props}>
      <circle cx="11" cy="11" r="7.5" />
      <line x1="20.5" y1="20.5" x2="16.5" y2="16.5" />
    </SvgIcon>
  )
}

export function IconGrid(props) {
  return (
    <SvgIcon {...props}>
      <rect x="3" y="3" width="7.5" height="7.5" rx="1.5" />
      <rect x="13.5" y="3" width="7.5" height="7.5" rx="1.5" />
      <rect x="3" y="13.5" width="7.5" height="7.5" rx="1.5" />
      <rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.5" />
    </SvgIcon>
  )
}

export function IconList(props) {
  return (
    <SvgIcon {...props}>
      <line x1="8" y1="6" x2="20" y2="6" />
      <line x1="8" y1="12" x2="20" y2="12" />
      <line x1="8" y1="18" x2="20" y2="18" />
      <circle cx="4" cy="6" r="1" fill="currentColor" stroke="none" />
      <circle cx="4" cy="12" r="1" fill="currentColor" stroke="none" />
      <circle cx="4" cy="18" r="1" fill="currentColor" stroke="none" />
    </SvgIcon>
  )
}

export function IconMapPin(props) {
  return (
    <SvgIcon {...props}>
      <path d="M12 21s-6.5-5.7-6.5-11a6.5 6.5 0 1 1 13 0c0 5.3-6.5 11-6.5 11z" />
      <circle cx="12" cy="10" r="2.2" />
    </SvgIcon>
  )
}

export function IconGps(props) {
  return (
    <SvgIcon {...props}>
      <circle cx="12" cy="12" r="3" />
      <circle cx="12" cy="12" r="7.5" />
      <line x1="12" y1="2" x2="12" y2="4.5" />
      <line x1="12" y1="19.5" x2="12" y2="22" />
      <line x1="2" y1="12" x2="4.5" y2="12" />
      <line x1="19.5" y1="12" x2="22" y2="12" />
    </SvgIcon>
  )
}

export function IconHomePin(props) {
  return (
    <SvgIcon {...props}>
      <path d="M3 10.5 12 3l9 7.5" />
      <path d="M5.5 9.5V20h13V9.5" />
      <path d="M10 20v-5.5h4V20" />
    </SvgIcon>
  )
}

export function IconWorkshop(props) {
  return (
    <SvgIcon {...props}>
      <path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L4 17l3 3 5.3-5.3a4 4 0 0 0 5.4-5.4l-2.3 2.3-3-3 2.3-2.3z" />
    </SvgIcon>
  )
}

export function IconPencil(props) {
  return (
    <SvgIcon {...props}>
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
    </SvgIcon>
  )
}

export function IconPlay(props) {
  return (
    <SvgIcon {...props}>
      <polygon points="7 5 19 12 7 19 7 5" />
    </SvgIcon>
  )
}

export function IconArchive(props) {
  return (
    <SvgIcon {...props}>
      <rect x="3" y="4" width="18" height="4" rx="1" />
      <path d="M5 8v11a1.5 1.5 0 0 0 1.5 1.5h11A1.5 1.5 0 0 0 19 19V8" />
      <line x1="10" y1="12.5" x2="14" y2="12.5" />
    </SvgIcon>
  )
}

export function IconStar({ filled = false, ...props }) {
  return (
    <SvgIcon {...props}>
      <polygon
        points="12 2.6 14.9 8.5 21.4 9.4 16.7 14 17.8 20.5 12 17.4 6.2 20.5 7.3 14 2.6 9.4 9.1 8.5 12 2.6"
        fill={filled ? 'currentColor' : 'none'}
      />
    </SvgIcon>
  )
}

export function IconPlus(props) {
  return (
    <SvgIcon {...props}>
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </SvgIcon>
  )
}

export function IconRefresh(props) {
  return (
    <SvgIcon {...props}>
      <path d="M20 11a8 8 0 1 0 2 5.3" />
      <polyline points="20 4 20 11 13 11" />
    </SvgIcon>
  )
}

export function IconSparkles(props) {
  return (
    <SvgIcon {...props}>
      <path d="M13 2 4.5 13h6.5l-1 9L19.5 11H13l0-9z" />
    </SvgIcon>
  )
}

export function IconUploadCloud(props) {
  return (
    <SvgIcon {...props}>
      <path d="M20 16.5A4.5 4.5 0 0 0 17.5 8h-1.2A6.5 6.5 0 1 0 4 14.5" />
      <polyline points="16 16 12 12 8 16" />
      <line x1="12" y1="12" x2="12" y2="21" />
    </SvgIcon>
  )
}

export function IconClock(props) {
  return (
    <SvgIcon {...props}>
      <circle cx="12" cy="12" r="9" />
      <polyline points="12 7 12 12 15.5 14" />
    </SvgIcon>
  )
}

export function IconUsers(props) {
  return (
    <SvgIcon {...props}>
      <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="3.5" />
      <path d="M22 21v-2a4 4 0 0 0-3-3.85" />
      <path d="M16 3.2a3.5 3.5 0 0 1 0 6.6" />
    </SvgIcon>
  )
}

export function IconLock(props) {
  return (
    <SvgIcon {...props}>
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </SvgIcon>
  )
}

export function IconEye(props) {
  return (
    <SvgIcon {...props}>
      <path d="M2 12s3.8-7 10-7 10 7 10 7-3.8 7-10 7-10-7-10-7z" />
      <circle cx="12" cy="12" r="3" />
    </SvgIcon>
  )
}

export function IconInfo(props) {
  return (
    <SvgIcon {...props}>
      <circle cx="12" cy="12" r="9.5" />
      <line x1="12" y1="16" x2="12" y2="11.5" />
      <circle cx="12" cy="8" r="0.7" fill="currentColor" />
    </SvgIcon>
  )
}

export function IconAlertTriangle(props) {
  return (
    <SvgIcon {...props}>
      <path d="M10.3 3.8 2.2 18a2 2 0 0 0 1.7 3h16.2a2 2 0 0 0 1.7-3L13.7 3.8a2 2 0 0 0-3.4 0z" />
      <line x1="12" y1="9.5" x2="12" y2="13.5" />
      <circle cx="12" cy="17" r="0.7" fill="currentColor" />
    </SvgIcon>
  )
}

export function IconArrowRight(props) {
  return (
    <SvgIcon {...props}>
      <line x1="5" y1="12" x2="19" y2="12" />
      <polyline points="13 6 19 12 13 18" />
    </SvgIcon>
  )
}

export function IconArrowLeft(props) {
  return (
    <SvgIcon {...props}>
      <line x1="19" y1="12" x2="5" y2="12" />
      <polyline points="11 6 5 12 11 18" />
    </SvgIcon>
  )
}

export function IconX(props) {
  return (
    <SvgIcon {...props}>
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </SvgIcon>
  )
}

export function IconCheck(props) {
  return (
    <SvgIcon {...props}>
      <polyline points="20 6 9 17 4 12" />
    </SvgIcon>
  )
}
