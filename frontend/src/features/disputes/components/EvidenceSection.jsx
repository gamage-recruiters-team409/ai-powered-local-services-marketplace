import { useState } from 'react'

import { Button } from '../../../shared/components/forms'
import { fetchEvidenceUrl } from '../services/disputesApi'
import { parseApiError } from '../utils/disputeErrors'
import { EVIDENCE_RULES, evidenceCountFor, formatBytes } from '../utils/evidence'
import { formatDateTime } from '../utils/format'
import { DisputeIcon } from './DisputeIcon'
import { CardHeading } from './DisputeParts'
import { EvidencePicker } from './EvidencePicker'
import { pendingUploads, uploadPickedFiles } from '../utils/evidenceUpload'
import styles from '../disputes.module.css'

function uploaderLabel(role, viewerRole) {
  if (viewerRole && role === viewerRole) {
    return 'You'
  }
  if (role === 'CUSTOMER') return 'Customer'
  if (role === 'PROVIDER') return 'Provider'
  return 'Party'
}

/**
 * Uploaded evidence files of a dispute. Each file opens through a short-lived signed link fetched on click (the
 * link is never stored). `viewerRole` is the party viewing (CUSTOMER / PROVIDER) or null for admins.
 * When `canUpload` is true the viewer can add files (up to 5 per party) while the dispute is still open.
 */
export function EvidenceSection({ dispute, viewerRole = null, canUpload = false, onUploaded, title = 'Evidence files' }) {
  const files = Array.isArray(dispute.evidenceFiles) ? dispute.evidenceFiles : []
  const [opening, setOpening] = useState(null)
  const [openError, setOpenError] = useState(null)
  const [picked, setPicked] = useState([])
  const [uploading, setUploading] = useState(false)
  const remaining = viewerRole ? Math.max(0, EVIDENCE_RULES.MAX_FILES - evidenceCountFor(dispute, viewerRole)) : 0

  async function openFile(file) {
    setOpenError(null)
    setOpening(file.id)
    // Open the tab during the click (popup blockers), then point it at the signed link.
    const tab = window.open('', '_blank')
    try {
      const { url } = await fetchEvidenceUrl(dispute.id, file.id)
      if (tab) {
        tab.opener = null
        tab.location.href = url
      } else {
        window.location.assign(url)
      }
    } catch (error) {
      tab?.close()
      setOpenError(parseApiError(error).kind === 'evidence-unavailable'
        ? 'Files cannot be opened right now. Please try again later.'
        : 'This file could not be opened. Please try again.')
    } finally {
      setOpening(null)
    }
  }

  async function upload() {
    setUploading(true)
    try {
      const uploaded = await uploadPickedFiles(dispute.id, picked, setPicked)
      if (uploaded.length > 0) {
        onUploaded?.(uploaded)
      }
    } finally {
      setUploading(false)
    }
  }

  const ready = pendingUploads(picked).length

  return (
    <section className={styles.card} aria-labelledby="dispute-evidence-title">
      <CardHeading id="dispute-evidence-title" icon="file" title={`${title}${files.length ? ` (${files.length})` : ''}`} />
      {files.length === 0
        ? <p className={styles.muted}>No files have been uploaded yet.</p>
        : (
          <ul className={styles.fileList}>
            {files.map((file) => (
              <li key={file.id} className={styles.fileRow}>
                <span className={styles.evidenceIcon} aria-hidden="true">
                  <DisputeIcon name={file.type === 'PDF' ? 'file' : 'image'} size={18} />
                </span>
                <span className={styles.fileInfo}>
                  <span className={styles.fileName}>{file.fileName}</span>
                  <span className={styles.muted}>
                    {file.type} · {formatBytes(file.sizeBytes)} · {uploaderLabel(file.uploadedByRole, viewerRole)}
                    {file.uploadedAt && ` · ${formatDateTime(file.uploadedAt)}`}
                  </span>
                </span>
                <Button
                  variant="secondary"
                  size="small"
                  onClick={() => openFile(file)}
                  loading={opening === file.id}
                  aria-label={`View ${file.fileName}`}
                >
                  View
                </Button>
              </li>
            ))}
          </ul>
        )}
      {openError && <p className={styles.pickedError} role="alert">{openError}</p>}

      {canUpload && viewerRole && (
        <div className={styles.uploadBlock}>
          <EvidencePicker
            files={picked}
            onChange={setPicked}
            maxFiles={remaining}
            disabled={uploading}
            label="Add more evidence"
          />
          {ready > 0 && (
            <Button onClick={upload} loading={uploading} disabled={uploading}>
              Upload {ready} file{ready === 1 ? '' : 's'}
            </Button>
          )}
        </div>
      )}
    </section>
  )
}
