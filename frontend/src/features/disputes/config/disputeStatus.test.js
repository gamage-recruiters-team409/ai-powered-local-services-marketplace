import { describe, expect, it } from 'vitest'

import { categoryLabel, getStatusBadge, responderRoleOf, roleLabel } from './disputeStatus'

describe('getStatusBadge', () => {
  it('words AWAITING_RESPONSE for the responder and for the other party', () => {
    expect(getStatusBadge('AWAITING_RESPONSE', { viewerIsResponder: true }))
      .toEqual({ label: 'Awaiting your response', tone: 'warning', stage: 1 })
    expect(getStatusBadge('AWAITING_RESPONSE', { viewerIsResponder: false, responderRole: 'PROVIDER' }).label)
      .toBe("Awaiting provider's response")
    expect(getStatusBadge('AWAITING_RESPONSE', { viewerIsResponder: false, responderRole: 'CUSTOMER' }).label)
      .toBe("Awaiting customer's response")
  })

  it.each([
    ['OPEN', 'Open', 'warning', 1],
    ['UNDER_REVIEW', 'Under review', 'info', 2],
    ['RESOLVED', 'Resolved', 'success', 3],
    ['RESOLVED_FINAL', 'Resolved (final)', 'success', 3],
    ['CLOSED', 'Closed', 'neutral', 3],
  ])('%s -> %s, %s, stage %s', (status, label, tone, stage) => {
    expect(getStatusBadge(status)).toEqual({ label, tone, stage })
  })

  it('falls back to a neutral badge for an unknown status', () => {
    expect(getStatusBadge('SOMETHING_NEW')).toEqual({ label: 'Unknown status', tone: 'neutral', stage: null })
    expect(getStatusBadge(undefined).tone).toBe('neutral')
  })
})

describe('responderRoleOf', () => {
  it('is the party who did not raise the dispute', () => {
    expect(responderRoleOf({ raisedByRole: 'CUSTOMER' })).toBe('PROVIDER')
    expect(responderRoleOf({ raisedByRole: 'PROVIDER' })).toBe('CUSTOMER')
  })
})

describe('labels', () => {
  it('maps categories and roles, with safe fallbacks', () => {
    expect(categoryLabel('POOR_QUALITY')).toBe('Poor quality')
    expect(categoryLabel('NO_SHOW')).toBe('No-show')
    expect(categoryLabel('UNKNOWN_VALUE')).toBe('Other')
    expect(roleLabel('CUSTOMER')).toBe('Customer')
    expect(roleLabel('PROVIDER')).toBe('Provider')
    expect(roleLabel('ADMIN')).toBe('Admin')
    expect(roleLabel(null)).toBe('System')
  })
})
