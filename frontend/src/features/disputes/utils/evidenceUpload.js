import { uploadEvidence } from '../services/disputesApi'
import { parseApiError } from './disputeErrors'

/** Valid files that still need uploading. */
export function pendingUploads(files) {
  return files.filter((item) => !item.error && item.status === 'ready')
}

/**
 * Uploads the valid chosen files one by one, reporting progress through `update(nextFiles)`.
 * Returns the uploaded EvidenceViews; a failed file keeps its error and the others continue.
 */
export async function uploadPickedFiles(disputeId, files, update) {
  let current = files
  const set = (key, patch) => {
    current = current.map((item) => (item.key === key ? { ...item, ...patch } : item))
    update(current)
  }
  const uploaded = []
  for (const item of pendingUploads(files)) {
    set(item.key, { status: 'uploading', progress: 0 })
    try {
      const view = await uploadEvidence(disputeId, item.file, (progress) => set(item.key, { progress }))
      uploaded.push(view)
      set(item.key, { status: 'done', progress: 100 })
    } catch (error) {
      set(item.key, { status: 'failed', error: parseApiError(error).message })
    }
  }
  return uploaded
}
