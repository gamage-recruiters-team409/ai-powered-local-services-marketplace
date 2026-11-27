import { useId } from 'react'

import { LIMITS } from '../config/usersConfig'
import styles from '../adminUsers.module.css'

/** Administrator note: labelled textarea with a live character counter and an inline error. */
export function NoteField({ label, value, onChange, error, required, disabled, placeholder, hint }) {
  const id = useId()
  const errorId = `${id}-error`
  const counterId = `${id}-counter`
  const length = value.trim().length
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.fieldLabel}>
        {label}
        {required && (
          <span className={styles.required} aria-hidden="true">
            {' *'}
          </span>
        )}
      </label>
      <textarea
        id={id}
        className={`${styles.textarea} ${error ? styles.inputError : ''}`}
        value={value}
        rows={4}
        maxLength={LIMITS.NOTE_MAX + 50}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
        placeholder={placeholder}
        required={required}
        aria-required={required || undefined}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={[counterId, error ? errorId : null].filter(Boolean).join(' ')}
      />
      <div className={styles.fieldFoot}>
        {error ? (
          <p id={errorId} className={styles.fieldError}>
            {error}
          </p>
        ) : (
          <span className={styles.fieldHint}>{hint}</span>
        )}
        <span id={counterId} className={`${styles.counter} ${length > LIMITS.NOTE_MAX ? styles.counterOver : ''}`}>
          {length}/{LIMITS.NOTE_MAX}
        </span>
      </div>
    </div>
  )
}
