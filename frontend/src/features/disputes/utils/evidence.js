/**
 * Evidence upload rules, the same as the backend (which checks the real file content again):
 * JPG, PNG, WEBP or PDF, at most 5 MB per file, at most 5 files per party per dispute.
 */

export const EVIDENCE_RULES = Object.freeze({
  MAX_BYTES: 5 * 1024 * 1024,
  MAX_FILES: 5,
  ACCEPT: '.jpg,.jpeg,.png,.webp,.pdf,image/jpeg,image/png,image/webp,application/pdf',
})

const ALLOWED_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'application/pdf'])
const ALLOWED_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'webp', 'pdf'])

/** Statuses in which the parties may still add evidence. */
export const EVIDENCE_OPEN_STATUSES = new Set(['OPEN', 'AWAITING_RESPONSE', 'UNDER_REVIEW'])

function extensionOf(name) {
  const match = /\.([A-Za-z0-9]+)$/.exec(String(name ?? ''))
  return match ? match[1].toLowerCase() : ''
}

/** Client-side check before uploading: an error message, or null when the file may be sent. */
export function validateEvidenceFile(file) {
  if (!file) {
    return 'Choose a file to upload.'
  }
  const typeOk = file.type ? ALLOWED_TYPES.has(file.type) : ALLOWED_EXTENSIONS.has(extensionOf(file.name))
  if (!typeOk) {
    return 'Only JPG, PNG, WEBP or PDF files can be uploaded.'
  }
  if (file.size > EVIDENCE_RULES.MAX_BYTES) {
    return 'Each file must be 5 MB or smaller.'
  }
  if (file.size === 0) {
    return 'This file is empty.'
  }
  return null
}

export function formatBytes(bytes) {
  const value = Number(bytes)
  if (!Number.isFinite(value) || value < 0) {
    return ''
  }
  if (value < 1024) {
    return `${value} B`
  }
  if (value < 1024 * 1024) {
    return `${Math.round(value / 1024)} KB`
  }
  return `${(value / (1024 * 1024)).toFixed(1)} MB`
}

/** Files of one side (CUSTOMER / PROVIDER) already on the dispute. */
export function evidenceCountFor(dispute, role) {
  const files = Array.isArray(dispute?.evidenceFiles) ? dispute.evidenceFiles : []
  return files.filter((file) => file.uploadedByRole === role).length
}

let counter = 0

/** A picker entry for a chosen file: validated at once, uploaded later. */
export function toPickedFile(file) {
  counter += 1
  return { key: `f${counter}-${file.name}`, file, error: validateEvidenceFile(file), progress: 0, status: 'ready' }
}
