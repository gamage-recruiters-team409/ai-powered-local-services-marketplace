import { useState } from 'react';

/**
 * Reusable Provider & Listing Search Result Card matching the approved reference design.
 * Features photo, verification badge, rating, description, response metrics,
 * next availability, hourly pricing, comparison checkbox, and "View profile" CTA.
 */
export function SearchResultCard({ item, isSelected = false, onSelect, onToggleComparison }) {
  const [isFavorited, setIsFavorited] = useState(false);

  if (!item) return null;

  const {
    id,
    name,
    image,
    isVerified,
    rating,
    reviewCount,
    description,
    distanceText,
    responseTimeText,
    nextAvailable,
    pricing = {},
  } = item;

  return (
    <article className="provider-result-card" data-testid={`provider-card-${id}`}>
      {/* Provider Photo */}
      <div className="provider-card-photo-wrapper">
        <img
          src={image}
          alt={`${name} service presentation`}
          className="provider-card-photo"
          loading="lazy"
        />
      </div>

      {/* Provider Content */}
      <div className="provider-card-content">
        {/* Header: Name & Favorite */}
        <div className="provider-card-top-row">
          <h3 className="provider-card-title">{name}</h3>
          <button
            type="button"
            className={`favorite-btn ${isFavorited ? 'favorite-btn-active' : ''}`}
            onClick={() => setIsFavorited(!isFavorited)}
            aria-label={isFavorited ? `Remove ${name} from favorites` : `Save ${name} to favorites`}
          >
            <svg
              className="heart-icon"
              viewBox="0 0 24 24"
              fill={isFavorited ? '#ef4444' : 'none'}
              stroke={isFavorited ? '#ef4444' : 'currentColor'}
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" />
            </svg>
          </button>
        </div>

        {/* Verification & Rating */}
        <div className="provider-badges-row">
          {isVerified && (
            <span className="verified-badge" title="Verified Provider">
              <svg
                className="verified-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                <polyline points="22 4 12 14.01 9 11.01" />
              </svg>
              <span>Verified</span>
            </span>
          )}

          {typeof rating === 'number' && (
            <span className="rating-summary" aria-label={`Rated ${rating} out of 5 stars`}>
              <span className="rating-star" aria-hidden="true">★</span>
              <span className="rating-score">{rating.toFixed(1)}</span>
              {reviewCount != null && (
                <span className="rating-count">({reviewCount} reviews)</span>
              )}
            </span>
          )}
        </div>

        {/* Description */}
        <p className="provider-card-description">{description}</p>

        {/* Distance & Response Time */}
        {(distanceText || responseTimeText) && (
          <div className="provider-meta-row">
            {distanceText && <span>{distanceText}</span>}
            {distanceText && responseTimeText && <span className="meta-dot">·</span>}
            {responseTimeText && <span>{responseTimeText}</span>}
          </div>
        )}

        {/* Availability Pill */}
        {nextAvailable && (
          <div className="provider-availability-row">
            <svg
              className="calendar-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
              <line x1="16" y1="2" x2="16" y2="6" />
              <line x1="8" y1="2" x2="8" y2="6" />
              <line x1="3" y1="10" x2="21" y2="10" />
            </svg>
            <span>Next available: {nextAvailable}</span>
          </div>
        )}

        {/* Pricing & CTA */}
        <div className="provider-card-footer-row">
          <div className="provider-pricing-block">
            <div className="pricing-primary">
              From <strong className="pricing-rate">{pricing.currency || '$'}{pricing.hourlyRate || 45}</strong>/{pricing.unit || 'hr'}
            </div>
            {pricing.minimumHours && (
              <div className="pricing-secondary">{pricing.minimumHours}-hour minimum</div>
            )}
          </div>

          <button
            type="button"
            className="view-profile-btn"
            onClick={(e) => onSelect?.(item, e.currentTarget)}
            aria-label={`View profile for ${name}`}
          >
            View profile
          </button>
        </div>

        {/* Comparison Checkbox */}
        <div className="provider-compare-row">
          <label className="compare-checkbox-label">
            <input
              type="checkbox"
              className="compare-checkbox"
              checked={isSelected}
              onChange={(e) => onToggleComparison?.(item, e.target.checked, e.target)}
            />
            <span>{isSelected ? 'Added to comparison' : 'Add to comparison'}</span>
          </label>
        </div>
      </div>
    </article>
  );
}

export default SearchResultCard;
