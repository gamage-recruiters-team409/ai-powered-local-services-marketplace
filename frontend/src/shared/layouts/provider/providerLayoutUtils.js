export function cx(...classNames) {
  return classNames.filter(Boolean).join(' ')
}

export function getInitials(name) {
  const parts = String(name ?? '')
    .trim()
    .split(/\s+/)
    .filter(Boolean)

  if (parts.length === 0) {
    return 'P'
  }

  const first = parts[0][0]
  const last = parts.length > 1 ? parts[parts.length - 1][0] : ''

  return `${first}${last}`.toUpperCase()
}