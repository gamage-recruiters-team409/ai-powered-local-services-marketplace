import { ACTION_LABELS } from '../config/disputeStatus'

/** "Full refund to the customer", "Partial refund to the customer (40%)", ... */
export function outcomeText(outcome) {
  if (!outcome) {
    return ''
  }
  const base = ACTION_LABELS[outcome.action] ?? 'Decision recorded'
  return outcome.action === 'REFUND_PARTIAL' ? `${base} (${outcome.customerRefundPercent}%)` : base
}

