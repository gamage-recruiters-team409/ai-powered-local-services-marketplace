/**
 * Comparison Bottom Banner matching the approved design.
 * Displays count of selected providers, selected names, and action button.
 */
export function SearchComparisonBanner({ selectedProviders = [], onCompare }) {
  const count = selectedProviders.length;
  if (count === 0) return null;

  const providerNames = selectedProviders.map((p) => p.name).join(' · ');

  return (
    <div className="comparison-banner-wrapper">
      <div className="comparison-banner-card">
        <div className="comparison-banner-info">
          <div className="comparison-banner-title">
            {count} {count === 1 ? 'provider' : 'providers'} selected
          </div>
          <div className="comparison-banner-subtext">
            {providerNames}
          </div>
        </div>

        <button
          type="button"
          className="comparison-banner-btn"
          onClick={(e) => onCompare?.(e.currentTarget)}
        >
          Compare ({count})
        </button>
      </div>
      <div className="comparison-help-text">
        Select up to 3 providers to compare.
      </div>
    </div>
  );
}

export default SearchComparisonBanner;

