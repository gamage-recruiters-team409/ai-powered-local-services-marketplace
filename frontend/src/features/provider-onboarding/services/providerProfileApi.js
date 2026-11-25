import apiClient from '../../../shared/services/apiClient'

export async function getMyProviderProfile() {
  const response = await apiClient.get('/providers/me')
  return response.data
}

export async function createProviderProfile(payload) {
  const response = await apiClient.post('/providers/me', payload)
  return response.data
}

export async function updateProviderProfile(payload) {
  const response = await apiClient.put('/providers/me', payload)
  return response.data
}

export async function getProviderDistricts() {
  const response = await apiClient.get('/locations/districts')
  return response.data.provinces ?? []
}

export async function getProviderCategories() {
  const response = await apiClient.get('/categories')
  return response.data ?? []
}

export async function uploadProviderProfileImage(file) {
  const formData = new FormData()
  formData.append('file', file)
  const response = await apiClient.post('/providers/me/profile-image', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return response.data
}

export async function removeProviderProfileImage() {
  const response = await apiClient.delete('/providers/me/profile-image')
  return response.data
}

// Read-only WP02 summary endpoints; these do not change the underlying records.
export async function getMyProviderVerificationStatus() {
  const response = await apiClient.get('/providers/me/verification/status')
  return response.data
}

export async function getMyProviderActivity() {
  const response = await apiClient.get('/providers/me/activity')
  return response.data
}

export async function getMyProviderPayoutDetails() {
  const response = await apiClient.get('/providers/me/payout-details')
  return response.data
}

// WP02 Provider verification documents. These endpoints require a Provider account.
export async function getMyVerificationDocuments() {
  const response = await apiClient.get('/providers/me/documents')
  return response.data
}

export async function uploadMyVerificationDocument({ documentType, file, issueDate, expiryDate }) {
  const formData = new FormData()
  formData.append('documentType', documentType)
  formData.append('file', file)
  if (issueDate) formData.append('issueDate', issueDate)
  if (expiryDate) formData.append('expiryDate', expiryDate)
  const response = await apiClient.post('/providers/me/documents', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return response.data
}

export async function submitMyProviderVerification() {
  const response = await apiClient.post('/providers/me/verification/submit')
  return response.data
}

// Existing WP02 write contracts used by the management modals.
export async function updateMyProviderPayoutDetails(payload) {
  const response = await apiClient.put('/providers/me/payout-details', payload)
  return response.data
}

export async function pauseMyProviderProfile() {
  const response = await apiClient.post('/providers/me/pause')
  return response.data
}

export async function reactivateMyProviderProfile() {
  const response = await apiClient.post('/providers/me/reactivate')
  return response.data
}
