/** Booking statuses where the Service Completion card is shown (from CONFIRMED onwards, incl. dispute outcomes). */
export const COMPLETION_CARD_STATUSES = Object.freeze([
  'CONFIRMED',
  'IN_PROGRESS',
  'COMPLETION_PENDING',
  'COMPLETED',
  'DISPUTED',
  'REFUNDED',
  'PARTIALLY_REFUNDED',
])
