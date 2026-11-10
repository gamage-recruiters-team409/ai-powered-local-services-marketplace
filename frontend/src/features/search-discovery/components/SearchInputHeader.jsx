/**
 * Search Input and Dynamic Results Heading Component.
 * Supports keyboard submission, clear button, and accessible labels.
 */
export function SearchInputHeader({
  query,
  onQueryChange,
  onSearch,
  totalCount,
  isLoading,
  isLive,
}) {
  const handleSubmit = (e) => {
    e.preventDefault();
    onSearch?.(query);
  };

  const handleClear = () => {
    onQueryChange?.('');
    onSearch?.('');
  };

  return (
    <header className="search-header">
      <div className="search-header-headline">
        <h1>Find Trusted Local Services</h1>
        <p>Connect with verified trade professionals and service specialists across Sri Lanka</p>
      </div>

      <form className="search-input-wrapper" onSubmit={handleSubmit} role="search">
        <div className="search-input-box">
          <svg
            className="search-input-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <input
            type="search"
            className="search-input-field"
            placeholder="Search by service, trade, location, or provider (e.g. Electrical, Colombo, AC)..."
            value={query}
            onChange={(e) => onQueryChange?.(e.target.value)}
            aria-label="Search services"
          />
          {query && (
            <button
              type="button"
              className="search-clear-btn"
              onClick={handleClear}
              aria-label="Clear search query"
            >
              ✕
            </button>
          )}
        </div>
        <button type="submit" className="search-submit-btn" disabled={isLoading}>
          {isLoading ? 'Searching...' : 'Search Services'}
        </button>
      </form>

      {/* Dynamic Results Heading Bar */}
      <div className="results-summary-bar">
        <div className="results-count-text">
          {isLoading ? (
            <span>Updating results...</span>
          ) : query ? (
            <span>
              Found <span className="results-count-highlight">{totalCount}</span> {totalCount === 1 ? 'service' : 'services'} for &ldquo;
              <span className="results-count-highlight">{query}</span>&rdquo;
            </span>
          ) : (
            <span>
              Showing <span className="results-count-highlight">{totalCount}</span> available verified services
            </span>
          )}
        </div>

        <div className="results-pending-badge" title="Adapter status">
          {isLive ? 'Live API Connected' : 'Fixture Adapter (Live Search Pending)'}
        </div>
      </div>
    </header>
  );
}

export default SearchInputHeader;
