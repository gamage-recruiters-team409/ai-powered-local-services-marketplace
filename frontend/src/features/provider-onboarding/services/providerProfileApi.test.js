import { beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient from '../../../shared/services/apiClient'
import {
  createProviderProfile,
  getMyProviderActivity,
  getMyProviderPayoutDetails,
  getMyProviderProfile,
  getMyProviderVerificationStatus,
  getMyVerificationDocuments,
  getProviderCategories,
  getProviderDistricts,
  removeProviderProfileImage,
  submitMyProviderVerification,
  updateProviderProfile,
  updateMyProviderPayoutDetails,
  pauseMyProviderProfile,
  reactivateMyProviderProfile,
  uploadMyVerificationDocument,
  uploadProviderProfileImage,
} from './providerProfileApi'

vi.mock('../../../shared/services/apiClient', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

describe('WP02 Provider profile and verification API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads the signed-in provider profile', async () => {
    apiClient.get.mockResolvedValue({ data: { displayName: 'Provider' } })
    await expect(getMyProviderProfile()).resolves.toEqual({ displayName: 'Provider' })
    expect(apiClient.get).toHaveBeenCalledWith('/providers/me')
  })

  it('creates and updates the existing provider profile endpoints', async () => {
    const payload = { providerType: 'INDIVIDUAL', displayName: 'Provider' }
    apiClient.post.mockResolvedValue({ data: { id: 'profile1' } })
    apiClient.put.mockResolvedValue({ data: { id: 'profile1' } })
    await createProviderProfile(payload)
    await updateProviderProfile(payload)
    expect(apiClient.post).toHaveBeenCalledWith('/providers/me', payload)
    expect(apiClient.put).toHaveBeenCalledWith('/providers/me', payload)
  })

  it('uses existing district and category catalogs', async () => {
    apiClient.get.mockResolvedValueOnce({ data: { provinces: [{ name: 'Western', districts: ['Colombo'] }] } })
      .mockResolvedValueOnce({ data: [{ id: 'cat1', active: true }] })
    await expect(getProviderDistricts()).resolves.toHaveLength(1)
    await expect(getProviderCategories()).resolves.toHaveLength(1)
    expect(apiClient.get).toHaveBeenCalledWith('/locations/districts')
    expect(apiClient.get).toHaveBeenCalledWith('/categories')
  })

  it('uses multipart upload and removes only the provider profile image', async () => {
    const file = new File(['photo'], 'profile.png', { type: 'image/png' })
    apiClient.post.mockResolvedValue({ data: { profileImageUrl: 'https://example.com/image.png' } })
    apiClient.delete.mockResolvedValue({ data: { profileImageUrl: null } })
    await uploadProviderProfileImage(file)
    const [, formData] = apiClient.post.mock.calls[0]
    expect(apiClient.post.mock.calls[0][0]).toBe('/providers/me/profile-image')
    expect(formData.get('file')).toBe(file)
    await removeProviderProfileImage()
    expect(apiClient.delete).toHaveBeenCalledWith('/providers/me/profile-image')
  })

  it('reads the real verification status', async () => {
    apiClient.get.mockResolvedValue({ data: { status: 'APPROVED' } })
    await expect(getMyProviderVerificationStatus()).resolves.toEqual({ status: 'APPROVED' })
    expect(apiClient.get).toHaveBeenCalledWith('/providers/me/verification/status')
  })

  it('reads the real provider activity state', async () => {
    const activity = { activityStatus: 'PAUSED', canReceiveNewBookings: false }
    apiClient.get.mockResolvedValue({ data: activity })
    await expect(getMyProviderActivity()).resolves.toEqual(activity)
    expect(apiClient.get).toHaveBeenCalledWith('/providers/me/activity')
  })

  it('retrieves only the masked payout summary', async () => {
    const payout = { configured: true, bankName: 'Bank', maskedAccountNumber: '********9012' }
    apiClient.get.mockResolvedValue({ data: payout })
    await expect(getMyProviderPayoutDetails()).resolves.toEqual(payout)
    expect(apiClient.get).toHaveBeenCalledWith('/providers/me/payout-details')
  })

  it('loads the provider document list', async () => {
    const docs = [{ id: 'doc1', documentType: 'ID' }]
    apiClient.get.mockResolvedValue({ data: docs })
    await expect(getMyVerificationDocuments()).resolves.toEqual(docs)
    expect(apiClient.get).toHaveBeenCalledWith('/providers/me/documents')
  })

  it('uploads the correct document type and file', async () => {
    const file = new File(['pdf'], 'document.pdf', { type: 'application/pdf' })
    apiClient.post.mockResolvedValue({ data: { id: 'doc2' } })
    await uploadMyVerificationDocument({ documentType: 'ID', file, issueDate: '2025-01-01', expiryDate: '2028-01-01' })
    const [url, body, options] = apiClient.post.mock.calls[0]
    expect(url).toBe('/providers/me/documents')
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('documentType')).toBe('ID')
    expect(body.get('file')).toBe(file)
    expect(body.get('issueDate')).toBe('2025-01-01')
    expect(body.get('expiryDate')).toBe('2028-01-01')
    expect(options.headers['Content-Type']).toBe('multipart/form-data')
  })

  it('omits optional dates when uploading without them', async () => {
    const file = new File(['img'], 'id.png', { type: 'image/png' })
    apiClient.post.mockResolvedValue({ data: { id: 'doc3' } })
    await uploadMyVerificationDocument({ documentType: 'BUSINESS', file, issueDate: '', expiryDate: '' })
    const body = apiClient.post.mock.calls[0][1]
    expect(body.has('issueDate')).toBe(false)
    expect(body.has('expiryDate')).toBe(false)
  })

  it('submits verification through the existing provider endpoint', async () => {
    apiClient.post.mockResolvedValue({ data: { status: 'SUBMITTED' } })
    await expect(submitMyProviderVerification()).resolves.toEqual({ status: 'SUBMITTED' })
    expect(apiClient.post).toHaveBeenCalledWith('/providers/me/verification/submit')
  })

  it('updates payout details through the encrypted payout endpoint', async () => {
    const details = { bankName: 'Commercial Bank', accountHolderName: 'Example Provider', accountNumber: '1234567890' }
    apiClient.put.mockResolvedValue({ data: { configured: true, maskedAccountNumber: '******7890' } })
    await updateMyProviderPayoutDetails(details)
    expect(apiClient.put).toHaveBeenCalledWith('/providers/me/payout-details', details)
  })

  it('pauses provider activity through the existing endpoint', async () => {
    apiClient.post.mockResolvedValue({ data: { activityStatus: 'PAUSED' } })
    await expect(pauseMyProviderProfile()).resolves.toMatchObject({ activityStatus: 'PAUSED' })
    expect(apiClient.post).toHaveBeenCalledWith('/providers/me/pause')
  })

  it('reactivates provider activity through the existing endpoint', async () => {
    apiClient.post.mockResolvedValue({ data: { activityStatus: 'ACTIVE' } })
    await expect(reactivateMyProviderProfile()).resolves.toMatchObject({ activityStatus: 'ACTIVE' })
    expect(apiClient.post).toHaveBeenCalledWith('/providers/me/reactivate')
  })

})
