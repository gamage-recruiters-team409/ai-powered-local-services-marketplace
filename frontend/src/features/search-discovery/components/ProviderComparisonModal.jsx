import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Modal from '../../../shared/components/forms/Modal';
import { FALLBACK_PROVIDER_IMAGE } from '../fixtures/providerImages.js';
import {
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
  getCommonCategory,
  getDistanceChip,
  getLocationCity,
  getProviderShortName,
} from '../utils/providerComparison.js';

function ComparisonField({ label, children, className = '' }) {
  return (
    <div className={`comparison-field ${className}`.trim()}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

/**
 * Full side-by-side provider comparison.
 *
 * The page owns the selected provider list; this component only renders it and
 * requests removals. Minimum (2) and maximum (3) selection rules stay in the
 * existing search page/banner so there is a single source of truth.
 */
export function ProviderComparisonModal({
  providers = [],
  serviceLabel = '',
  locationLabel = '',
  filters = {},
  onClose,
  onRemove,
}) {
  const removeButtonRefs = useRef(new Map());
  const backButtonRef = useRef(null);
  const emptyStateBackRef = useRef(null);
  const pendingFocusRef = useRef(null);
  const [notice, setNotice] = useState('');

  const count = providers.length;
  const searchLocationCity = getLocationCity(locationLabel);
  const commonCategory = getCommonCategory(providers);
  const distanceChip = getDistanceChip(filters);
  const serviceText = serviceLabel.trim() || commonCategory;

  const description =
    count >= 2
      ? `${count} providers · ${serviceText}${searchLocationCity ? ` in ${searchLocationCity}` : ''} · Choose the best fit for your home.`
      : 'Select at least 2 providers to compare them side by side.';

  const railSubtitle =
    commonCategory === 'Mixed services'
      ? 'Your selected providers, compared side by side.'
      : 'The same service, compared side by side.';

  useEffect(() => {
    const pendingFocus = pendingFocusRef.current;

    if (!pendingFocus) {
      return;
    }

    if (pendingFocus.type === 'remove') {
      const nextRemoveButton = removeButtonRefs.current.get(pendingFocus.id);

      if (nextRemoveButton) {
        nextRemoveButton.focus();
      } else {
        backButtonRef.current?.focus();
      }
    } else {
      emptyStateBackRef.current?.focus();
    }

    pendingFocusRef.current = null;
  }, [providers]);

  const handleClose = () => {
    setNotice('');
    onClose?.();
  };

  const handleRemove = (provider) => {
    const index = providers.findIndex((item) => item.id === provider.id);
    const nextProvider = providers[index + 1] || providers[index - 1];

    setNotice('');
    pendingFocusRef.current = nextProvider
      ? { type: 'remove', id: nextProvider.id }
      : { type: 'empty' };
    onRemove?.(provider.id);
  };

  const setRemoveButtonRef = (providerId) => (node) => {
    if (node) {
      removeButtonRefs.current.set(providerId, node);
    } else {
      removeButtonRefs.current.delete(providerId);
    }
  };

  const handleImageError = (event) => {
    const image = event.currentTarget;

    if (image.dataset.fallbackApplied) {
      return;
    }

    image.dataset.fallbackApplied = 'true';
    image.src = FALLBACK_PROVIDER_IMAGE;
  };

  const footer = (
    <p className="comparison-disclaimer">
      Starting rates are indicative. Confirm your service, duration and final quote with the
      provider before booking.
    </p>
  );

  return (
    <Modal
      isOpen
      onClose={handleClose}
      title="Compare your shortlist"
      description={description}
      closeOnBackdrop
      showCloseButton
      className="comparison-modal-skin"
      footer={footer}
    >
      <div className="comparison-layout">
        <aside className="comparison-rail" aria-label="Shortlist context">
          <button
            type="button"
            className="comparison-back-btn"
            ref={backButtonRef}
            onClick={handleClose}
          >
            <span aria-hidden="true">«</span> Back to results
          </button>

          <h3 className="comparison-rail-title">Your shortlist</h3>
          <p className="comparison-rail-subtitle">{railSubtitle}</p>

          <ul className="comparison-context-chips" aria-label="Comparison context">
            <li className="comparison-context-chip">{commonCategory}</li>
            <li className="comparison-context-chip">{distanceChip}</li>
            <li className="comparison-context-chip">Compare up to 3 providers</li>
          </ul>
        </aside>

        <div className="comparison-content">
          {notice && (
            <p className="comparison-inline-notice" role="status">
              {notice}
            </p>
          )}

          {count < 2 ? (
            <section
              className="comparison-minimum-state"
              aria-labelledby="comparison-minimum-title"
            >
              <h3 id="comparison-minimum-title">
                {count === 0 ? 'No providers selected' : 'Select one more provider'}
              </h3>
              <p>
                {count === 0
                  ? 'Your shortlist is empty. Return to the results to add providers.'
                  : 'Side-by-side comparison needs at least 2 providers. Add another provider from your results.'}
              </p>

              {count === 1 && (
                <ul className="comparison-minimum-list">
                  {providers.map((provider) => (
                    <li className="comparison-minimum-item" key={provider.id}>
                      <span>{provider.name}</span>
                      <button
                        type="button"
                        className="comparison-remove-btn comparison-remove-btn--text"
                        ref={setRemoveButtonRef(provider.id)}
                        onClick={() => handleRemove(provider)}
                        aria-label={`Remove ${provider.name} from comparison`}
                      >
                        Remove
                      </button>
                    </li>
                  ))}
                </ul>
              )}

              <button
                type="button"
                className="comparison-primary-btn comparison-empty-back-btn"
                ref={emptyStateBackRef}
                onClick={handleClose}
              >
                Return to search results
              </button>
            </section>
          ) : (
            <div
              className="comparison-grid"
              style={{ '--provider-count': count }}
            >
              {providers.map((provider) => {
                const providerMeta = [serviceText, formatProviderLocation(provider.location)]
                  .filter(Boolean)
                  .join(' · ');
                const ctaNoteId = `comparison-cta-note-${provider.id}`;

                return (
                  <article
                    className="comparison-column"
                    key={provider.id}
                    aria-labelledby={`comparison-provider-${provider.id}`}
                  >
                    <header className="comparison-column-header">
                      <button
                        type="button"
                        className="comparison-remove-btn"
                        ref={setRemoveButtonRef(provider.id)}
                        onClick={() => handleRemove(provider)}
                        aria-label={`Remove ${provider.name} from comparison`}
                      >
                        <svg
                          width="16"
                          height="16"
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="2"
                          strokeLinecap="round"
                          aria-hidden="true"
                        >
                          <path d="M18 6 6 18" />
                          <path d="m6 6 12 12" />
                        </svg>
                      </button>

                      <img
                        className="comparison-provider-image"
                        src={provider.image || FALLBACK_PROVIDER_IMAGE}
                        alt={`${provider.name} service presentation`}
                        onError={handleImageError}
                      />

                      <h3
                        className="comparison-provider-name"
                        id={`comparison-provider-${provider.id}`}
                      >
                        {provider.name}
                      </h3>
                      <p className="comparison-provider-meta">{providerMeta}</p>
                    </header>

                    <dl className="comparison-fields">
                      <ComparisonField label="Starting price">
                        {formatPrice(provider.pricing)}
                      </ComparisonField>

                      <ComparisonField label="Rating">
                        {formatRating(provider.rating)}
                      </ComparisonField>

                      <ComparisonField label="Customer reviews">
                        {formatReviewCount(provider.reviewCount)}
                      </ComparisonField>

                      <ComparisonField label="Verification">
                        {formatVerification(provider)}
                      </ComparisonField>

                      <ComparisonField label="Response time">
                        {formatResponseTime(provider.responseTimeText)}
                      </ComparisonField>

                      <ComparisonField label="Next available">
                        {formatNextAvailable(provider.nextAvailable)}
                      </ComparisonField>

                      <ComparisonField label="Distance / area">
                        {formatDistanceArea(provider)}
                      </ComparisonField>

                      <ComparisonField label="What’s included">
                        {formatInclusions(provider.inclusions)}
                      </ComparisonField>

                      <ComparisonField label="Cancellation policy">
                        {formatCancellationPolicy(provider.cancellationPolicy)}
                      </ComparisonField>

                      <ComparisonField label="Next step" className="comparison-field--cta">
                        <button
                          type="button"
                          className="comparison-primary-btn"
                          onClick={() =>
                            setNotice('Booking is not available in this milestone.')
                          }
                          aria-describedby={ctaNoteId}
                        >
                          Choose {getProviderShortName(provider.name)}
                        </button>
                        <Link
                          className="comparison-secondary-btn"
                          to={`/providers/${encodeURIComponent(
                            provider.providerId || provider.id,
                          )}`}
                          aria-describedby={ctaNoteId}
                        >
                          View profile
                        </Link>
                        <p className="comparison-cta-note" id={ctaNoteId}>
                          Booking is not available in this milestone. Profile links are
                          available from each result.
                        </p>
                      </ComparisonField>
                    </dl>
                  </article>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
}

export default ProviderComparisonModal;
