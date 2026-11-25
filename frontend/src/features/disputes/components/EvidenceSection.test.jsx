// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../services/disputesApi', () => ({
  uploadEvidence: vi.fn(),
  fetchEvidenceUrl: vi.fn(),
}))

import { fetchEvidenceUrl, uploadEvidence } from '../services/disputesApi'
import { EvidenceSection } from './EvidenceSection'

function file(name, type, size = 1000) {
  const blob = new File([new Uint8Array(Math.min(size, 16))], name, { type })
  Object.defineProperty(blob, 'size', { value: size })
  return blob
}

function evidence(overrides = {}) {
  return {
    id: 'ev-1',
    fileName: 'door.jpg',
    type: 'JPG',
    contentType: 'image/jpeg',
    sizeBytes: 204800,
    uploadedByRole: 'PROVIDER',
    uploadedAt: '2026-11-20T10:00:00Z',
    ...overrides,
  }
}

function renderSection(props = {}) {
  const onUploaded = vi.fn()
  render(
    <EvidenceSection
      dispute={{ id: 'd-1', status: 'AWAITING_RESPONSE', evidenceFiles: [evidence()] }}
      viewerRole="CUSTOMER"
      canUpload
      onUploaded={onUploaded}
      {...props}
    />,
  )
  return { onUploaded }
}

const input = () => screen.getByLabelText('Add more evidence')

beforeEach(() => {
  uploadEvidence.mockReset()
  fetchEvidenceUrl.mockReset()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('EvidenceSection list', () => {
  it('lists the files with type, size and who uploaded them', () => {
    renderSection()

    const list = screen.getByRole('list')
    expect(within(list).getByText('door.jpg')).toBeTruthy()
    expect(within(list).getByText(/JPG · 200 KB · Provider/)).toBeTruthy()
  })

  it('"View" fetches a fresh signed link and opens it in a new tab (the link is not kept)', async () => {
    const tab = { opener: 'x', location: { href: '' }, close: vi.fn() }
    vi.spyOn(window, 'open').mockReturnValue(tab)
    fetchEvidenceUrl.mockResolvedValue({ url: 'https://files.example.test/signed', expiresAt: '2026-11-20T10:05:00Z' })
    renderSection()

    fireEvent.click(screen.getByRole('button', { name: 'View door.jpg' }))

    await waitFor(() => expect(tab.location.href).toBe('https://files.example.test/signed'))
    expect(fetchEvidenceUrl).toHaveBeenCalledWith('d-1', 'ev-1')
    expect(tab.opener).toBeNull()
  })

  it('a link that cannot be created -> message, the empty tab is closed', async () => {
    const tab = { opener: null, location: { href: '' }, close: vi.fn() }
    vi.spyOn(window, 'open').mockReturnValue(tab)
    fetchEvidenceUrl.mockRejectedValue(Object.assign(new Error('x'),
      { response: { status: 503, data: { error: 'EVIDENCE_STORAGE_UNAVAILABLE' } } }))
    renderSection()

    fireEvent.click(screen.getByRole('button', { name: 'View door.jpg' }))

    expect(await screen.findByText(/Files cannot be opened right now/)).toBeTruthy()
    expect(tab.close).toHaveBeenCalled()
  })

  it('admins (no viewer role) see the list but no upload controls', () => {
    renderSection({ viewerRole: null, canUpload: false })

    expect(screen.getByText('door.jpg')).toBeTruthy()
    expect(screen.queryByLabelText('Add more evidence')).toBeNull()
  })
})

describe('EvidenceSection upload', () => {
  it('rejects a wrong type and a file over 5 MB before uploading', () => {
    renderSection()

    fireEvent.change(input(), { target: { files: [file('virus.exe', 'application/x-msdownload'),
      file('huge.png', 'image/png', 6 * 1024 * 1024)] } })

    expect(screen.getByText('Only JPG, PNG, WEBP or PDF files can be uploaded.')).toBeTruthy()
    expect(screen.getByText('Each file must be 5 MB or smaller.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^Upload/ })).toBeNull()
    expect(uploadEvidence).not.toHaveBeenCalled()
  })

  it('uploads the valid files with a progress bar, then reports them', async () => {
    let finish
    uploadEvidence.mockImplementation((disputeId, chosen, onProgress) => new Promise((resolve) => {
      onProgress(40)
      finish = () => resolve(evidence({ id: 'ev-2', fileName: chosen.name, uploadedByRole: 'CUSTOMER' }))
    }))
    const { onUploaded } = renderSection()

    fireEvent.change(input(), { target: { files: [file('receipt.pdf', 'application/pdf')] } })
    fireEvent.click(screen.getByRole('button', { name: 'Upload 1 file' }))

    const bar = await screen.findByRole('progressbar', { name: 'Uploading receipt.pdf' })
    expect(bar.getAttribute('aria-valuenow')).toBe('40')
    finish()

    await waitFor(() => expect(onUploaded).toHaveBeenCalledWith([expect.objectContaining({ id: 'ev-2' })]))
    expect(uploadEvidence).toHaveBeenCalledWith('d-1', expect.any(File), expect.any(Function))
    expect(screen.getByText('Uploaded')).toBeTruthy()
  })

  it('a server rejection is shown on that file (e.g. the 5-file limit)', async () => {
    uploadEvidence.mockRejectedValue(Object.assign(new Error('x'),
      { response: { status: 409, data: { error: 'EVIDENCE_LIMIT_REACHED' } } }))
    const { onUploaded } = renderSection()

    fireEvent.change(input(), { target: { files: [file('photo.png', 'image/png')] } })
    fireEvent.click(screen.getByRole('button', { name: 'Upload 1 file' }))

    expect(await screen.findByText('You can upload at most 5 files to this dispute.')).toBeTruthy()
    expect(onUploaded).not.toHaveBeenCalled()
  })

  it('only as many files as the party has left (5 per party)', () => {
    const own = Array.from({ length: 4 }, (_, i) => evidence({ id: `own-${i}`, uploadedByRole: 'CUSTOMER' }))
    renderSection({ dispute: { id: 'd-1', status: 'UNDER_REVIEW', evidenceFiles: own } })

    expect(screen.getByText(/You can add 1 more/)).toBeTruthy()
    fireEvent.change(input(), { target: { files: [file('a.png', 'image/png'), file('b.png', 'image/png')] } })

    expect(screen.getByText('You can add at most 1 file.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Upload 1 file' })).toBeTruthy()
  })

  it('uploading three files successfully leaves two available slots for additional selection', async () => {
    let currentDispute = { id: 'd-1', status: 'AWAITING_RESPONSE', evidenceFiles: [] }
    uploadEvidence.mockImplementation((disputeId, chosen) =>
      Promise.resolve(evidence({ id: `ev-${chosen.name}`, fileName: chosen.name, uploadedByRole: 'CUSTOMER' })))

    const { rerender } = render(
      <EvidenceSection
        dispute={currentDispute}
        viewerRole="CUSTOMER"
        canUpload
        onUploaded={(uploaded) => {
          currentDispute = { ...currentDispute, evidenceFiles: [...currentDispute.evidenceFiles, ...uploaded] }
        }}
      />,
    )

    expect(screen.getByText(/You can add 5 more/)).toBeTruthy()

    fireEvent.change(input(), {
      target: { files: [file('f1.png', 'image/png'), file('f2.png', 'image/png'), file('f3.png', 'image/png')] },
    })
    expect(screen.getByRole('button', { name: 'Upload 3 files' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Upload 3 files' }))
    await waitFor(() => expect(uploadEvidence).toHaveBeenCalledTimes(3))

    rerender(
      <EvidenceSection
        dispute={currentDispute}
        viewerRole="CUSTOMER"
        canUpload
        onUploaded={(uploaded) => {
          currentDispute = { ...currentDispute, evidenceFiles: [...currentDispute.evidenceFiles, ...uploaded] }
        }}
      />,
    )

    expect(screen.getByText(/You can add 2 more/)).toBeTruthy()
    expect(input().disabled).toBe(false)
  })

  it('uploading the remaining two files disables the picker only after reaching five files', async () => {
    let currentDispute = {
      id: 'd-1',
      status: 'AWAITING_RESPONSE',
      evidenceFiles: [
        evidence({ id: 'ev-1', uploadedByRole: 'CUSTOMER' }),
        evidence({ id: 'ev-2', uploadedByRole: 'CUSTOMER' }),
        evidence({ id: 'ev-3', uploadedByRole: 'CUSTOMER' }),
      ],
    }
    uploadEvidence.mockImplementation((disputeId, chosen) =>
      Promise.resolve(evidence({ id: `ev-${chosen.name}`, fileName: chosen.name, uploadedByRole: 'CUSTOMER' })))

    const { rerender } = render(
      <EvidenceSection
        dispute={currentDispute}
        viewerRole="CUSTOMER"
        canUpload
        onUploaded={(uploaded) => {
          currentDispute = { ...currentDispute, evidenceFiles: [...currentDispute.evidenceFiles, ...uploaded] }
        }}
      />,
    )

    expect(screen.getByText(/You can add 2 more/)).toBeTruthy()

    fireEvent.change(input(), {
      target: { files: [file('f4.png', 'image/png'), file('f5.png', 'image/png')] },
    })

    expect(screen.getByText(/File limit reached/)).toBeTruthy()
    expect(input().disabled).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: 'Upload 2 files' }))
    await waitFor(() => expect(uploadEvidence).toHaveBeenCalledTimes(2))

    rerender(
      <EvidenceSection
        dispute={currentDispute}
        viewerRole="CUSTOMER"
        canUpload
      />,
    )

    expect(screen.getByText(/File limit reached/)).toBeTruthy()
    expect(input().disabled).toBe(true)
  })

  it('allowance remains correct when uploads partially fail without deducting failed files', async () => {
    let currentDispute = { id: 'd-1', status: 'AWAITING_RESPONSE', evidenceFiles: [] }
    uploadEvidence.mockImplementation((disputeId, chosen) => {
      if (chosen.name === 'f2.png') {
        return Promise.reject(new Error('Network error'))
      }
      return Promise.resolve(evidence({ id: `ev-${chosen.name}`, fileName: chosen.name, uploadedByRole: 'CUSTOMER' }))
    })

    const { rerender } = render(
      <EvidenceSection
        dispute={currentDispute}
        viewerRole="CUSTOMER"
        canUpload
        onUploaded={(uploaded) => {
          currentDispute = { ...currentDispute, evidenceFiles: [...currentDispute.evidenceFiles, ...uploaded] }
        }}
      />,
    )

    fireEvent.change(input(), {
      target: { files: [file('f1.png', 'image/png'), file('f2.png', 'image/png'), file('f3.png', 'image/png')] },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Upload 3 files' }))

    await waitFor(() => expect(uploadEvidence).toHaveBeenCalledTimes(3))

    rerender(
      <EvidenceSection
        dispute={currentDispute}
        viewerRole="CUSTOMER"
        canUpload
        onUploaded={(uploaded) => {
          currentDispute = { ...currentDispute, evidenceFiles: [...currentDispute.evidenceFiles, ...uploaded] }
        }}
      />,
    )

    expect(screen.getByRole('alert')).toBeTruthy()
    expect(screen.getByText(/We could not reach the server/i)).toBeTruthy()
    expect(screen.getByText(/You can add 3 more/)).toBeTruthy()
    expect(input().disabled).toBe(false)
  })

  it('existing evidence uploaded by other party does not reduce current party allowance', () => {
    const providerFiles = Array.from({ length: 4 }, (_, i) => evidence({ id: `prov-${i}`, uploadedByRole: 'PROVIDER' }))
    renderSection({
      dispute: { id: 'd-1', status: 'AWAITING_RESPONSE', evidenceFiles: providerFiles },
      viewerRole: 'CUSTOMER',
    })

    expect(screen.getByText(/You can add 5 more/)).toBeTruthy()
    expect(input().disabled).toBe(false)
  })
})
