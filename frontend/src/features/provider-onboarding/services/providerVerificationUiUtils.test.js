import { describe, expect, it } from 'vitest'
import {
  colomboDateString,
  displayDocuments,
  documentsUploadedAfter,
  isDocumentExpired,
  newestDocumentOfType,
  requiredDocumentTypes,
  verificationUploadCutoff,
} from './providerVerificationUiUtils'

describe('WP02 verification UI data contracts', () => {
  it('does not fabricate missing certificates or business documents', () => {
    const docs = [{ id: 'id-1', documentType: 'ID', uploadedAt: '2026-11-12T10:00:00Z' }]
    expect(displayDocuments(docs)).toHaveLength(1)
    expect(displayDocuments(docs)[0].documentType).toBe('ID')
  })

  it('selects the latest identity document but retains multiple certificates', () => {
    const docs = [
      { id: 'a', documentType: 'ID', uploadedAt: '2026-01-01T00:00:00Z' },
      { id: 'b', documentType: 'ID', uploadedAt: '2026-11-01T00:00:00Z' },
      { id: 'c', documentType: 'CERTIFICATE', uploadedAt: '2026-11-01T00:00:00Z' },
      { id: 'd', documentType: 'CERTIFICATE', uploadedAt: '2026-10-01T00:00:00Z' },
    ]
    expect(newestDocumentOfType(docs, 'ID').id).toBe('b')
    expect(displayDocuments(docs).map((doc) => doc.id)).toEqual(['b', 'c', 'd'])
  })

  it('requires a business registration document only for business providers', () => {
    expect(requiredDocumentTypes('INDIVIDUAL')).toEqual(['ID'])
    expect(requiredDocumentTypes('BUSINESS')).toEqual(['ID', 'BUSINESS'])
  })

  it('uses local Sri Lankan date and treats expiry day as expired', () => {
    expect(colomboDateString(new Date('2026-11-24T19:30:00Z'))).toBe('2026-11-25')
    expect(isDocumentExpired({ expiryDate: '2026-11-25' }, '2026-11-25')).toBe(true)
    expect(isDocumentExpired({ expiryDate: '2026-11-26' }, '2026-11-25')).toBe(false)
    expect(isDocumentExpired({ expiryDate: null }, '2026-11-25')).toBe(false)
  })

  it('uses expiredAt for expiry resubmission and updatedAt only for legacy records', () => {
    const verification = {
      status: 'EXPIRED',
      expiredAt: '2026-11-25T09:00:00Z',
      updatedAt: '2026-11-26T09:00:00Z',
      reviewedAt: '2026-11-01T09:00:00Z',
    }
    expect(verificationUploadCutoff(verification)).toBe(verification.expiredAt)
    expect(verificationUploadCutoff({ ...verification, expiredAt: null })).toBe(verification.updatedAt)
    expect(verificationUploadCutoff({ ...verification, status: 'REJECTED' })).toBe(verification.reviewedAt)
    expect(verificationUploadCutoff({ ...verification, status: 'RESUBMISSION_REQUESTED' })).toBe(verification.reviewedAt)
    expect(verificationUploadCutoff(null)).toBeUndefined()
  })

  it('compares new uploads to the previous decision timestamp', () => {
    const docs = [{ uploadedAt: '2026-11-26T10:00:00Z' }]
    expect(documentsUploadedAfter(docs, '2026-11-25T00:00:00Z')).toBe(true)
    expect(documentsUploadedAfter(docs, '2026-11-26T10:00:00Z')).toBe(false)
  })
})
