import { useEffect, useRef, useState } from 'react'

import CompletionIcon from './CompletionIcons'
import { MAX_PHOTOS, toPhotoItems } from '../utils/completionUtils'

/**
 * Photo selector for the completion form: JPG or PNG, up to 5, max 5 MB each.
 * Controlled: `items` and `onChange` are owned by the parent form.
 */
export default function PhotoPicker({ items, onChange, disabled = false }) {
  const inputRef = useRef(null)
  const [failed, setFailed] = useState([]) // previews that could not be displayed
  const validCount = items.filter((item) => !item.error).length

  // Release preview URLs when the picker is unmounted.
  const itemsRef = useRef(items)
  useEffect(() => {
    itemsRef.current = items
  }, [items])
  useEffect(() => () => {
    itemsRef.current.forEach((item) => item.previewUrl && URL.revokeObjectURL?.(item.previewUrl))
  }, [])

  const handleFiles = (event) => {
    const files = event.target.files
    if (files?.length) {
      onChange([...items, ...toPhotoItems(files, validCount)])
    }
    event.target.value = ''
  }

  const remove = (id) => {
    const item = items.find((photo) => photo.id === id)
    if (item?.previewUrl) {
      URL.revokeObjectURL?.(item.previewUrl)
    }
    onChange(items.filter((photo) => photo.id !== id))
  }

  const errors = items.filter((item) => item.error)

  return (
    <div className="sc-photo-picker">
      <p className="sc-field-label" id="sc-photos-label">
        Photos <span className="sc-field-label__hint">(optional · JPG or PNG · up to 5 · max 5 MB each)</span>
      </p>

      <div className="sc-photo-grid" role="group" aria-labelledby="sc-photos-label">
        {validCount < MAX_PHOTOS && (
          <button
            type="button"
            className="sc-photo-add"
            onClick={() => inputRef.current?.click()}
            disabled={disabled}
          >
            <CompletionIcon name="upload" size={24} />
            <span>Add photo</span>
          </button>
        )}

        {items.map((item, index) => (
          <span key={item.id} className={`sc-photo-item${item.error ? ' sc-photo-item--error' : ''}`}>
            {item.previewUrl && !failed.includes(item.id) ? (
              <img
                src={item.previewUrl}
                alt={`Selected photo ${item.file.name}`}
                onError={() => setFailed((ids) => [...ids, item.id])}
              />
            ) : (
              <span className={`sc-photo sc-photo--medium sc-photo--${item.error ? 'red' : ['teal', 'indigo', 'amber'][index % 3]}`}>
                <CompletionIcon name="image" size={28} />
              </span>
            )}
            <button
              type="button"
              className="sc-photo-remove"
              onClick={() => remove(item.id)}
              disabled={disabled}
              aria-label={`Remove ${item.file.name}`}
            >
              <CompletionIcon name="close" size={14} />
            </button>
          </span>
        ))}
      </div>

      <input
        ref={inputRef}
        type="file"
        accept=".jpg,.jpeg,.png,image/jpeg,image/png"
        multiple
        hidden
        onChange={handleFiles}
        data-testid="sc-photo-input"
      />

      {errors.map((item) => (
        <p key={item.id} className="sc-field-error" role="alert">
          <CompletionIcon name="alert" size={16} />
          {item.error}
        </p>
      ))}
    </div>
  )
}
