/**
 * Decides whether the Bearer token may be attached to a request.
 *
 * The request URL is resolved the same way axios does (baseURL + url, unless url is
 * absolute or protocol-relative), and the token is allowed only when the resolved
 * origin equals the configured API origin. Anything ambiguous fails closed (no token).
 *
 * Pure function with no imports, so it runs under `node --test` and in the browser.
 */

// axios treats "<scheme>://..." and "//..." as absolute (lib/helpers/isAbsoluteURL.js).
const AXIOS_ABSOLUTE_URL = /^([a-z][a-z\d+\-.]*:)?\/\//i

// Backslashes, control characters and surrounding whitespace are normalised differently by
// URL parsers and browsers (e.g. "/\evil.com" or " //evil.com"), so reject them outright.
function hasUnsafeCharacter(value) {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index)
    if (code <= 0x1f || code === 0x7f || code === 0x5c /* backslash */) {
      return true
    }
  }
  return false
}

/** Parses an http(s) URL; returns null instead of throwing for anything invalid. */
function parseHttpUrl(value, base) {
  try {
    const url = new URL(value, base)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url : null
  } catch {
    return null
  }
}

/** Same joining rule as axios combineURLs: trim trailing "/" on base, leading "/" on path. */
function combineUrls(baseUrl, path) {
  return `${baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
}

/**
 * @param {string|undefined} requestUrl     config.url of the request
 * @param {string|undefined} requestBaseUrl config.baseURL of the request
 * @param {string|undefined} apiBaseUrl     the trusted API base (VITE_API_BASE_URL); defaults to requestBaseUrl
 * @returns {boolean} true only if the request is going to the API origin
 */
export function shouldAttachToken(requestUrl, requestBaseUrl, apiBaseUrl = requestBaseUrl) {
  const api = parseHttpUrl(apiBaseUrl)
  if (!api || typeof requestUrl !== 'string' || requestUrl === '') {
    return false
  }
  if (requestUrl !== requestUrl.trim() || hasUnsafeCharacter(requestUrl)) {
    return false
  }

  let target
  if (AXIOS_ABSOLUTE_URL.test(requestUrl)) {
    // Protocol-relative URLs take the scheme of the base, as the browser would.
    target = parseHttpUrl(requestUrl, api)
  } else {
    if (typeof requestBaseUrl !== 'string' || requestBaseUrl === '') {
      return false
    }
    target = parseHttpUrl(combineUrls(requestBaseUrl, requestUrl))
  }

  return target !== null && target.origin === api.origin
}
