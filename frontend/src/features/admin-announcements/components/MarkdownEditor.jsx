import { useId, useRef, useState } from 'react'

import { LIMITS } from '../config/announcementConfig'
import { insertLink, prefixLines, wrapSelection } from '../utils/markdownEditing'
import { AnnIcon } from './AnnIcon'
import { MarkdownContent } from './MarkdownContent'
import styles from '../announcements.module.css'

const TOOLS = [
  { id: 'bold', label: 'Bold', icon: 'bold', apply: (v, s, e) => wrapSelection(v, s, e, '**', 'bold text') },
  { id: 'italic', label: 'Italic', icon: 'italic', apply: (v, s, e) => wrapSelection(v, s, e, '_', 'italic text') },
  { id: 'link', label: 'Insert link', icon: 'link', apply: (v, s, e) => insertLink(v, s, e) },
  { id: 'bullets', label: 'Bulleted list', icon: 'list', apply: (v, s, e) => prefixLines(v, s, e, false) },
  { id: 'numbers', label: 'Numbered list', icon: 'listOrdered', apply: (v, s, e) => prefixLines(v, s, e, true) },
]

/**
 * Markdown message editor: toolbar (bold, italic, link, bullet and numbered lists; no underline, markdown has none),
 * "Markdown supported" tag, character counter and a "Preview banner" toggle that shows the message the way users see
 * the banner. The text is stored as raw markdown.
 */
export function MarkdownEditor({ value, onChange, title, error, disabled = false, label = 'Announcement message' }) {
  const id = useId()
  const errorId = `${id}-error`
  const counterId = `${id}-counter`
  const textareaRef = useRef(null)
  const [preview, setPreview] = useState(false)

  const applyTool = (tool) => {
    const textarea = textareaRef.current
    const start = textarea?.selectionStart ?? value.length
    const end = textarea?.selectionEnd ?? value.length
    const result = tool.apply(value, start, end)
    onChange(result.value)
    requestAnimationFrame(() => {
      if (!textareaRef.current) return
      textareaRef.current.focus()
      textareaRef.current.setSelectionRange(result.selectionStart, result.selectionEnd)
    })
  }

  const over = value.length > LIMITS.MESSAGE

  return (
    <div className={styles.editorField}>
      <div className={styles.editorLabelRow}>
        <label htmlFor={id} className={styles.fieldLabel}>
          {label}
          <span className={styles.required} aria-hidden="true">
            {' *'}
          </span>
        </label>
        <span className={styles.tag}>
          <AnnIcon name="fileText" size={13} /> Markdown supported
        </span>
      </div>

      <div className={`${styles.editor} ${error ? styles.editorError : ''} ${disabled ? styles.editorDisabled : ''}`}>
        <div className={styles.toolbar} role="toolbar" aria-label="Formatting" aria-controls={id}>
          {TOOLS.map((tool) => (
            <button
              key={tool.id}
              type="button"
              className={styles.toolButton}
              aria-label={tool.label}
              title={tool.label}
              onClick={() => applyTool(tool)}
              disabled={disabled || preview}
            >
              <AnnIcon name={tool.icon} size={16} />
            </button>
          ))}
          <button
            type="button"
            className={`${styles.previewToggle} ${preview ? styles.previewToggleOn : ''}`}
            aria-pressed={preview}
            onClick={() => setPreview((on) => !on)}
          >
            <AnnIcon name="eye" size={15} />
            Preview banner
          </button>
        </div>

        {preview ? (
          <div className={styles.bannerPreview} aria-label="Banner preview">
            <div className={styles.bannerPreviewHead}>
              <AnnIcon name="megaphone" size={16} />
              <strong>{title?.trim() || 'Announcement title'}</strong>
            </div>
            {value.trim() ? (
              <MarkdownContent text={value} className={styles.bannerPreviewBody} />
            ) : (
              <p className={styles.muted}>Nothing to preview yet.</p>
            )}
          </div>
        ) : (
          <textarea
            id={id}
            ref={textareaRef}
            className={styles.editorTextarea}
            value={value}
            onChange={(event) => onChange(event.target.value)}
            rows={8}
            disabled={disabled}
            required
            aria-required="true"
            aria-invalid={error ? 'true' : undefined}
            aria-describedby={[counterId, error ? errorId : null].filter(Boolean).join(' ')}
            placeholder="Draft your broadcast message, policy updates, or scheduled platform maintenance alert here..."
          />
        )}
      </div>

      <div className={styles.fieldFoot}>
        {error ? (
          <p id={errorId} className={styles.fieldError}>
            {error}
          </p>
        ) : (
          <span />
        )}
        <span id={counterId} className={`${styles.counter} ${over ? styles.counterOver : ''}`}>
          {value.length} / {LIMITS.MESSAGE} characters
        </span>
      </div>
    </div>
  )
}
