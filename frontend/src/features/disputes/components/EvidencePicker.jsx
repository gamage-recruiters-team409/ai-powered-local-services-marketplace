import { useId } from 'react'

import { EVIDENCE_RULES, formatBytes, toPickedFile } from '../utils/evidence'
import { DisputeIcon } from './DisputeIcon'
import styles from '../disputes.module.css'

/**
 * Lets a party choose evidence files (JPG, PNG, WEBP, PDF; 5 MB each; at most `maxFiles`). Each file is checked as
 * soon as it is chosen; the upload itself happens later (utils/evidenceUpload.js), with a progress bar per file.
 * `files` is controlled by the parent: [{ key, file, error, progress, status }].
 */
export function EvidencePicker({ files, onChange, maxFiles = EVIDENCE_RULES.MAX_FILES, disabled = false,
  label = 'Add evidence (optional)' }) {
  const inputId = useId()
  const hintId = `${inputId}-hint`
  const pendingCount = files.filter((item) => item.status !== 'done' && item.status !== 'failed' && item.status !== 'skipped').length
  const room = Math.max(0, maxFiles - pendingCount)

  function handleSelect(event) {
    const chosen = Array.from(event.target.files ?? [])
    event.target.value = ''
    if (chosen.length === 0) {
      return
    }
    const added = chosen.slice(0, room).map(toPickedFile)
    const skipped = chosen.length - added.length
    const tooMany = skipped > 0
      ? [{ key: `limit-${Date.now()}`, file: { name: `${skipped} more file${skipped === 1 ? '' : 's'}`, size: 0 },
        error: `You can add at most ${maxFiles} file${maxFiles === 1 ? '' : 's'}.`, progress: 0, status: 'skipped' }]
      : []
    onChange([...files.filter((item) => item.status !== 'skipped'), ...added, ...tooMany])
  }

  function remove(key) {
    onChange(files.filter((item) => item.key !== key))
  }

  return (
    <div className={styles.evidencePicker}>
      <label htmlFor={inputId} className={styles.fieldLabel}>{label}</label>
      <div className={`${styles.dropzone} ${disabled || room === 0 ? styles.dropzoneDisabled : ''}`}>
        <span className={styles.dropzoneIcon}><DisputeIcon name="upload" size={22} /></span>
        <input
          id={inputId}
          type="file"
          multiple
          accept={EVIDENCE_RULES.ACCEPT}
          className={styles.fileInput}
          aria-describedby={hintId}
          disabled={disabled || room === 0}
          onChange={handleSelect}
        />
        <span id={hintId} className={styles.muted}>
          JPG, PNG, WEBP or PDF, up to 5 MB each. {room === 0 ? 'File limit reached.' : `You can add ${room} more.`}
        </span>
      </div>
      {files.length > 0 && (
        <ul className={styles.pickedList} aria-label="Chosen files">
          {files.map((item) => (
            <li key={item.key} className={`${styles.pickedItem} ${item.error ? styles.pickedItemError : ''}`}>
              <span className={styles.pickedName}>
                <DisputeIcon name={item.file.type === 'application/pdf' ? 'file' : 'image'} size={16} />
                {item.file.name}
                {item.file.size > 0 && <span className={styles.muted}> · {formatBytes(item.file.size)}</span>}
              </span>
              {item.status === 'uploading' && (
                <span
                  className={styles.progress}
                  role="progressbar"
                  aria-label={`Uploading ${item.file.name}`}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={item.progress}
                >
                  <span className={styles.progressBar} style={{ width: `${item.progress}%` }} />
                </span>
              )}
              {item.status === 'done' && <span className={styles.pickedDone}>Uploaded</span>}
              {item.error && <span className={styles.pickedError} role="alert">{item.error}</span>}
              {item.status !== 'uploading' && (
                <button type="button" className={styles.linkButton} onClick={() => remove(item.key)}
                  aria-label={`Remove ${item.file.name}`} disabled={disabled}>
                  Remove
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
