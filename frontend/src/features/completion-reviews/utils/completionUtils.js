/**
 * WP08 – helpers for the Service Completion UI.
 * All times are shown in Sri Lanka time (Asia/Colombo).
 */

export const TIME_ZONE = 'Asia/Colombo'
export const AUTO_CONFIRM_HOURS = 48
export const PAYOUT_RELEASE_HOURS = 72
export const NOTE_MAX_LENGTH = 500
export const MAX_PHOTOS = 5
export const MAX_PHOTO_BYTES = 5 * 1024 * 1024
const ALLOWED_PHOTO_TYPES = ['image/jpeg', 'image/png']
const ALLOWED_PHOTO_EXTENSIONS = ['jpg', 'jpeg', 'png']

const HOUR_MS = 60 * 60 * 1000

/** Booking statuses handled by the completion section, with their badge label and colour. */
export const COMPLETION_STATUS = {
  CONFIRMED: { label: 'Confirmed', tone: 'teal' },
  IN_PROGRESS: { label: 'In Progress', tone: 'indigo' },
  COMPLETION_PENDING: { label: 'Awaiting Confirmation', tone: 'amber' },
  COMPLETED: { label: 'Completed', tone: 'green' },
  DISPUTED: { label: 'Disputed', tone: 'red' },
  REFUNDED: { label: 'Refunded', tone: 'red' },
  PARTIALLY_REFUNDED: { label: 'Partially Refunded', tone: 'amber' },
}

/** Booking statuses that can follow a resolved dispute (admin decision, WP09). */
export const DISPUTE_OUTCOME_STATUSES = ['COMPLETED', 'REFUNDED', 'PARTIALLY_REFUNDED']

export const STEPS = ['Confirmed', 'Started', 'Marked done', 'Completed']

/**
 * Stepper state for a booking status.
 * Returns { current, done, error }: `done` = number of finished steps, `current` = active step index.
 */
export function stepState(status) {
  switch (status) {
    case 'CONFIRMED':
      return { done: 0, current: 0, error: null }
    case 'IN_PROGRESS':
      return { done: 1, current: 1, error: null }
    case 'COMPLETION_PENDING':
      return { done: 2, current: 2, error: null }
    case 'DISPUTED':
    case 'REFUNDED':
    case 'PARTIALLY_REFUNDED':
      return { done: 2, current: 2, error: 2 }
    case 'COMPLETED':
      return { done: 4, current: 3, error: null }
    default:
      return { done: 0, current: -1, error: null }
  }
}

const toDate = (value) => (value instanceof Date ? value : new Date(value))

function parts(value, options) {
  return new Intl.DateTimeFormat('en-GB', { timeZone: TIME_ZONE, ...options }).format(toDate(value))
}

/** "10:05" */
export function formatTime(value) {
  return value ? parts(value, { hour: '2-digit', minute: '2-digit', hour12: false }) : ''
}

/** "Tue, 20 Oct 2026" */
export function formatDate(value) {
  return value ? parts(value, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }) : ''
}

/** "Tue, 20 Oct 2026 · 10:05" */
export function formatDateTime(value) {
  return value ? `${formatDate(value)} · ${formatTime(value)}` : ''
}

/** "23 Oct 2026" */
export function formatShortDate(value) {
  return value ? parts(value, { day: 'numeric', month: 'short', year: 'numeric' }) : ''
}

function sameDay(a, b) {
  return parts(a, { year: 'numeric', month: '2-digit', day: '2-digit' })
    === parts(b, { year: 'numeric', month: '2-digit', day: '2-digit' })
}

/** "today, 10:00 – 12:00" or "Thu, 23 Oct 2026, 10:00 – 12:00" */
export function formatSlot(slotStart, slotEnd, now = new Date()) {
  if (!slotStart) {
    return ''
  }
  const day = sameDay(slotStart, now) ? 'today' : formatDate(slotStart)
  const end = slotEnd ? ` – ${formatTime(slotEnd)}` : ''
  return `${day}, ${formatTime(slotStart)}${end}`
}

/** "31h 20m" / "45m"; never negative. */
export function formatDuration(ms) {
  const totalMinutes = Math.max(0, Math.floor(ms / 60000))
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  return hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`
}

/** Time left until the 48-hour auto-confirm, in ms (0 when it has passed). */
export function autoConfirmRemainingMs(markedCompletedAt, now = new Date()) {
  if (!markedCompletedAt) {
    return 0
  }
  const deadline = toDate(markedCompletedAt).getTime() + AUTO_CONFIRM_HOURS * HOUR_MS
  return Math.max(0, deadline - toDate(now).getTime())
}

/** Whole hours since the booking slot ended (0 when not ended yet). */
export function hoursSinceSlotEnded(slotEnd, now = new Date()) {
  if (!slotEnd) {
    return 0
  }
  const diff = toDate(now).getTime() - toDate(slotEnd).getTime()
  return diff > 0 ? Math.floor(diff / HOUR_MS) : 0
}

export function payoutReleaseDate(confirmedAt) {
  return confirmedAt ? new Date(toDate(confirmedAt).getTime() + PAYOUT_RELEASE_HOURS * HOUR_MS) : null
}

/** Short display name: "Nimal Perera" -> "Nimal P." */
export function shortName(fullName) {
  if (!fullName) {
    return 'the customer'
  }
  const [first, ...rest] = fullName.trim().split(/\s+/)
  return rest.length ? `${first} ${rest[rest.length - 1][0]}.` : first
}

/** Returns an error message for a photo file, or null when it is accepted. */
export function validatePhoto(file) {
  const extension = file.name.includes('.') ? file.name.split('.').pop().toLowerCase() : ''
  const typeOk = ALLOWED_PHOTO_TYPES.includes(file.type) || (!file.type && ALLOWED_PHOTO_EXTENSIONS.includes(extension))
  if (!typeOk || !ALLOWED_PHOTO_EXTENSIONS.includes(extension)) {
    return `${file.name} – this file type is not supported`
  }
  if (file.size > MAX_PHOTO_BYTES) {
    return `${file.name} – this file is larger than 5 MB`
  }
  return null
}

let nextPhotoId = 0

function createPreviewUrl(file) {
  try {
    return typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : null
  } catch {
    return null
  }
}

/**
 * Builds photo-picker items for newly selected files. Invalid files are kept with an
 * error message so the user can see why they were not accepted.
 */
export function toPhotoItems(files, currentValidCount) {
  let validCount = currentValidCount
  return Array.from(files).map((file) => {
    nextPhotoId += 1
    let error = validatePhoto(file)
    if (!error && validCount >= MAX_PHOTOS) {
      error = `${file.name} – you can add up to ${MAX_PHOTOS} photos`
    }
    if (!error) {
      validCount += 1
    }
    return { id: `photo-${nextPhotoId}`, file, error, previewUrl: error ? null : createPreviewUrl(file) }
  })
}
