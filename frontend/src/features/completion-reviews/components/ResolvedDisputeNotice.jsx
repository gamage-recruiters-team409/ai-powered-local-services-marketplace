import CompletionIcon from './CompletionIcons'
import { formatDateTime } from '../utils/completionUtils'

/**
 * Shown on the completion card after an admin resolved the dispute (WP09).
 * dispute: { id, status: 'RESOLVED', resolvedAt, outcome, canAppeal, url }
 * "Open appeal" links to the Dispute Details screen, where the appeal is reviewed by a different admin.
 */
export default function ResolvedDisputeNotice({ dispute }) {
  const resolvedOn = dispute.resolvedAt ? ` on ${formatDateTime(dispute.resolvedAt).replace(' · ', ' at ')}` : ''
  return (
    <>
      <p className="sc-callout sc-callout--info">
        <CompletionIcon name="info" />
        <span>
          <strong>The dispute for this booking{dispute.id ? ` (${dispute.id})` : ''} was resolved{resolvedOn}.</strong>
          {dispute.outcome ? ` Decision: ${dispute.outcome}` : ''}
        </span>
      </p>
      {dispute.canAppeal && (
        <p className="sc-muted">
          If you disagree with the decision, you can open an appeal. It will be reviewed by a different admin.
        </p>
      )}
      {dispute.url && (
        <div className="sc-actions">
          {dispute.canAppeal ? (
            <a className="shared-btn shared-btn--primary shared-btn--medium sc-link-btn" href={dispute.url}>
              Open appeal <CompletionIcon name="chevronRight" size={16} />
            </a>
          ) : (
            <a className="shared-btn shared-btn--outline shared-btn--medium sc-link-btn" href={dispute.url}>
              View dispute <CompletionIcon name="chevronRight" size={16} />
            </a>
          )}
        </div>
      )}
    </>
  )
}
