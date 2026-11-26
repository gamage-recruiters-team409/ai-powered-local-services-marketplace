/** Local WP02 detail icons. The common Provider sidebar icon library stays unchanged. */
export default function ProviderFeatureIcon({ name, size = 22, className = '' }) {
  const content = {
    document: <><path d="M7 3h7l5 5v12a1 1 0 0 1-1 1H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z" /><path d="M14 3v6h5M9 13h6M9 17h6" /></>,
    bank: <><path d="M3 9 12 4l9 5M4 9h16M5 20h14M3 22h18M7 10v9M12 10v9M17 10v9" /></>,
    pin: <><path d="M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0z" /><circle cx="12" cy="10" r="2.5" /></>,
    upload: <><path d="M12 16V3m0 0-4 4m4-4 4 4" /><path d="M4 15v5a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5" /></>,
    camera: <><path d="M4 7h4l2-3h4l2 3h4a1 1 0 0 1 1 1v11H3V8a1 1 0 0 1 1-1z" /><circle cx="12" cy="13" r="4" /></>,
    refresh: <><path d="M20 7v5h-5M4 17v-5h5" /><path d="M6.3 9a7 7 0 0 1 12.6-2L20 12M4 12l1.1 5a7 7 0 0 0 12.6-2" /></>,
    alert: <><path d="M10.3 4.8 2.4 19a2 2 0 0 0 1.8 3h15.6a2 2 0 0 0 1.8-3L13.7 4.8a2 2 0 0 0-3.4 0z" /><path d="M12 9v5m0 4h.01" /></>,
    check: <path d="m5 12 4 4L19 6" />,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    close: <path d="M5 5 19 19M19 5 5 19" />,
  }[name]
  if (!content) return null
  return <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">{content}</svg>
}
