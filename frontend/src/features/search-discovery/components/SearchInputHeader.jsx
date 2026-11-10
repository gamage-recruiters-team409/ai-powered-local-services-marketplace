import { useState } from 'react';

/**
 * Search Bar, Breadcrumbs, Title Header, and Active Filter Chips
 * matching the approved LocalServe design.
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
}) {
  const [activeChipClean, setActiveChipClean] = useState(true);
  const [activeChipVerified, setActiveChipVerified] = useState(true);

  const handleSubmit = (e) => {
    e.preventDefault();
    onSearch?.({ query, location });
  };

  const handleClearAll = () => {
    setActiveChipClean(false);
    setActiveChipVerified(false);
    onQueryChange?.('');
    onLocationChange?.('');
    onSearch?.({ query: '', location: '' });
  };

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
            {totalCount || 18} local providers · Prices shown before booking
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
      {(activeChipClean || activeChipVerified) && (
        <div className="active-chips-row" aria-label="Active filters">
          {activeChipClean && (
            <button
              type="button"
              className="filter-chip filter-chip-active"
              onClick={() => setActiveChipClean(false)}
              aria-label="Remove Standard clean filter"
            >
              Standard clean <span className="chip-x">✕</span>
            </button>
          )}

          {activeChipVerified && (
            <button
              type="button"
              className="filter-chip"
              onClick={() => setActiveChipVerified(false)}
              aria-label="Remove Verified filter"
            >
              Verified <span className="chip-x">✕</span>
            </button>
          )}

          <button
            type="button"
            className="filter-chip-clear"
            onClick={handleClearAll}
          >
            Clear all
          </button>
        </div>
      )}
    </div>
  );
}

export default SearchInputHeader;
