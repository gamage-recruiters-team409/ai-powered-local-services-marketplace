export function cx(...classNames) {
  return classNames.filter(Boolean).join(' ')
}

function nameParts(name) {
  return String(name ?? '')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
}

export function getInitials(name) {
  const parts = nameParts(name)

  if (parts.length === 0) {
    return 'C'
  }

  const first = parts[0][0]
  const last = parts.length > 1 ? parts[parts.length - 1][0] : ''

  return `${first}${last}`.toUpperCase()
}

/** "Nimal Perera" → "Nimal P." (header profile button). */
export function getShortName(name) {
  const parts = nameParts(name)

  if (parts.length === 0) {
    return 'Customer'
  }

  if (parts.length === 1) {
    return parts[0]
  }

  return `${parts[0]} ${parts[parts.length - 1][0].toUpperCase()}.`
}

export function normalizeCount(value) {
  const number = Number(value)

  if (!Number.isFinite(number) || number <= 0) {
    return 0
  }

  return Math.floor(number)
}

/** Danger items (e.g. Deactivate Account) are listed after Log out. */
export function splitFooterItems(items = []) {
  return {
    regular: items.filter((item) => item.tone !== 'danger'),
    danger: items.filter((item) => item.tone === 'danger'),
  }
}
