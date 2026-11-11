/** Small inline icons used by the completion UI (stroke icons, 24x24 viewBox). */
const PATHS = {
  play: [<circle key="c" cx="12" cy="12" r="9" />, <path key="p" d="M10 8.5v7l5.5-3.5z" />],
  checkCircle: [<circle key="c" cx="12" cy="12" r="9" />, <path key="p" d="m8.5 12.5 2.5 2.5 4.5-5" />],
  check: [<path key="p" d="m5 12.5 4.5 4.5L19 7.5" />],
  info: [<circle key="c" cx="12" cy="12" r="9" />, <path key="l" d="M12 11v5" />, <path key="d" d="M12 8h.01" />],
  clock: [<circle key="c" cx="12" cy="12" r="9" />, <path key="p" d="M12 7v5l3 2" />],
  alert: [<path key="t" d="M10.3 4.2 2.6 17.5A2 2 0 0 0 4.3 20.5h15.4a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0z" />, <path key="l" d="M12 9.5v4" />, <path key="d" d="M12 17h.01" />],
  flag: [<path key="p" d="M5 21V4m0 0h11l-2 4 2 4H5" />],
  image: [<rect key="r" x="3.5" y="3.5" width="17" height="17" rx="2.5" />, <circle key="c" cx="9" cy="9" r="1.8" />, <path key="p" d="m20.5 15.5-5-5L5 21" />],
  upload: [<path key="a" d="M12 15V4m0 0L7.5 8.5M12 4l4.5 4.5" />, <path key="b" d="M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15" />],
  close: [<path key="a" d="M18 6 6 18" />, <path key="b" d="m6 6 12 12" />],
  user: [<circle key="c" cx="12" cy="8" r="3.5" />, <path key="p" d="M5 20a7 7 0 0 1 14 0" />],
  wallet: [<rect key="r" x="3.5" y="5.5" width="17" height="14" rx="2" />, <path key="p" d="M3.5 9.5h17" />],
  chevronRight: [<path key="p" d="m9 6 6 6-6 6" />],
}

export default function CompletionIcon({ name, size = 20, className = '' }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.9"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      className={className}
    >
      {PATHS[name]}
    </svg>
  )
}
