/**
 * Search Bar, Breadcrumbs, Title Header, and Active Filter Chips
 * matching the approved LocalServe design.
 * Fully synchronized with parent filter state and dynamic singular/plural count.
 */
export function SearchInputHeader({
  query,
  location,
  onQueryChange,
  onLocationChange,
  onSearch,
  totalCount,
  sortBy,
  onSortChange,
  filters = {},
  onToggleFilter,
  onClearAll,
}) {
  const handleSubmit = (e) => {
    e.preventDefault();
    onSearch?.({ query, location });
  };

  const formatCount = (count) => {
    if (count === 1) return '1 local provider';
    return `${count} local providers`;
  };

  const hasActiveChips = filters.standardClean || filters.verifiedOnly;

  return (
    <div className="search-header-section">
      {/* Search Input Bar Card */}
      <form className="search-bar-card" onSubmit={handleSubmit} role="search">
        <div className="search-field-group">
          <label className="search-field-label" htmlFor="search-service-input">
            WHAT DO YOU NEED?
          </label>
          <input
            id="search-service-input"
            type="text"
            className="search-field-input"
            value={query}
            onChange={(e) => onQueryChange?.(e.target.value)}
            placeholder="Home cleaning"
          />
        </div>

        <div className="search-field-divider" aria-hidden="true" />

        <div className="search-field-group">
          <label className="search-field-label" htmlFor="search-location-input">
            WHERE?
          </label>
          <input
            id="search-location-input"
            type="text"
            className="search-field-input"
            value={location}
            onChange={(e) => onLocationChange?.(e.target.value)}
            placeholder="Austin, TX 78701"
          />
        </div>

        <button type="submit" className="search-bar-btn">
          Search
        </button>
      </form>

      {/* Breadcrumbs */}
      <nav className="search-breadcrumbs" aria-label="Breadcrumbs">
        <a href="/">Home</a>
        <span className="breadcrumb-separator">/</span>
        <a href="/search">Home cleaning</a>
        <span className="breadcrumb-separator">/</span>
        <span className="breadcrumb-current">Austin, TX</span>
      </nav>

      {/* Header Title & Sort Bar */}
      <div className="search-title-bar">
        <div>
          <h1 className="search-title">Home cleaning in Austin</h1>
          <p className="search-subtitle">
            {formatCount(totalCount)} · Prices shown before booking
          </p>
        </div>

        <div className="search-sort-wrapper">
          <label htmlFor="search-sort-select" className="search-sort-label">
            Sort:
          </label>
          <select
            id="search-sort-select"
            className="search-sort-select"
            value={sortBy}
            onChange={(e) => onSortChange?.(e.target.value)}
          >
            <option value="recommended">Recommended ⌄</option>
            <option value="rating">Highest Rated</option>
            <option value="price_asc">Price: Low to High</option>
            <option value="price_desc">Price: High to Low</option>
          </select>
        </div>
      </div>

      {/* Active Filter Chips */}
      {hasActiveChips && (
        <div className="active-chips-row" aria-label="Active filters">
          {filters.standardClean && (
            <button
              type="button"
              className="filter-chip filter-chip-active"
              onClick={() => onToggleFilter?.('standardClean', false)}
              aria-label="Remove Standard clean filter"
            >
              Standard clean <span className="chip-x">✕</span>
            </button>
          )}

          {filters.verifiedOnly && (
            <button
              type="button"
              className="filter-chip"
              onClick={() => onToggleFilter?.('verifiedOnly', false)}
              aria-label="Remove Verified filter"
            >
              Verified <span className="chip-x">✕</span>
            </button>
          )}

          <button
            type="button"
            className="filter-chip-clear"
            onClick={onClearAll}
          >
            Clear all
          </button>
        </div>
      )}
    </div>
  );
}

export default SearchInputHeader;
