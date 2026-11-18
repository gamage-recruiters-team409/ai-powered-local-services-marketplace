import { useId } from 'react';

/**
 * Comparison Bottom Banner matching the approved design.
 * Displays count of selected providers, selected names, and action button.
 *
 * Comparison needs at least 2 providers (FR-CMP-003/UC-CMP-01), so the action
 * stays disabled until the shortlist reaches the minimum.
 */
export function SearchComparisonBanner({ selectedProviders = [], onCompare }) {
  const helpTextId = useId();
  const count = selectedProviders.length;

  if (count === 0) return null;

  const canCompare = count >= 2;
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
          disabled={!canCompare}
          aria-describedby={helpTextId}
          aria-haspopup="dialog"
        >
          Compare ({count})
        </button>
      </div>
      <div className="comparison-help-text" id={helpTextId}>
        {canCompare
          ? 'Select up to 3 providers to compare.'
          : 'Select at least 2 providers to compare.'}
      </div>
    </div>
  );
}

export default SearchComparisonBanner;
