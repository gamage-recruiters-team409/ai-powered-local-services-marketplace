import { INTENT, LIMITS } from '../config/announcementConfig'

/**
 * Same rule as the backend NoHtmlTags validator: an HTML tag (a letter right after "<" or "</"), a comment, a
 * doctype/declaration or a processing instruction. Markdown autolinks (<https://...>) and "a < b" are allowed.
 */
const HTML = /<\/?[A-Za-z][A-Za-z0-9-]*(?:[\s/][^<>]*)?>|<!--|<![A-Za-z]|<\?/

export function containsHtml(value) {
  return typeof value === 'string' && HTML.test(value)
}

/**
 * Client checks that mirror the backend, so most mistakes are shown before sending.
 * The schedule must not be in the past when publishing (drafts may keep any date).
 *
 * @returns {Record<string, string>} field -> message; empty when valid
 */
export function validateAnnouncement(form, { intent, lockedSchedule = false, now = new Date() } = {}) {
  const errors = {}
  const title = (form.title ?? '').trim()
  const message = form.message ?? ''

  if (!title) errors.title = 'Enter a title.'
  else if (title.length > LIMITS.TITLE) errors.title = `Title must be at most ${LIMITS.TITLE} characters.`
  else if (containsHtml(title)) errors.title = 'Title must not contain HTML tags.'

  if (!message.trim()) errors.message = 'Enter a message.'
  else if (message.length > LIMITS.MESSAGE) errors.message = `Message must be at most ${LIMITS.MESSAGE} characters.`
  else if (containsHtml(message)) errors.message = 'Message is markdown and must not contain HTML tags.'

  if (!form.audience) errors.audience = 'Choose who should receive it.'

  if (!lockedSchedule && intent === INTENT.PUBLISH && form.scheduledLocal) {
    const scheduled = new Date(form.scheduledLocal)
    if (Number.isNaN(scheduled.getTime())) errors.scheduledAt = 'Enter a valid date and time.'
    else if (scheduled.getTime() < now.getTime()) errors.scheduledAt = 'The scheduled time is in the past.'
  }
  return errors
}
