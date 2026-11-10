/**
 * Skeleton Loader Card for Search Results.
 */
export function SearchResultSkeleton() {
  return (
    <div className="skeleton-card" aria-hidden="true">
      <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
        <div className="skeleton-shimmer skeleton-avatar" />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '6px' }}>
          <div className="skeleton-shimmer skeleton-title" style={{ width: '50%' }} />
          <div className="skeleton-shimmer skeleton-text" style={{ width: '35%' }} />
        </div>
      </div>
      <div className="skeleton-shimmer skeleton-tag" />
      <div className="skeleton-shimmer skeleton-title" />
      <div className="skeleton-shimmer skeleton-text" />
      <div className="skeleton-shimmer skeleton-text" style={{ width: '70%' }} />
      <div style={{ display: 'flex', gap: '6px', marginTop: '6px' }}>
        <div className="skeleton-shimmer skeleton-tag" />
        <div className="skeleton-shimmer skeleton-tag" />
      </div>
      <div style={{ borderTop: '1px solid var(--border)', paddingTop: '12px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div className="skeleton-shimmer skeleton-title" style={{ width: '30%', height: '24px' }} />
        <div className="skeleton-shimmer skeleton-btn" />
      </div>
    </div>
  );
}

/**
 * Empty Search State with Recovery Action.
 */
export function SearchEmptyState({ query, onReset }) {
  return (
    <section className="empty-state-card" aria-label="No search results">
      <svg
        className="empty-state-icon"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <circle cx="11" cy="11" r="8" />
        <line x1="21" y1="21" x2="16.65" y2="16.65" />
        <line x1="8" y1="11" x2="14" y2="11" />
      </svg>
      <h2 className="empty-state-title">No Matching Services Found</h2>
      <p className="empty-state-desc">
        {query ? (
          <>
            We couldn&rsquo;t find any service matching &ldquo;<strong>{query}</strong>&rdquo;. Try searching for general terms like <em>Electrical</em>, <em>Plumbing</em>, or <em>Cleaning</em>.
          </>
        ) : (
          'There are currently no listings matching your search criteria.'
        )}
      </p>
      {onReset && (
        <button type="button" className="empty-reset-btn" onClick={onReset}>
          Reset Search
        </button>
      )}
    </section>
  );
}

/**
 * Error State with Retry Action.
 */
export function SearchErrorState({ error, onRetry }) {
  return (
    <section className="error-state-card" role="alert" aria-live="assertive">
      <div className="error-text-group">
        <svg
          className="error-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
        <div>
          <h3 className="error-title">Unable to Load Services</h3>
          <p className="error-message">
            {error || 'An unexpected error occurred while fetching services. Please try again.'}
          </p>
        </div>
      </div>
      {onRetry && (
        <button type="button" className="error-retry-btn" onClick={onRetry}>
          Retry Search
        </button>
      )}
    </section>
  );
}
