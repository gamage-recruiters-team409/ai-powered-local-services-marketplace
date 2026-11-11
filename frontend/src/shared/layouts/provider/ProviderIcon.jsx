const HOME = (
  <>
    <path d="M3 10.5 12 3l9 7.5" />
    <path d="M5 9.5V21h5v-6h4v6h5V9.5" />
  </>
)

const DASHBOARD = (
  <>
    <rect x="3" y="3" width="7" height="7" rx="1.5" />
    <rect x="14" y="3" width="7" height="7" rx="1.5" />
    <rect x="3" y="14" width="7" height="7" rx="1.5" />
    <rect x="14" y="14" width="7" height="7" rx="1.5" />
  </>
)

const USER = (
  <>
    <circle cx="12" cy="8" r="4" />
    <path d="M5 21v-1a7 7 0 0 1 14 0v1" />
  </>
)

const SHIELD = (
  <>
    <path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z" />
    <path d="m9 12 2 2 4-4" />
  </>
)

const LIST = (
  <>
    <path d="M9 6h12M9 12h12M9 18h12" />
    <circle cx="4" cy="6" r="1" />
    <circle cx="4" cy="12" r="1" />
    <circle cx="4" cy="18" r="1" />
  </>
)

const CALENDAR = (
  <>
    <rect x="3" y="5" width="18" height="16" rx="2" />
    <path d="M3 10h18M8 3v4M16 3v4" />
  </>
)

const AVAILABILITY = (
  <>
    <rect x="3" y="5" width="18" height="16" rx="2" />
    <path d="M3 10h18M8 3v4M16 3v4" />
    <circle cx="12" cy="15" r="2.5" />
    <path d="M12 13.5V15l1 1" />
  </>
)

const MESSAGE = (
  <>
    <path d="M21 12a8 8 0 0 1-11.8 7L4 20l1-4.6A8 8 0 1 1 21 12z" />
    <path d="M8 11h8M8 14h5" />
  </>
)

const CARD = (
  <>
    <rect x="3" y="5" width="18" height="14" rx="2" />
    <path d="M3 10h18M15 15h3" />
  </>
)

const REVIEWS = (
  <>
    <circle cx="9" cy="8" r="3" />
    <path d="M3 20v-1a6 6 0 0 1 6-6" />
    <path d="m17 13 1.2 2.4 2.8.4-2 2 .5 2.8-2.5-1.3-2.5 1.3.5-2.8-2-2 2.8-.4z" />
  </>
)

const ACCOUNT = (
  <>
    <circle cx="12" cy="8" r="4" />
    <path d="M4 21a8 8 0 0 1 16 0" />
  </>
)

const ICONS = {
  home: HOME,

  dashboard: DASHBOARD,
  grid: DASHBOARD,

  user: USER,
  profile: USER,
  account: ACCOUNT,

  shield: SHIELD,
  verification: SHIELD,

  list: LIST,
  listings: LIST,

  calendar: CALENDAR,
  bookings: CALENDAR,

  availability: AVAILABILITY,

  message: MESSAGE,
  messages: MESSAGE,

  creditCard: CARD,
  wallet: CARD,
  earnings: CARD,

  reviews: REVIEWS,

  bell: (
    <>
      <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.9 1.9 0 0 0 3.4 0" />
    </>
  ),

  menu: <path d="M4 6h16M4 12h16M4 18h16" />,

  close: <path d="M18 6 6 18M6 6l12 12" />,

  chevronDown: <path d="m6 9 6 6 6-6" />,

  chevronRight: <path d="m9 18 6-6-6-6" />,

  back: <path d="m15 18-6-6 6-6" />,

  logout: (
    <>
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <path d="m16 17 5-5-5-5M21 12H9" />
    </>
  ),
}

export function ProviderIcon({
  name,
  size = 18,
  className,
  strokeWidth = 1.8,
}) {
  const shape = ICONS[name]

  if (!shape) {
    return null
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
      {shape}
    </svg>
  )
}