import { getTimeLeft } from './countdown'

/**
 * Why the responding party cannot respond right now, or null when they can.
 * Same rules as the backend's submitResponse: status AWAITING_RESPONSE, before responseDueAt, no response yet.
 */
export function responseClosedReason(dispute, now = Date.now()) {
  if (dispute?.response) {
    return 'You have already responded. An admin is reviewing both sides.'
  }
  if (dispute?.status !== 'AWAITING_RESPONSE') {
    return 'Response window closed. This dispute is no longer waiting for a response.'
  }
  if (getTimeLeft(dispute.responseDueAt, now)?.overdue !== false) {
    return 'Response window closed. The 72-hour deadline has passed.'
  }
  return null
}
