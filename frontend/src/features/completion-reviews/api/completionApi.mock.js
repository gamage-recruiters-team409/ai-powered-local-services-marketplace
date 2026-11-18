/**
 * WP08 – Mock Service Completion API.
 * Same function names and response shape as completionApi.js, with in-memory data and a short delay.
 * Used until the booking (WP05) and completion backend endpoints are merged.
 */

const DELAY_MS = 600
const store = new Map()
const slots = new Map() // bookingId -> slotStart (for the no-show rule)
const NO_SHOW_GRACE_MS = 120 * 60 * 1000

const wait = (value, ms = DELAY_MS) => new Promise((resolve) => setTimeout(() => resolve(value), ms))

function apiError(status, error, message) {
  const err = new Error(message)
  err.response = { status, data: { status, error, message } }
  return err
}

function toResponse(record) {
  return { ...record, photoUrls: [...(record.photoUrls ?? [])] }
}

/** Seeds or replaces the mock completion for a booking (used by the preview page and tests). */
export function seedCompletion(bookingId, completion) {
  if (completion) {
    store.set(bookingId, { id: `comp-${bookingId}`, bookingId, photoUrls: [], ...completion })
  } else {
    store.delete(bookingId)
  }
}

/** Stores the booking start time so the mock can apply the 120-minute no-show rule. */
export function seedBookingSlot(bookingId, slotStart) {
  if (slotStart) {
    slots.set(bookingId, slotStart)
  } else {
    slots.delete(bookingId)
  }
}

export function resetMockCompletions() {
  store.clear()
  slots.clear()
}

export async function getCompletion(bookingId) {
  const record = store.get(bookingId)
  if (!record) {
    throw apiError(404, 'COMPLETION_NOT_FOUND', 'The service has not been started for this booking')
  }
  return wait(toResponse(record), 150)
}

export async function startService(bookingId) {
  if (store.has(bookingId)) {
    await wait(null)
    throw apiError(409, 'SERVICE_ALREADY_STARTED', 'The service has already been started for this booking')
  }
  const now = new Date().toISOString()
  store.set(bookingId, {
    id: `comp-${bookingId}`,
    bookingId,
    startedAt: now,
    completionNote: null,
    photoUrls: [],
    markedCompletedAt: null,
    confirmedAt: null,
    confirmationType: null,
    issueRaisedAt: null,
    disputeId: null,
  })
  return wait(toResponse(store.get(bookingId)))
}

export async function markCompleted(bookingId, { completionNote, photoUrls }) {
  const record = store.get(bookingId)
  if (!record) {
    await wait(null)
    throw apiError(404, 'COMPLETION_NOT_FOUND', 'The service has not been started for this booking')
  }
  if (record.markedCompletedAt) {
    await wait(null)
    throw apiError(409, 'SERVICE_ALREADY_MARKED_COMPLETED', 'This service has already been marked as completed')
  }
  record.completionNote = completionNote?.trim() || null
  record.photoUrls = photoUrls ?? []
  record.markedCompletedAt = new Date().toISOString()
  return wait(toResponse(record))
}

export async function confirmCompletion(bookingId) {
  const record = store.get(bookingId)
  if (!record?.markedCompletedAt) {
    await wait(null)
    throw apiError(409, 'INVALID_STATUS_TRANSITION', 'Completion can only be confirmed while it is pending')
  }
  record.confirmedAt = new Date().toISOString()
  record.confirmationType = 'CUSTOMER'
  return wait(toResponse(record))
}

/**
 * Mock upload: returns a temporary browser link to the selected photo (blob:),
 * so the real image is shown in the preview. It only lasts until the page is reloaded.
 * Real uploads need the media endpoint.
 */
export async function uploadCompletionPhoto(file) {
  let url = `mock://completion-photo/${encodeURIComponent(file.name)}`
  try {
    if (typeof URL.createObjectURL === 'function') {
      url = URL.createObjectURL(file)
    }
  } catch {
    // keep the placeholder URL
  }
  return wait(url, 300)
}

export async function raiseIssue(bookingId, { reason, evidenceUrls = [] }) {
  const record = store.get(bookingId)
  if (!record?.markedCompletedAt || record.confirmedAt) {
    await wait(null)
    throw apiError(409, 'INVALID_STATUS_TRANSITION', 'An issue can only be raised while completion is pending')
  }
  if (record.issueRaisedAt) {
    await wait(null)
    throw apiError(409, 'COMPLETION_ISSUE_RAISED', 'An issue has already been raised for this booking')
  }
  const text = reason?.trim() ?? ''
  if (text.length < 10 || text.length > 500) {
    await wait(null)
    throw apiError(400, 'VALIDATION_ERROR', 'Reason must be between 10 and 500 characters')
  }
  record.issueRaisedAt = new Date().toISOString()
  record.disputeId = `DSP-${1000 + Math.floor(Math.random() * 9000)}`
  record.issueReason = text
  record.evidenceUrls = evidenceUrls
  return wait(toResponse(record))
}

export async function reportNoShow(bookingId, { note } = {}) {
  const slotStart = slots.get(bookingId)
  if (!slotStart || Date.now() < new Date(slotStart).getTime() + NO_SHOW_GRACE_MS) {
    await wait(null)
    throw apiError(409, 'NO_SHOW_TOO_EARLY', 'A no-show can be reported 120 minutes after the booking start time')
  }
  if (note && note.length > 500) {
    await wait(null)
    throw apiError(400, 'VALIDATION_ERROR', 'Note must not exceed 500 characters')
  }
  return wait({ bookingId, type: 'NO_SHOW', bookingStatus: 'DISPUTED', reportedAt: new Date().toISOString() })
}

/** The mock accepts evidence photos so the full S05 / S06 design can be previewed. */
export const supportsEvidence = true

export async function uploadEvidence(file) {
  return uploadCompletionPhoto(file)
}
