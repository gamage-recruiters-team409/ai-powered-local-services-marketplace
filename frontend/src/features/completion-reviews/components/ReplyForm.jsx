import { useState } from 'react'

import { Button, Textarea } from '../../../shared/components/forms'

import { REPLY_MAX_LENGTH } from '../utils/reviewUtils'

/**
 * S12 – Reviews_Provider_ReplyToReview: inline reply form under a review.
 * onSubmit(text) returns a promise; a rejected promise keeps the form open with the error.
 */
export default function ReplyForm({ reviewId, onCancel, onSubmit, serverError }) {
  const [text, setText] = useState('')
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  const submit = async (event) => {
    event.preventDefault()
    const trimmed = text.trim()
    if (!trimmed) {
      setError('Please write your reply.')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await onSubmit(trimmed)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="rv-reply-form" onSubmit={submit} noValidate aria-label="Reply to review">
      <Textarea
        id={`reply-${reviewId}`}
        label="Your public reply"
        rows={3}
        value={text}
        maxLength={REPLY_MAX_LENGTH}
        onChange={(event) => setText(event.target.value)}
        error={error}
        disabled={submitting}
        autoFocus
      />
      {serverError && (
        <p className="rv-error" role="alert">
          {serverError}
        </p>
      )}
      <div className="rv-reply-form-foot">
        <p className="rv-muted">One reply per review · Everyone can see it · You can't delete reviews.</p>
        <div className="rv-actions">
          <Button variant="outline" size="small" onClick={onCancel} disabled={submitting}>
            Cancel
          </Button>
          <Button type="submit" size="small" loading={submitting}>
            Post reply
          </Button>
        </div>
      </div>
    </form>
  )
}
