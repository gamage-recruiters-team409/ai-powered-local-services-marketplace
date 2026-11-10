/**
 * Reusable Provider & Listing Search Result Card.
 * Displays provider verification, service metadata, ratings, location,
 * pricing, and tags in a responsive card format.
 */
export function SearchResultCard({ item, onSelect }) {
  if (!item) return null;

  const {
    id,
    title,
    category,
    provider = {},
    location,
    pricing = {},
    description,
    tags = [],
  } = item;

  const formattedPrice = pricing.startingPrice
    ? `${pricing.currency || 'LKR'} ${Number(pricing.startingPrice).toLocaleString()}`
    : 'Custom Quote';

  const priceUnitText = pricing.priceUnit ? ` / ${pricing.priceUnit}` : '';

  return (
    <article className="result-card" data-testid={`search-result-${id}`}>
      <div>
        {/* Provider Profile Header */}
        <header className="card-provider-header">
          <div className="provider-avatar" aria-hidden="true">
            {provider.avatar || provider.name?.substring(0, 2).toUpperCase() || 'SP'}
          </div>
          <div className="provider-details">
            <div className="provider-name-row">
              <span className="provider-name">{provider.name}</span>
              {provider.isVerified && (
                <span
                  className="verified-badge"
                  title="Verified Service Provider"
                  aria-label="Verified Service Provider"
                >
                  ✓
                </span>
              )}
            </div>
            {provider.businessName && (
              <div className="provider-business">{provider.businessName}</div>
            )}
          </div>
          {typeof provider.rating === 'number' && (
            <div className="card-rating-badge" aria-label={`Rated ${provider.rating} stars`}>
              <span className="star-icon" aria-hidden="true">★</span>
              <span>{provider.rating.toFixed(1)}</span>
              {provider.reviewCount != null && (
                <span className="review-count">({provider.reviewCount})</span>
              )}
            </div>
          )}
        </header>

        {/* Service Details */}
        <div className="card-category-tag">{category}</div>
        <h2 className="card-service-title">{title}</h2>
        <p className="card-description">{description}</p>

        {/* Location Info */}
        {location && (
          <div className="card-location-row">
            <svg
              className="location-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
              <circle cx="12" cy="10" r="3" />
            </svg>
            <span>{location}</span>
          </div>
        )}

        {/* Tags */}
        {tags.length > 0 && (
          <div className="card-tags" aria-label="Service tags">
            {tags.map((tag, idx) => (
              <span key={idx} className="card-tag">
                {tag}
              </span>
            ))}
          </div>
        )}
      </div>

      {/* Footer / Pricing & Actions */}
      <footer className="card-footer">
        <div className="card-pricing-block">
          <span className="pricing-label">Starting at</span>
          <div>
            <span className="pricing-amount">{formattedPrice}</span>
            <span className="pricing-unit">{priceUnitText}</span>
          </div>
        </div>
        <button
          type="button"
          className="card-action-btn"
          onClick={() => onSelect?.(item)}
          aria-label={`View service details for ${title}`}
        >
          View Service
        </button>
      </footer>
    </article>
  );
}

export default SearchResultCard;
