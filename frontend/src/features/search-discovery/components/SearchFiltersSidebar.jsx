import { useState } from 'react';

/**
 * Filter Sidebar matching the approved design.
 * Includes Service Type, Hourly Budget slider, Availability,
 * Trust & Quality, and Distance controls.
 */
export function SearchFiltersSidebar({ onReset }) {
  const [standardClean, setStandardClean] = useState(true);
  const [deepClean, setDeepClean] = useState(false);
  const [moveOutClean, setMoveOutClean] = useState(false);
  const [recurringClean, setRecurringClean] = useState(false);

  const [budgetMax, setBudgetMax] = useState(60);
  const [availability, setAvailability] = useState('any');
  const [verifiedOnly, setVerifiedOnly] = useState(true);
  const [highRating, setHighRating] = useState(false);
  const [backgroundChecked, setBackgroundChecked] = useState(false);
  const [distance, setDistance] = useState('5');

  const handleResetFilters = () => {
    setStandardClean(true);
    setDeepClean(false);
    setMoveOutClean(false);
    setRecurringClean(false);
    setBudgetMax(60);
    setAvailability('any');
    setVerifiedOnly(true);
    setHighRating(false);
    setBackgroundChecked(false);
    setDistance('5');
    onReset?.();
  };

  return (
    <aside className="filters-sidebar" aria-label="Search Filters">
      <div className="filters-card">
        {/* Header */}
        <div className="filters-header">
          <h2 className="filters-title">Filters</h2>
          <button
            type="button"
            className="filters-reset-btn"
            onClick={handleResetFilters}
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
                checked={standardClean}
                onChange={(e) => setStandardClean(e.target.checked)}
              />
              <span>Standard home clean</span>
            </label>
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={deepClean}
                onChange={(e) => setDeepClean(e.target.checked)}
              />
              <span>Deep cleaning</span>
            </label>
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={moveOutClean}
                onChange={(e) => setMoveOutClean(e.target.checked)}
              />
              <span>Move-out cleaning</span>
            </label>
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={recurringClean}
                onChange={(e) => setRecurringClean(e.target.checked)}
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
              value={budgetMax}
              onChange={(e) => setBudgetMax(Number(e.target.value))}
              className="budget-range-slider"
              aria-label="Hourly budget upper limit"
            />
          </div>
          <div className="budget-selected-text">
            Selected: $30–${budgetMax} per hour
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
                checked={availability === 'any'}
                onChange={() => setAvailability('any')}
              />
              <span>Any date</span>
            </label>
            <label className="filter-radio-label">
              <input
                type="radio"
                name="availability"
                value="today"
                className="filter-radio"
                checked={availability === 'today'}
                onChange={() => setAvailability('today')}
              />
              <span>Today</span>
            </label>
            <label className="filter-radio-label">
              <input
                type="radio"
                name="availability"
                value="tomorrow"
                className="filter-radio"
                checked={availability === 'tomorrow'}
                onChange={() => setAvailability('tomorrow')}
              />
              <span>Tomorrow</span>
            </label>
            <label className="filter-radio-label">
              <input
                type="radio"
                name="availability"
                value="custom"
                className="filter-radio"
                checked={availability === 'custom'}
                onChange={() => setAvailability('custom')}
              />
              <span>Choose a date</span>
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
                checked={verifiedOnly}
                onChange={(e) => setVerifiedOnly(e.target.checked)}
              />
              <span>Verified providers</span>
            </label>
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={highRating}
                onChange={(e) => setHighRating(e.target.checked)}
              />
              <span>4.5 stars & above</span>
            </label>
            <label className="filter-checkbox-label">
              <input
                type="checkbox"
                className="filter-checkbox"
                checked={backgroundChecked}
                onChange={(e) => setBackgroundChecked(e.target.checked)}
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
              value={distance}
              onChange={(e) => setDistance(e.target.value)}
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

