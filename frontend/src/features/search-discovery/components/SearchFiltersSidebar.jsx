/**
 * Filter Sidebar matching the approved design.
 * Fully controlled component connected to parent search state.
 */
export function SearchFiltersSidebar({ filters = {}, onFilterChange, onReset }) {
  return (
    <aside className="filters-sidebar" aria-label="Search Filters">
      <div className="filters-card">
        {/* Header */}
        <div className="filters-header">
          <h2 className="filters-title">Filters</h2>
          <button
            type="button"
            className="filters-reset-btn"
            onClick={onReset}
          >
            Reset
          </button>
        </div>

        {/* Section 1: Service Type */}
        <div className="filter-section">
          <h3 className="filter-section-title">Service type</h3>
          <div className="filter-options-group">
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={Boolean(filters.standardClean)}
                onChange={(e) => onFilterChange?.('standardClean', e.target.checked)}
              />
              <span>Standard home clean</span>
            </label>
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={Boolean(filters.deepClean)}
                onChange={(e) => onFilterChange?.('deepClean', e.target.checked)}
              />
              <span>Deep cleaning</span>
            </label>
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={Boolean(filters.moveOutClean)}
                onChange={(e) => onFilterChange?.('moveOutClean', e.target.checked)}
              />
              <span>Move-out cleaning</span>
            </label>
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={Boolean(filters.recurringClean)}
                onChange={(e) => onFilterChange?.('recurringClean', e.target.checked)}
              />
              <span>Recurring service</span>
            </label>
          </div>
        </div>

        {/* Section 2: Hourly Budget */}
        <div className="filter-section">
          <h3 className="filter-section-title">Hourly budget</h3>
          <div className="budget-range-limits">
            <span>$30</span>
            <span>$80</span>
          </div>
          <div className="budget-slider-wrapper">
            <input
              type="range"
              min="30"
              max="80"
              step="5"
              value={filters.budgetMax || 60}
              onChange={(e) => onFilterChange?.('budgetMax', Number(e.target.value))}
              className="budget-range-slider"
              aria-label="Hourly budget upper limit"
            />
          </div>
          <div className="budget-selected-text">
            Selected: $30–${filters.budgetMax || 60} per hour
          </div>
        </div>

        {/* Section 3: Availability */}
        <div className="filter-section">
          <h3 className="filter-section-title">Availability</h3>
          <div className="filter-options-group">
            <label className="filter-radio-label">
              <input
                type="radio"
                name="availability"
                value="any"
                className="filter-radio"
                checked={filters.availability === 'any'}
                onChange={() => onFilterChange?.('availability', 'any')}
              />
              <span>Any date</span>
            </label>
            <label className="filter-radio-label">
              <input
                type="radio"
                name="availability"
                value="today"
                className="filter-radio"
                checked={filters.availability === 'today'}
                onChange={() => onFilterChange?.('availability', 'today')}
              />
              <span>Today</span>
            </label>
            <label className="filter-radio-label">
              <input
                type="radio"
                name="availability"
                value="tomorrow"
                className="filter-radio"
                checked={filters.availability === 'tomorrow'}
                onChange={() => onFilterChange?.('availability', 'tomorrow')}
              />
              <span>Tomorrow</span>
            </label>
            <label className="filter-radio-label filter-option-pending">
              <input
                type="radio"
                name="availability"
                value="custom"
                className="filter-radio"
                checked={filters.availability === 'custom'}
                onChange={() => onFilterChange?.('availability', 'custom')}
                disabled
              />
              <span>
                Choose a date <span className="filter-pending-tag">Coming soon</span>
              </span>
            </label>
          </div>
        </div>

        {/* Section 4: Trust & Quality */}
        <div className="filter-section">
          <h3 className="filter-section-title">Trust & quality</h3>
          <div className="filter-options-group">
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={Boolean(filters.verifiedOnly)}
                onChange={(e) => onFilterChange?.('verifiedOnly', e.target.checked)}
              />
              <span>Verified providers</span>
            </label>
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={Boolean(filters.highRating)}
                onChange={(e) => onFilterChange?.('highRating', e.target.checked)}
              />
              <span>4.5 stars & above</span>
            </label>
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={Boolean(filters.backgroundChecked)}
                onChange={(e) => onFilterChange?.('backgroundChecked', e.target.checked)}
              />
              <span>Background checked</span>
            </label>
          </div>
        </div>

        {/* Section 5: Distance */}
        <div className="filter-section filter-section-last">
          <h3 className="filter-section-title">Distance</h3>
          <div className="distance-select-wrapper">
            <select
              className="distance-select"
              value={filters.distance || '5'}
              onChange={(e) => onFilterChange?.('distance', e.target.value)}
              aria-label="Distance limit"
            >
              <option value="5">Within 5 miles ⌄</option>
              <option value="10">Within 10 miles ⌄</option>
              <option value="25">Within 25 miles ⌄</option>
            </select>
          </div>
        </div>
      </div>
    </aside>
  );
}

export default SearchFiltersSidebar;
