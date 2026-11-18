/** Control characters and backslashes are parsed differently by URL parsers and browsers: reject them. */
function hasUnsafeCharacter(value) {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index)
    if (code <= 0x1f || code === 0x7f || code === 0x5c) {
      return true
    }
  }
  return false
}

/**
 * Returns a URL that may be opened for an evidence reference, or null.
 * Only absolute https URLs, or URLs on this site's own origin, are allowed. Everything else
 * (http, javascript:, data:, protocol-relative tricks, plain storage keys) is shown as text only.
 */
export function safeEvidenceHref(reference, currentOrigin = globalThis.location?.origin) {
  if (typeof reference !== 'string') {
    return null
  }
  const value = reference.trim()
  if (value === '' || value !== reference || hasUnsafeCharacter(value)) {
    return null
  }
  let url
  try {
    url = new URL(value, currentOrigin || undefined)
  } catch {
    return null
  }
  const isAbsolute = /^[a-z][a-z\d+\-.]*:/i.test(value)
  if (url.protocol === 'https:' && isAbsolute) {
    return url.href
  }
  // Same-origin only for root-relative paths ("/files/a.png"); bare keys ("evidence/a.png") are not URLs.
  if (!isAbsolute && value.startsWith('/') && !value.startsWith('//') && currentOrigin && url.origin === currentOrigin) {
    return url.href
  }
  return null
}
