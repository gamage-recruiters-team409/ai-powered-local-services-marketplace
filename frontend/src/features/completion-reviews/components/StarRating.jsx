import { useRef } from 'react'

import CompletionIcon from './CompletionIcons'
import { RATING_LABELS, RATING_MAX } from '../utils/reviewUtils'

const STARS = Array.from({ length: RATING_MAX }, (_, i) => i + 1)

/** Read-only stars, e.g. on review cards. */
export function StarRatingDisplay({ value, size = 16 }) {
  return (
    <span className="rv-stars" role="img" aria-label={`${value} out of ${RATING_MAX} stars`}>
      {STARS.map((star) => (
        <span key={star} className={star <= value ? 'rv-star rv-star--on' : 'rv-star'}>
          <CompletionIcon name="star" size={size} />
        </span>
      ))}
    </span>
  )
}

/**
 * Star rating input (S07/S08). A radio group, so it works with the keyboard:
 * Tab moves into the group, arrow keys change the rating.
 */
export function StarRatingInput({ value, onChange, error, disabled = false, label = 'Your rating' }) {
  const buttons = useRef([])

  const select = (star) => {
    if (!disabled) {
      onChange(star)
    }
  }

  const onKeyDown = (event) => {
    const current = value || 0
    let next = null
    if (event.key === 'ArrowRight' || event.key === 'ArrowUp') {
      next = Math.min(RATING_MAX, current + 1)
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowDown') {
      next = Math.max(1, current - 1)
    }
    if (next) {
      event.preventDefault()
      select(next)
      buttons.current[next - 1]?.focus()
    }
  }

  return (
    <div className="rv-field">
      <span className="rv-label" id="rv-rating-label">
        {label} <span className="rv-required" aria-hidden="true">*</span>
      </span>
      <div className="rv-rating-row">
        <div
          role="radiogroup"
          aria-labelledby="rv-rating-label"
          aria-required="true"
          aria-invalid={Boolean(error)}
          aria-describedby={error ? 'rv-rating-error' : undefined}
          className="rv-rating-input"
          onKeyDown={onKeyDown}
        >
          {STARS.map((star) => (
            <button
              key={star}
              ref={(el) => {
                buttons.current[star - 1] = el
              }}
              type="button"
              role="radio"
              aria-checked={value === star}
              aria-label={`${star} star${star > 1 ? 's' : ''} – ${RATING_LABELS[star]}`}
              tabIndex={value ? (value === star ? 0 : -1) : star === 1 ? 0 : -1}
              className={star <= (value || 0) ? 'rv-star-btn rv-star-btn--on' : 'rv-star-btn'}
              onClick={() => select(star)}
              disabled={disabled}
            >
              <CompletionIcon name="star" size={30} />
            </button>
          ))}
        </div>
        <span className="rv-rating-hint" aria-live="polite">
          {value ? RATING_LABELS[value] : 'Tap a star to rate'}
        </span>
      </div>
      {error && (
        <p className="rv-error" id="rv-rating-error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
