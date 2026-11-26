/** WP02-only view helpers. No backend records are created or modified here. */
export const DOCUMENT_LABELS = Object.freeze({
  ID: 'Identity document',
  BUSINESS: 'Business registration',
  CERTIFICATE: 'Certificate / qualification',
})

export const VERIFICATION_STATUS_LABELS = Object.freeze({
  NOT_SUBMITTED: 'Not submitted',
  SUBMITTED: 'Submitted',
  UNDER_REVIEW: 'Under review',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  RESUBMISSION_REQUESTED: 'Resubmission requested',
  EXPIRED: 'Expired',
  SUSPENDED: 'Suspended',
})

export const EDITABLE_VERIFICATION_STATUSES = Object.freeze([
  'NOT_SUBMITTED', 'REJECTED', 'RESUBMISSION_REQUESTED', 'EXPIRED',
])

/** Use the same calendar day as the verification expiry job (Asia/Colombo). */
export function colomboDateString(date) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Colombo', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

/** The provider documents endpoint returns newest uploads first, but enforce that defensively. */
export function sortUploadedDocuments(documents) {
  if (!Array.isArray(documents)) return []
  return [...documents].filter(Boolean).sort((a, b) => {
    const aTime = Date.parse(a.uploadedAt || '') || 0
    const bTime = Date.parse(b.uploadedAt || '') || 0
    return bTime - aTime
  })
}

/**
 * Show the current ID and business document, and every uploaded certificate.
 * Certificates may represent several independent qualifications, so they must
 * not be collapsed into a single fictional 'latest certificate'.
 * The API does not yet identify the exact submitted snapshot or replacement links.
 */
export function displayDocuments(documents) {
  const seen = new Set()
  return sortUploadedDocuments(documents).filter((document) => {
    if (document.documentType === 'CERTIFICATE') return true
    if (!document.documentType || seen.has(document.documentType)) return false
    seen.add(document.documentType)
    return true
  })
}

export function newestDocumentOfType(documents, documentType) {
  return sortUploadedDocuments(documents).find((document) => document.documentType === documentType) || null
}

export function isDocumentExpired(document, asOfDate) {
  return Boolean(document?.expiryDate && asOfDate && document.expiryDate <= asOfDate)
}

export function isDocumentCurrent(document, asOfDate) {
  return Boolean(document && !isDocumentExpired(document, asOfDate))
}

export function requiredDocumentTypes(providerType) {
  return providerType === 'BUSINESS' ? ['ID', 'BUSINESS'] : ['ID']
}

/**
 * Use the exact timestamp rule from ProviderVerificationServiceImpl:
 * EXPIRED uses expiredAt (or updatedAt for older records), while rejected
 * and resubmission-requested cases use the previous review timestamp.
 */
export function verificationUploadCutoff(verification) {
  if (verification?.status === 'EXPIRED') {
    return verification.expiredAt ?? verification.updatedAt
  }
  return verification?.reviewedAt
}

/** Only the backend knows whether an older decision actually permits resubmission. */
export function documentsUploadedAfter(documents, cutoff) {
  if (!cutoff) return false
  const cutoffMs = Date.parse(cutoff)
  if (Number.isNaN(cutoffMs)) return false
  return Array.isArray(documents) && documents.some((doc) => {
    const time = Date.parse(doc?.uploadedAt || '')
    return !Number.isNaN(time) && time > cutoffMs
  })
}

export function formatDocumentDate(value) {
  if (!value) return 'Not recorded'
  const parsed = /^\d{4}-\d{2}-\d{2}$/.test(value)
    ? new Date(`${value}T12:00:00Z`)
    : new Date(value)
  return Number.isNaN(parsed.getTime())
    ? String(value)
    : parsed.toLocaleDateString('en-GB', {
      day: '2-digit', month: 'short', year: 'numeric',
      timeZone: /^\d{4}-\d{2}-\d{2}$/.test(value) ? 'UTC' : 'Asia/Colombo',
    })
}
