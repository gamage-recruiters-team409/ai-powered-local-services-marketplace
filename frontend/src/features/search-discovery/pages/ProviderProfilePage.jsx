import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';

import {
  EmptyState,
  PageError,
  PageLoader,
} from '../../../shared/components/feedback/index.js';
import FeaturePendingModal from '../components/FeaturePendingModal';
import LocalServeFooter from '../components/LocalServeFooter';
import LocalServeNavbar from '../components/LocalServeNavbar';
import { FALLBACK_PROVIDER_IMAGE } from '../fixtures/providerImages.js';
import {
  fetchProviderProfile,
} from '../services/providerProfileAdapter.js';
import { HIGH_RATING_THRESHOLD } from '../services/searchAdapter.js';
import {
  NOT_PROVIDED,
  formatCancellationPolicy,
  formatDistanceArea,
  formatInclusions,
  formatNextAvailable,
  formatPrice,
  formatProviderLocation,
  formatRating,
  formatResponseTime,
  formatReviewCount,
  formatVerification,
} from '../utils/providerComparison.js';
import {
  getProviderAvailabilityState,
  getProviderReviewState,
  getProviderServiceOptions,
  getRatingStarStates,
  normalizeProfileTab,
} from '../utils/providerProfile.js';
import '../styles/search-discovery.css';
import '../styles/provider-profile.css';

const BOOKING_PENDING_ACTION = {
  badgeText: 'MILESTONE BOUNDARY',
  title: 'Booking is not available yet',
  message:
    'Provider booking and scheduling will be connected in a later milestone. You can still review services, pricing, and next availability.',
};

const MESSAGE_PENDING_ACTION = {
  badgeText: 'MILESTONE BOUNDARY',
  title: 'Messaging is not available yet',
  message:
    'Direct messaging will be connected in a later milestone. For now, review the provider details and request booking when it becomes available.',
};

function RatingStars({ rating }) {
  const stars = getRatingStarStates(rating);

  if (stars.length === 0) {
    return null;
  }

  return (
    <span
      className="provider-profile-rating-stars"
      data-testid="provider-rating-stars"
      data-rating={rating}
      aria-hidden="true"
    >
      {stars.map((star, index) => (
        <span
          key={index}
          className="provider-profile-star"
          data-testid="provider-rating-star"
          data-state={star.state}
          data-fill-percent={star.fillPercent}
        >
          <span className="provider-profile-star-empty">★</span>
          {star.fillPercent > 0 && (
            <span
              className="provider-profile-star-fill"
              style={{ width: `${star.fillPercent}%` }}
            >
              ★
            </span>
          )}
        </span>
      ))}
    </span>
  );
}

function ProfileHeader({ provider }) {
  const handleImageError = (event) => {
    const image = event.currentTarget;

    if (image.dataset.fallbackApplied) {
      return;
    }

    image.dataset.fallbackApplied = 'true';
    image.src = FALLBACK_PROVIDER_IMAGE;
  };

  const showTopRated =
    typeof provider.rating === 'number' &&
    provider.rating >= HIGH_RATING_THRESHOLD;
  const hasTrustBadges = provider.isVerified || provider.backgroundChecked;
  const ratingLabel =
    typeof provider.rating === 'number'
      ? `Rated ${provider.rating} out of 5${
          typeof provider.reviewCount === 'number'
            ? ` from ${provider.reviewCount} reviews`
            : ''
        }`
      : 'Rating not provided';

  return (
    <section
      className="provider-profile-hero"
      aria-labelledby="provider-profile-name"
    >
      <div className="provider-profile-hero-media">
        <img
          src={provider.image || FALLBACK_PROVIDER_IMAGE}
          alt={`${provider.name} service presentation`}
          className="provider-profile-hero-image"
          onError={handleImageError}
        />
      </div>

      <div className="provider-profile-hero-copy">
        <div className="provider-profile-title-block">
          <h1
            id="provider-profile-name"
            className="provider-profile-name"
          >
            {provider.name}
          </h1>

          <p className="provider-profile-meta">
            {provider.category && <span>{provider.category}</span>}
            {provider.category && (
              <span className="provider-profile-meta-dot" aria-hidden="true">
                ·
              </span>
            )}
            <span>{formatProviderLocation(provider.location)}</span>
            {provider.distanceText && (
              <>
                <span className="provider-profile-meta-dot" aria-hidden="true">
                  ·
                </span>
                <span>{provider.distanceText}</span>
              </>
            )}
          </p>
        </div>

        <div
          className="provider-profile-badges"
          aria-label="Provider verification"
        >
          {provider.isVerified && (
            <span className="provider-profile-badge provider-profile-badge-verified">
              <span aria-hidden="true">✓</span>
              Identity verified
            </span>
          )}
          {provider.backgroundChecked && (
            <span className="provider-profile-badge provider-profile-badge-verified">
              <span aria-hidden="true">✓</span>
              Background checked
            </span>
          )}
          {showTopRated && (
            <span className="provider-profile-badge provider-profile-badge-top">
              Top rated
            </span>
          )}
          {!hasTrustBadges && (
            <span className="provider-profile-badge provider-profile-badge-muted">
              {formatVerification(provider)}
            </span>
          )}
        </div>

        <div
          className="provider-profile-rating-row"
          aria-label={ratingLabel}
        >
          {typeof provider.rating === 'number' ? (
            <>
              <span className="provider-profile-rating-score">
                {formatRating(provider.rating)}
              </span>
              <RatingStars rating={provider.rating} />
              <span className="provider-profile-rating-count">
                {formatReviewCount(provider.reviewCount)}
              </span>
            </>
          ) : (
            <span className="provider-profile-muted">
              Rating not provided
            </span>
          )}
        </div>
      </div>
    </section>
  );
}

function ProfileTabs({ activeTab, buildTabHref, reviewCount }) {
  const tabs = [
    { key: 'overview', label: 'Overview' },
    { key: 'services', label: 'Services / Packages' },
    {
      key: 'reviews',
      label:
        typeof reviewCount === 'number' && reviewCount > 0
          ? `Reviews (${reviewCount})`
          : 'Reviews',
    },
    { key: 'availability', label: 'Availability' },
  ];

  return (
    <nav
      className="provider-profile-tabs"
      aria-label="Provider profile sections"
    >
      {tabs.map((tab) => {
        const isActive = tab.key === activeTab;

        return (
          <Link
            key={tab.key}
            to={buildTabHref(tab.key)}
            className={`provider-profile-tab${
              isActive ? ' provider-profile-tab-active' : ''
            }`}
            aria-current={isActive ? 'page' : undefined}
          >
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}

function BookingCard({ provider, onBook, onMessage }) {
  return (
    <aside
      className="provider-profile-booking"
      aria-labelledby="provider-profile-booking-heading"
    >
      <h2
        id="provider-profile-booking-heading"
        className="provider-profile-booking-heading"
      >
        Booking summary
      </h2>
      <span className="provider-profile-kicker">Starting rate</span>
      <p className="provider-profile-booking-price">
        {formatPrice(provider.pricing)}
      </p>

      <div className="provider-profile-booking-availability">
        <span>Next available</span>
        <strong>
          {provider.nextAvailable
            ? formatNextAvailable(provider.nextAvailable)
            : 'No availability provided'}
        </strong>
      </div>

      <div className="provider-profile-booking-actions">
        <button
          type="button"
          className="provider-profile-primary-btn"
          onClick={onBook}
        >
          Request booking
        </button>
        <button
          type="button"
          className="provider-profile-secondary-btn"
          onClick={onMessage}
        >
          Message provider
        </button>
      </div>

      <p className="provider-profile-booking-note">
        Booking and messaging are not connected yet.
      </p>

      <dl className="provider-profile-facts">
        <div>
          <dt>Verification</dt>
          <dd>{formatVerification(provider)}</dd>
        </div>
        <div>
          <dt>Response time</dt>
          <dd>{formatResponseTime(provider.responseTimeText)}</dd>
        </div>
        <div>
          <dt>Service area</dt>
          <dd>{formatDistanceArea(provider)}</dd>
        </div>
        <div>
          <dt>What&apos;s included</dt>
          <dd>{formatInclusions(provider.inclusions)}</dd>
        </div>
        <div>
          <dt>Cancellation</dt>
          <dd>{formatCancellationPolicy(provider.cancellationPolicy)}</dd>
        </div>
      </dl>
    </aside>
  );
}

function OverviewPanel({
  provider,
  serviceOptions,
  reviewState,
  buildTabHref,
}) {
  const serviceLabels = serviceOptions
    .map((service) => service.label)
    .join(', ');
  const reviewSummary =
    typeof provider.rating === 'number'
      ? `${formatRating(provider.rating)}${
          typeof provider.reviewCount === 'number'
            ? ` from ${formatReviewCount(provider.reviewCount)}`
            : ''
        }`
      : 'Reviews are not provided for this provider.';

  return (
    <section
      className="provider-profile-panel"
      aria-labelledby="provider-profile-about-heading"
    >
      <h2
        id="provider-profile-about-heading"
        className="provider-profile-panel-title"
      >
        About {provider.name}
      </h2>
      <p className="provider-profile-about-text">
        {provider.description || 'No provider description has been provided.'}
      </p>

      <div className="provider-profile-info-grid">
        <div className="provider-profile-info-card">
          <h3>Services offered</h3>
          <p>{serviceLabels || 'Not provided'}</p>
        </div>
        <div className="provider-profile-info-card">
          <h3>What&apos;s included</h3>
          <p>{formatInclusions(provider.inclusions)}</p>
        </div>
        <div className="provider-profile-info-card">
          <h3>Service area</h3>
          <p>{formatDistanceArea(provider)}</p>
        </div>
        <div className="provider-profile-info-card">
          <h3>Cancellation policy</h3>
          <p>{formatCancellationPolicy(provider.cancellationPolicy)}</p>
        </div>
      </div>

      <div className="provider-profile-review-preview">
        <div>
          <h3>Reviews</h3>
          <p>{reviewSummary}</p>
        </div>
        <Link
          to={buildTabHref('reviews')}
          className="provider-profile-text-link"
        >
          Read reviews
        </Link>
      </div>

      {reviewState.reviewCount === 0 && (
        <p className="provider-profile-unavailable-note" role="status">
          {reviewState.message}
        </p>
      )}
    </section>
  );
}

function ServicesPanel({ provider, serviceOptions, onBook }) {
  const providerPrice = formatPrice(provider.pricing);
  const providerInclusions = formatInclusions(provider.inclusions);

  return (
    <section
      className="provider-profile-panel"
      aria-labelledby="provider-profile-services-heading"
    >
      <h2
        id="provider-profile-services-heading"
        className="provider-profile-panel-title"
      >
        Services &amp; packages
      </h2>
      <p className="provider-profile-panel-intro">
        Choose the care your home needs. Provider-level rates are shown
        separately from package-specific details.
      </p>

      <div className="provider-profile-provider-summary">
        <div>
          <span className="provider-profile-kicker">
            Provider starting rate
          </span>
          <strong>{providerPrice}</strong>
        </div>
        <div>
          <span className="provider-profile-kicker">
            Provider-level inclusions
          </span>
          <span>{providerInclusions}</span>
        </div>
      </div>

      <p className="provider-profile-panel-footnote">
        Package-specific prices and inclusions are shown only when supplied by
        the provider.
      </p>

      <div className="provider-profile-service-list">
        {serviceOptions.map((service) => {
          const servicePrice = formatPrice(service.pricing);
          const serviceInclusions = formatInclusions(service.inclusions);
          const hasServicePrice = servicePrice !== NOT_PROVIDED;
          const hasServiceInclusions = serviceInclusions !== NOT_PROVIDED;

          return (
            <article
              key={service.key}
              className="provider-profile-service-card"
            >
              <div className="provider-profile-service-copy">
                <h3>{service.label}</h3>
                {service.description ? (
                  <p>{service.description}</p>
                ) : (
                  <p className="provider-profile-service-unavailable">
                    Package description not provided.
                  </p>
                )}
                {hasServiceInclusions ? (
                  <p className="provider-profile-service-includes">
                    Package includes: {serviceInclusions}
                  </p>
                ) : (
                  <p className="provider-profile-service-unavailable">
                    Package-specific inclusions not provided.
                  </p>
                )}
              </div>

              <div className="provider-profile-service-action">
                <span className="provider-profile-service-price">
                  {hasServicePrice
                    ? servicePrice
                    : 'Package price not provided'}
                </span>
                <button
                  type="button"
                  className="provider-profile-secondary-btn"
                  onClick={onBook}
                >
                  Request {service.label}
                </button>
              </div>
            </article>
          );
        })}
      </div>

      <p className="provider-profile-panel-footnote">
        Provider starting rates are indicative. Package-specific details are
        confirmed before booking.
      </p>
    </section>
  );
}

function ReviewsPanel({ provider, reviewState }) {
  return (
    <section
      className="provider-profile-panel"
      aria-labelledby="provider-profile-reviews-heading"
    >
      <h2
        id="provider-profile-reviews-heading"
        className="provider-profile-panel-title"
      >
        What customers are saying
      </h2>

      <div className="provider-profile-review-summary">
        <div>
          <span className="provider-profile-kicker">Overall rating</span>
          <p className="provider-profile-review-score">
            {formatRating(provider.rating)}
          </p>
          <p className="provider-profile-review-count">
            {formatReviewCount(provider.reviewCount)}
          </p>
        </div>
      </div>

      {reviewState.hasReviewContent ? (
        <ul className="provider-profile-review-list">
          {reviewState.reviews.map((review, index) => (
            <li
              key={review.id ?? index}
              className="provider-profile-review-item"
            >
              <div className="provider-profile-review-meta">
                <strong>{review.authorName || 'Customer'}</strong>
                {typeof review.rating === 'number' && (
                  <span>{formatRating(review.rating)}</span>
                )}
              </div>
              {review.text && <p>{review.text}</p>}
            </li>
          ))}
        </ul>
      ) : (
        <p className="provider-profile-unavailable-note" role="status">
          {reviewState.message}
        </p>
      )}
    </section>
  );
}

function AvailabilityPanel({ availability, onBook }) {
  return (
    <section
      className="provider-profile-panel"
      aria-labelledby="provider-profile-availability-heading"
    >
      <h2
        id="provider-profile-availability-heading"
        className="provider-profile-panel-title"
      >
        Find a time that works
      </h2>

      <div className="provider-profile-availability-card">
        <div>
          <span className="provider-profile-kicker">Next available</span>
          <p className="provider-profile-availability-time">
            {availability.nextAvailable
              ? formatNextAvailable(availability.nextAvailable)
              : 'No availability provided'}
          </p>
        </div>
        <span className="provider-profile-status-pill">Preview only</span>
      </div>

      {availability.hasSlotPreview ? (
        <ul className="provider-profile-slot-list">
          {availability.slots.map((slot, index) => (
            <li
              key={slot?.id ?? index}
              className="provider-profile-slot"
            >
              {typeof slot === 'string'
                ? slot
                : slot?.label || slot?.startTime || 'Available slot'}
            </li>
          ))}
        </ul>
      ) : (
        <p className="provider-profile-unavailable-note" role="status">
          {availability.message}
        </p>
      )}

      <div className="provider-profile-panel-actions">
        <button
          type="button"
          className="provider-profile-primary-btn"
          onClick={onBook}
        >
          Request booking
        </button>
      </div>

      <p className="provider-profile-panel-footnote">
        Availability is confirmed before a booking is accepted.
      </p>
    </section>
  );
}

/**
 * Public provider profile page.
 *
 * Fixture mode is explicit. In live mode the adapter reports that public
 * profiles are unavailable, so this page never presents sample data as live.
 */
export function ProviderProfilePage() {
  const { providerId } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const activeTab = normalizeProfileTab(searchParams.get('tab'));

  const [profileState, setProfileState] = useState({
    providerId: null,
    status: 'loading',
    provider: null,
    isFixture: false,
    error: null,
  });
  const [retryNonce, setRetryNonce] = useState(0);
  const [pendingAction, setPendingAction] = useState(null);
  const pendingTriggerRef = useRef(null);

  useEffect(() => {
    let isCancelled = false;

    async function loadProfile() {
      try {
        const data = await fetchProviderProfile(providerId);

        if (!isCancelled) {
          setProfileState({
            providerId,
            status: 'ready',
            provider: data.provider,
            isFixture: Boolean(data.isFixture),
            error: null,
          });
        }
      } catch (error) {
        if (isCancelled) {
          return;
        }

        if (error?.code === 'PROVIDER_PROFILE_NOT_FOUND') {
          setProfileState({
            providerId,
            status: 'not-found',
            provider: null,
            isFixture: false,
            error: null,
          });
        } else if (error?.code === 'PROVIDER_PROFILE_UNAVAILABLE') {
          setProfileState({
            providerId,
            status: 'unavailable',
            provider: null,
            isFixture: false,
            error: null,
          });
        } else {
          console.error('[ProviderProfilePage] Provider profile error:', error);
          setProfileState({
            providerId,
            status: 'error',
            provider: null,
            isFixture: false,
            error,
          });
        }
      }
    }

    loadProfile();

    return () => {
      isCancelled = true;
    };
  }, [providerId, retryNonce]);

  useEffect(() => {
    if (typeof window !== 'undefined' && window.scrollY > 0) {
      window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
    }
  }, [providerId]);

  const isCurrentProfile = profileState.providerId === providerId;
  const viewStatus = isCurrentProfile ? profileState.status : 'loading';
  const provider = isCurrentProfile ? profileState.provider : null;
  const isFixture = isCurrentProfile ? profileState.isFixture : false;

  const buildTabHref = (tab) => {
    const params = new URLSearchParams(searchParams);

    if (tab === 'overview') {
      params.delete('tab');
    } else {
      params.set('tab', tab);
    }

    const queryString = params.toString();
    const path = `/providers/${encodeURIComponent(providerId ?? '')}`;

    return queryString ? `${path}?${queryString}` : path;
  };

  const handleBookingClick = (event) => {
    pendingTriggerRef.current = event.currentTarget;
    setPendingAction(BOOKING_PENDING_ACTION);
  };

  const handleMessageClick = (event) => {
    pendingTriggerRef.current = event.currentTarget;
    setPendingAction(MESSAGE_PENDING_ACTION);
  };

  const handleClosePending = () => setPendingAction(null);

  const handleRetry = () => {
    setProfileState({
      providerId,
      status: 'loading',
      provider: null,
      isFixture: false,
      error: null,
    });
    setRetryNonce((nonce) => nonce + 1);
  };

  const serviceOptions = provider
    ? getProviderServiceOptions(provider)
    : [];
  const reviewState = provider
    ? getProviderReviewState(provider)
    : { reviewCount: null, reviews: [], hasReviewContent: false, message: '' };
  const availability = provider
    ? getProviderAvailabilityState(provider)
    : { nextAvailable: null, slots: [], hasSlotPreview: false, message: '' };

  return (
    <div className="localserve-page-wrapper">
      <LocalServeNavbar />

      <main className="localserve-main-content" id="main-content">
        <div className="localserve-content-container">
          {viewStatus === 'loading' && (
            <PageLoader label="Loading provider profile..." />
          )}

          {viewStatus === 'not-found' && (
            <div className="provider-profile-state">
              <EmptyState
                variant="search"
                title="Provider not found"
                message={`We couldn't find a provider with ID "${providerId}". It may have been removed or the link may be incorrect.`}
                actionLabel="Back to search"
                onAction={() => navigate('/search')}
              />
            </div>
          )}

          {viewStatus === 'unavailable' && (
            <div className="provider-profile-state">
              <EmptyState
                variant="search"
                title="Provider profiles are not available yet"
                message="Live public provider profiles are not connected. Please check back soon."
                actionLabel="Back to search"
                onAction={() => navigate('/search')}
              />
            </div>
          )}

          {viewStatus === 'error' && (
            <div className="provider-profile-state">
              <PageError
                title="Could not load provider profile"
                message="We could not load this provider profile. Please try again."
                onRetry={handleRetry}
              />
              <p className="provider-profile-state-link">
                <Link to="/search">Back to search</Link>
              </p>
            </div>
          )}

          {viewStatus === 'ready' && provider && (
            <>
              {isFixture && (
                <p
                  className="search-demo-notice provider-profile-demo-notice"
                  role="status"
                >
                  Demo mode: this provider profile uses sample data while live
                  public profiles are not connected.
                </p>
              )}

              <nav
                className="search-breadcrumbs"
                aria-label="Breadcrumb"
              >
                <Link to="/">Home</Link>
                <span className="breadcrumb-separator" aria-hidden="true">
                  /
                </span>
                <Link to="/search">Find Services</Link>
                {provider.category && (
                  <>
                    <span className="breadcrumb-separator" aria-hidden="true">
                      /
                    </span>
                    <span>{provider.category}</span>
                  </>
                )}
                <span className="breadcrumb-separator" aria-hidden="true">
                  /
                </span>
                <span className="breadcrumb-current" aria-current="page">
                  {provider.name}
                </span>
              </nav>

              <ProfileHeader provider={provider} />

              <ProfileTabs
                activeTab={activeTab}
                buildTabHref={buildTabHref}
                reviewCount={provider.reviewCount}
              />

              <div className="provider-profile-layout">
                <div className="provider-profile-content">
                  {activeTab === 'overview' && (
                    <OverviewPanel
                      provider={provider}
                      serviceOptions={serviceOptions}
                      reviewState={reviewState}
                      buildTabHref={buildTabHref}
                    />
                  )}

                  {activeTab === 'services' && (
                    <ServicesPanel
                      provider={provider}
                      serviceOptions={serviceOptions}
                      onBook={handleBookingClick}
                    />
                  )}

                  {activeTab === 'reviews' && (
                    <ReviewsPanel
                      provider={provider}
                      reviewState={reviewState}
                    />
                  )}

                  {activeTab === 'availability' && (
                    <AvailabilityPanel
                      availability={availability}
                      onBook={handleBookingClick}
                    />
                  )}
                </div>

                <div className="provider-profile-sidebar">
                  <BookingCard
                    provider={provider}
                    onBook={handleBookingClick}
                    onMessage={handleMessageClick}
                  />
                </div>
              </div>
            </>
          )}
        </div>
      </main>

      <LocalServeFooter />

      <FeaturePendingModal
        isOpen={Boolean(pendingAction)}
        title={pendingAction?.title ?? ''}
        message={pendingAction?.message ?? ''}
        badgeText={pendingAction?.badgeText}
        triggerRef={pendingTriggerRef}
        onClose={handleClosePending}
      />
    </div>
  );
}

export default ProviderProfilePage;
