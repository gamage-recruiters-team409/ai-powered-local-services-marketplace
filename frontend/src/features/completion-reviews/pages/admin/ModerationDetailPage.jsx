import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { Badge, Button } from '../../../../shared/components/forms'
import { PageError, useToast } from '../../../../shared/components/feedback'
import { AdminPage } from '../../../../shared/layouts/admin'
import { useAreaAccess } from '../../../disputes-admin/access/useAdminAccess'

import CompletionIcon from '../../components/CompletionIcons'
import ModerationDecisionDialog from '../../components/admin/ModerationDecisionDialog'
import { StarRatingDisplay } from '../../components/StarRating'
import { moderationApi } from '../../api'
import { formatDateTime } from '../../utils/completionUtils'
import {
  actionLabel,
  availableActions,
  flagLabel,
  flagMessage,
  highlightSegments,
  isConflict,
  moderationErrorMessage,
  removalReasonLabel,
  reportReasonLabel,
} from '../../utils/moderationUtils'
import { reviewStatusBadge } from '../../utils/reviewUtils'

const TOAST = {
  REMOVE: 'Review removed. The customer can see the reason.',
  PUBLISH: 'Review published.',
  KEEP: 'Reports dismissed. The review stays published.',
}

function HighlightedText({ text }) {
  return (
    <p className="rm-text">
      {highlightSegments(text).map((segment, index) =>
        segment.hit ? <mark key={index}>{segment.text}</mark> : <span key={index}>{segment.text}</span>,
      )}
    </p>
  )
}

/**
 * S15 – Reviews_Admin_ModerationDetail. Review, open reports, context and history, with the decisions the
 * backend accepts for its status. Decisions (S16) send the moderationRevision that was loaded; when another
 * moderator decided first the backend answers 409 and the review is reloaded.
 * Super Admin (view only) sees no decision buttons.
 */
export default function ModerationDetailPage({ api = moderationApi }) {
  const { reviewId } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const { canEdit } = useAreaAccess()
  const [state, setState] = useState({ loading: true, error: null, detail: null })
  const [dialog, setDialog] = useState({ kind: null, loading: false, error: null })
  const [notice, setNotice] = useState(null)

  const load = useCallback(async () => {
    setState((prev) => ({ ...prev, loading: true, error: null }))
    try {
      const detail = await api.getReview(reviewId)
      setState({ loading: false, error: null, detail })
    } catch (error) {
      setState({ loading: false, error: moderationErrorMessage(error), detail: null })
    }
  }, [api, reviewId])

  useEffect(() => {
    load()
  }, [load])

  const decide = async ({ reasonCode, note }) => {
    const { kind } = dialog
    const revision = state.detail.review.moderationRevision
    setDialog((prev) => ({ ...prev, loading: true, error: null }))
    try {
      if (kind === 'REMOVE') {
        await api.removeReview(reviewId, { reasonCode, note, revision })
      } else {
        await api.publishReview(reviewId, { note, revision })
      }
      setDialog({ kind: null, loading: false, error: null })
      toast.success(TOAST[kind])
      navigate('..', { relative: 'path' })
    } catch (error) {
      if (isConflict(error)) {
        setDialog({ kind: null, loading: false, error: null })
        setNotice(moderationErrorMessage(error))
        load()
      } else {
        setDialog((prev) => ({ ...prev, loading: false, error: moderationErrorMessage(error) }))
      }
    }
  }

  const breadcrumbs = ['Moderation', { label: 'Review moderation', to: '..' }, 'Review']

  if (state.loading && !state.detail) {
    return (
      <AdminPage title="Review" breadcrumbs={breadcrumbs}>
        <p className="rm-muted" role="status">Loading the review…</p>
      </AdminPage>
    )
  }
  if (state.error) {
    return (
      <AdminPage title="Review" breadcrumbs={breadcrumbs}>
        <PageError title="Could not load this review" message={state.error} onRetry={load} />
        <Link to=".." relative="path" className="rm-back">‹ Back to queue</Link>
      </AdminPage>
    )
  }

  const { review, openReports = [], history = [] } = state.detail
  const badge = reviewStatusBadge(review.status)
  const actions = availableActions(review)
  const showActions = canEdit && (actions.publish || actions.remove)

  return (
    <AdminPage
      title="Review"
      subtitle={`${review.providerName ?? 'Provider'} · booking ${review.bookingId}`}
      breadcrumbs={breadcrumbs}
      plain
      actions={
        showActions ? (
          <>
            {actions.remove && (
              <Button variant="destructive" onClick={() => setDialog({ kind: 'REMOVE', loading: false, error: null })}>Remove</Button>
            )}
            {actions.publish && (
              <Button variant="primary" onClick={() => setDialog({ kind: actions.publish, loading: false, error: null })}>
                {actions.publish === 'KEEP' ? 'Keep review' : 'Publish'}
              </Button>
            )}
          </>
        ) : null
      }
    >
      <Link to=".." relative="path" className="rm-back">‹ Back to queue</Link>

      {notice && (
        <p className="sc-callout sc-callout--warning" role="alert">
          <CompletionIcon name="alert" />
          <span>{notice}</span>
        </p>
      )}

      <div className="rm-grid">
        <div className="rm-main">
          <section className="rm-card" aria-labelledby="rm-review-title">
            <div className="rm-card-head">
              <h2 id="rm-review-title">Review</h2>
              <Badge variant={badge.variant} size="small">{badge.label}</Badge>
            </div>
            <div className="rm-meta">
              <strong>{review.customerName ?? 'Customer'}</strong>
              <span className="rm-muted">{formatDateTime(review.createdAt)}{review.edited ? ' · edited' : ''}</span>
            </div>
            <StarRatingDisplay value={review.rating} />
            {review.comment ? <HighlightedText text={review.comment} /> : <p className="rm-muted"><em>No comment</em></p>}
            {review.moderationFlags?.length > 0 && (
              <ul className="rm-flags" aria-label="Automatic check results">
                {review.moderationFlags.map((flag) => (
                  <li key={flag}>
                    <Badge variant="warning" size="small">{flagLabel(flag)}</Badge> {flagMessage(flag)}
                  </li>
                ))}
              </ul>
            )}
            {review.removalReason && <p className="rm-muted">Removed: {review.removalReason}</p>}
            {review.providerReply?.text && (
              <blockquote className="rm-reply"><strong>Provider reply:</strong> {review.providerReply.text}</blockquote>
            )}
          </section>

          <section className="rm-card" aria-labelledby="rm-reports-title">
            <h2 id="rm-reports-title">User reports ({openReports.length})</h2>
            {openReports.length === 0 ? (
              <p className="rm-muted">No open reports.</p>
            ) : (
              <ul className="rm-list">
                {openReports.map((report) => (
                  <li key={report.id}>
                    <Badge variant="error" size="small">{reportReasonLabel(report.reason)}</Badge>{' '}
                    <span className="rm-muted">by a {report.reporterRole === 'PROVIDER' ? 'provider' : 'customer'} · {formatDateTime(report.createdAt)}</span>
                    {report.details && <p className="rm-text">{report.details}</p>}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>

        <aside className="rm-side">
          <section className="rm-card" aria-labelledby="rm-context-title">
            <h2 id="rm-context-title">Context</h2>
            <dl className="rm-dl">
              <dt>Booking</dt><dd>{review.bookingId}</dd>
              <dt>Provider</dt><dd>{review.providerName ?? review.providerId}</dd>
              <dt>Customer</dt><dd>{review.customerName ?? review.customerId}</dd>
              <dt>Submitted</dt><dd>{formatDateTime(review.createdAt)}</dd>
            </dl>
          </section>

          <section className="rm-card" aria-labelledby="rm-history-title">
            <h2 id="rm-history-title">History</h2>
            {history.length === 0 ? (
              <p className="rm-muted">No decisions yet.</p>
            ) : (
              <ol className="rm-list">
                {history.map((entry, index) => (
                  <li key={`${entry.action}-${entry.at}-${index}`}>
                    <strong>{actionLabel(entry.action)}</strong>
                    <span className="rm-muted"> · {formatDateTime(entry.at)}</span>
                    {entry.reasonCode && <p className="rm-text">Reason: {removalReasonLabel(entry.reasonCode)}</p>}
                    {entry.note && <p className="rm-text">Note: {entry.note}</p>}
                  </li>
                ))}
              </ol>
            )}
          </section>

          {!canEdit && <p className="rm-muted">View only: decisions are made by moderators.</p>}
        </aside>
      </div>

      {dialog.kind && (
        <ModerationDecisionDialog
          key={dialog.kind}
          kind={dialog.kind}
          isOpen
          loading={dialog.loading}
          error={dialog.error}
          onClose={() => setDialog({ kind: null, loading: false, error: null })}
          onSubmit={decide}
        />
      )}
    </AdminPage>
  )
}
