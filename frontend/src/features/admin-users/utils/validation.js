import { LIMITS } from '../config/usersConfig'

/** Same rule as the backend: trimmed length 10..500 when required, at most 500 otherwise. */
export function noteError(value, { required }) {
  const length = (value ?? '').trim().length
  if (length > LIMITS.NOTE_MAX) return `Keep the note to ${LIMITS.NOTE_MAX} characters or fewer.`
  if (required && length < LIMITS.NOTE_MIN) {
    return length === 0 ? 'Enter a note.' : `The note must be at least ${LIMITS.NOTE_MIN} characters.`
  }
  return null
}
