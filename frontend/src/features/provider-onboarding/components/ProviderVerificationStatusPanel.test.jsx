// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ProviderVerificationStatusPanel from './ProviderVerificationStatusPanel'

const identity = { id: 'doc-1', documentType: 'ID', originalFileName: 'identity.pdf', uploadedAt: '2026-11-10T10:00:00Z' }
const defaultProps = { documents: [identity], onViewProfile: vi.fn(), onEditDocuments: vi.fn(), asOfDate: '2026-11-25' }

describe('WP02 provider verification status', () => {
  it('renders submitted status using only the documents returned by the API', () => {
    render(<ProviderVerificationStatusPanel {...defaultProps} verification={{ status: 'SUBMITTED' }} />)
    expect(screen.getByRole('heading', { name: 'Verification submitted' })).toBeTruthy()
    expect(screen.getByText('identity.pdf')).toBeTruthy()
    expect(screen.queryByText(/business registration/i)).toBeNull()
    expect(screen.queryByText(/certificate \/ qualification/i)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Update documents' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'View' })).toBeNull()
  })

  it('renders the under-review state without edit controls', () => {
    render(<ProviderVerificationStatusPanel {...defaultProps} verification={{ status: 'UNDER_REVIEW' }} />)
    expect(screen.getByRole('heading', { name: 'Verification under review' })).toBeTruthy()
    expect(screen.getByText(/No action is required/i)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Update documents' })).toBeNull()
  })

  it('shows the approved state based on backend status', () => {
    render(<ProviderVerificationStatusPanel {...defaultProps} verification={{ status: 'APPROVED' }} />)
    expect(screen.getByRole('heading', { name: 'Verification approved' })).toBeTruthy()
    expect(screen.queryByText('certificate.pdf')).toBeNull()
  })

  it('displays the real rejection reason and enables document updates', async () => {
    const edit = vi.fn()
    render(<ProviderVerificationStatusPanel {...defaultProps} verification={{ status: 'REJECTED', decisionReason: 'The ID document is invalid.' }} onEditDocuments={edit} />)
    expect(screen.getByText('The ID document is invalid.')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Update documents' }))
    expect(edit).toHaveBeenCalledOnce()
  })

  it('shows the category-change reason directly from verification data', () => {
    const decisionReason = 'A new service category was added. Please upload updated verification documents and resubmit.'
    render(<ProviderVerificationStatusPanel {...defaultProps} verification={{ status: 'RESUBMISSION_REQUESTED', decisionReason }} />)
    expect(screen.getByText(decisionReason)).toBeTruthy()
  })

  it('shows expired and nonexpired real documents and re-verification action', async () => {
    const edit = vi.fn()
    const documents = [
      { ...identity, expiryDate: '2026-11-20' },
      { id: 'doc-b', documentType: 'BUSINESS', originalFileName: 'business.pdf', expiryDate: '2027-08-01', uploadedAt: '2026-11-10T09:00:00Z' },
    ]
    render(<ProviderVerificationStatusPanel {...defaultProps} verification={{ status: 'EXPIRED' }} documents={documents} onEditDocuments={edit} />)
    expect(screen.getByRole('heading', { name: 'Verification expired' })).toBeTruthy()
    expect(screen.getByText('identity.pdf')).toBeTruthy()
    expect(screen.getByText('business.pdf')).toBeTruthy()
    expect(screen.queryByText(/certificate \/ qualification/i)).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Start re-verification' }))
    expect(edit).toHaveBeenCalledOnce()
  })

  it('shows only the latest ID while preserving independent certificate uploads', () => {
    const documents = [
      { ...identity, id: 'id-old', originalFileName: 'old-id.pdf', uploadedAt: '2026-09-01T12:00:00Z' },
      { ...identity, id: 'id-new', originalFileName: 'new-id.pdf', uploadedAt: '2026-11-10T12:00:00Z' },
      { id: 'cert-1', documentType: 'CERTIFICATE', originalFileName: 'first-cert.pdf', uploadedAt: '2026-11-10T11:00:00Z' },
      { id: 'cert-2', documentType: 'CERTIFICATE', originalFileName: 'second-cert.pdf', uploadedAt: '2026-11-10T10:00:00Z' },
    ]
    render(<ProviderVerificationStatusPanel {...defaultProps} verification={{ status: 'SUBMITTED' }} documents={documents} />)
    expect(screen.getByText('new-id.pdf')).toBeTruthy()
    expect(screen.queryByText('old-id.pdf')).toBeNull()
    expect(screen.getByText('first-cert.pdf')).toBeTruthy()
    expect(screen.getByText('second-cert.pdf')).toBeTruthy()
  })
})
