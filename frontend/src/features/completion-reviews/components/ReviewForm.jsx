import { useState } from 'react'

import { Button, Textarea } from '../../../shared/components/forms'

import CompletionIcon from './CompletionIcons'
import { StarRatingInput } from './StarRating'
import { COMMENT_MAX_LENGTH, toReviewPayload, validateReview } from '../utils/reviewUtils'

/**
 * Shared form for S07 (write) and S08 (edit): star rating, review text, guidance and actions.
 * onSubmit receives { rating, comment } (comment trimmed, empty -> null) and returns a promise.
 */
export default function ReviewForm({
  initialRating = 0,
  initialComment = '',
  notice,
  submitLabel,
  cancelLabel,
  onCancel,
  onSubmit,
  serverError,
}) {
  const [rating, setRating] = useState(initialRating)
  const [comment, setComment] = useState(initialComment ?? '')
  const [errors, setErrors] = useState({})
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (event) => {
    event.preventDefault()
    const found = validateReview({ rating, comment })
    setErrors(found)
    if (Object.keys(found).length > 0) {
      return
    }
    setSubmitting(true)
    try {
      await onSubmit(toReviewPayload({ rating, comment }))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="rv-form" onSubmit={handleSubmit} noValidate>
      <StarRatingInput
        value={rating}
        onChange={(star) => {
          setRating(star)
          setErrors((prev) => ({ ...prev, rating: undefined }))
        }}
        error={errors.rating}
        disabled={submitting}
      />

      <div className="rv-field">
        <Textarea
          label="Your review"
          placeholder="Share details of your experience (optional)"
          rows={5}
          value={comment}
          maxLength={COMMENT_MAX_LENGTH}
          onChange={(event) => setComment(event.target.value)}
          error={errors.comment}
          disabled={submitting}
        />
        <p className="rv-counter" aria-live="polite">
          {comment.length} / {COMMENT_MAX_LENGTH}
        </p>
      </div>

      <div className="rv-note">
        <CompletionIcon name="info" size={18} />
        <p>{notice}</p>
      </div>

      {serverError && (
        <p className="rv-error rv-error--box" role="alert">
          {serverError}
        </p>
      )}

      <div className="rv-actions">
        <Button variant="outline" onClick={onCancel} disabled={submitting}>
          {cancelLabel}
        </Button>
        <Button type="submit" loading={submitting}>
          {submitLabel}
        </Button>
      </div>
    </form>
  )
}
