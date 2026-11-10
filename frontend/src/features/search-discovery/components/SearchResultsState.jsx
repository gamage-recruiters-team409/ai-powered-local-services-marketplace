/**
 * Skeleton Loader Card for Search Results matching the reference design layout.
 */
export function SearchResultSkeleton() {
  return (
    <div className="provider-result-card skeleton-card" aria-hidden="true">
      <div className="skeleton-photo skeleton-shimmer" />
      <div className="provider-card-content" style={{ gap: '10px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div className="skeleton-shimmer" style={{ width: '45%', height: '20px', borderRadius: '4px' }} />
          <div className="skeleton-shimmer" style={{ width: '24px', height: '24px', borderRadius: '50%' }} />
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          <div className="skeleton-shimmer" style={{ width: '70px', height: '16px', borderRadius: '4px' }} />
          <div className="skeleton-shimmer" style={{ width: '100px', height: '16px', borderRadius: '4px' }} />
        </div>
        <div className="skeleton-shimmer" style={{ width: '90%', height: '14px', borderRadius: '4px' }} />
        <div className="skeleton-shimmer" style={{ width: '50%', height: '14px', borderRadius: '4px' }} />
        <div className="skeleton-shimmer" style={{ width: '60%', height: '16px', borderRadius: '4px' }} />
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '12px' }}>
          <div className="skeleton-shimmer" style={{ width: '100px', height: '24px', borderRadius: '4px' }} />
          <div className="skeleton-shimmer" style={{ width: '110px', height: '36px', borderRadius: '8px' }} />
        </div>
      </div>
    </div>
  );
}

/**
 * Empty Search State with Recovery Action matching the approved theme.
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
      <h2 className="empty-state-title">No matching providers found</h2>
      <p className="empty-state-desc">
        {query ? (
          <>
            We couldn&rsquo;t find any service providers matching &ldquo;<strong>{query}</strong>&rdquo;. Try searching for &ldquo;Home cleaning&rdquo; or adjusting your filters.
          </>
        ) : (
          'There are currently no providers matching your filter criteria.'
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
 * Error State with Retry Action matching the approved theme.
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
          <h3 className="error-title">Unable to Load Providers</h3>
          <p className="error-message">
            {error || 'An unexpected error occurred while fetching providers. Please try again.'}
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
