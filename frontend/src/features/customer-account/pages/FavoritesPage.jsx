import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { CustomerPage } from '../../../shared/layouts/customer/CustomerPage';
import { useToast } from '../../../shared/components/feedback/useToast';
import { getFavourites, removeFavourite } from '../services/favouritesApi';
import { StatusBadge } from '../components/StatusBadge';
import { Pagination } from '../components/Pagination';
import { EmptyState } from '../components/EmptyState';
import styles from '../customerAccount.module.css';

function formatPrice(amount) {
  if (amount == null) return null;
  const num = Number(amount);
  if (isNaN(num)) return String(amount);
  return num.toLocaleString('en-US');
}

export function FavoritesPage() {
  const toast = useToast();
  const [activeTab, setActiveTab] = useState('PROVIDER');
  const [page, setPage] = useState(0);
  const [reloadCounter, setReloadCounter] = useState(0);
  const pageSize = 6;
  const currentKey = `${activeTab}:${page}:${reloadCounter}`;

  const [result, setResult] = useState({
    key: null,
    data: null,
    error: null,
    listingsUnavailable: false,
  });
  const [removedIds, setRemovedIds] = useState(new Set());

  const loading = result.key !== currentKey;
  const rawContent = result.data?.content || [];
  const favourites = rawContent.filter((item) => !removedIds.has(item.favouriteId));
  const totalPages = result.data?.totalPages ?? 0;
  const totalElements = Math.max(0, (result.data?.totalElements ?? 0) - removedIds.size);
  const listingsUnavailable = result.listingsUnavailable;
  const errorMessage = result.error ? (result.error.message || 'Failed to load favorites') : '';

  const start = totalElements === 0 ? 0 : page * pageSize + 1;
  const end = Math.min(page * pageSize + favourites.length, totalElements);

  useEffect(() => {
    let cancelled = false;
    getFavourites(activeTab, page, pageSize)
      .then((data) => {
        if (!cancelled) {
          const content = data?.content || [];
          const totalElems = data?.totalElements ?? 0;
          const totalPgs = data?.totalPages ?? 0;

          if (content.length === 0 && totalElems > 0 && page > 0) {
            const lastValidPage = Math.max(0, totalPgs - 1);
            if (lastValidPage !== page) {
              setPage(lastValidPage);
              return;
            }
          }

          setRemovedIds(new Set());
          setResult({
            key: `${activeTab}:${page}:${reloadCounter}`,
            data,
            error: null,
            listingsUnavailable: false,
          });
        }
      })
      .catch((err) => {
        if (!cancelled) {
          const isListing503 =
            activeTab === 'LISTING' &&
            (err.status === 503 || err.code === 'FAVOURITES_LISTING_UNAVAILABLE');
          setResult({
            key: `${activeTab}:${page}:${reloadCounter}`,
            data: null,
            error: isListing503 ? null : err,
            listingsUnavailable: isListing503,
          });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [activeTab, page, pageSize, reloadCounter]);

  const handleTabChange = (newTab) => {
    if (newTab !== activeTab) {
      setActiveTab(newTab);
      setPage(0);
      setRemovedIds(new Set());
    }
  };

  const handlePageChange = (newPage) => {
    setPage(newPage);
    setRemovedIds(new Set());
  };

  const handleRemove = async (fav) => {
    // Optimistic update
    setRemovedIds((prev) => new Set(prev).add(fav.favouriteId));

    try {
      await removeFavourite(fav.type, fav.targetId);
      toast.success('Removed from favorites');
      setReloadCounter((c) => c + 1);
    } catch (err) {
      // Rollback on error
      setRemovedIds((prev) => {
        const next = new Set(prev);
        next.delete(fav.favouriteId);
        return next;
      });
      toast.error(err.message || 'Failed to remove from favorites');
    }
  };

  const headerActions = (
    <div className={styles.tabToggle} role="tablist" aria-label="Favorites type">
      <button
        type="button"
        role="tab"
        aria-selected={activeTab === 'PROVIDER'}
        className={`${styles.tabBtn} ${activeTab === 'PROVIDER' ? styles.tabBtnActive : ''}`}
        onClick={() => handleTabChange('PROVIDER')}
      >
        Providers
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={activeTab === 'LISTING'}
        className={`${styles.tabBtn} ${activeTab === 'LISTING' ? styles.tabBtnActive : ''}`}
        onClick={() => handleTabChange('LISTING')}
      >
        Listings
      </button>
    </div>
  );

  return (
    <CustomerPage title="Favorites" actions={headerActions}>
      <div className={styles.accountContainer}>
        <div>
          <p className={styles.pageHeaderSubtitle} style={{ marginTop: '-12px', marginBottom: '20px' }}>
            Providers and services you saved.
          </p>
        </div>

        {errorMessage && (
          <div className={styles.alertWarning} role="alert" style={{ background: '#fef2f2', borderColor: '#fecaca', color: '#dc2626' }}>
            {errorMessage}
          </div>
        )}

        {loading ? (
          <div className={styles.card} style={{ minHeight: '200px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <p className={styles.inputHint}>Loading favorites...</p>
          </div>
        ) : listingsUnavailable ? (
          <div className={styles.card}>
            <EmptyState
              icon={
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
              }
              title="Listings are not available yet"
              description="Service listing favourites will be enabled once service catalog listings become available. You can still save and view provider favourites."
            />
          </div>
        ) : totalElements === 0 ? (
          <div className={styles.card}>
            <EmptyState
              icon={
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z" />
                </svg>
              }
              title="No favorites yet"
              description="Tap the heart on a provider or service to save it here."
              actionLabel="Browse services"
              actionTo="/search"
            />
          </div>
        ) : (
          <div>
            <div className={styles.cardGrid}>
              {favourites.map((fav) => {
                const isInactive = Boolean(fav.inactive);

                return (
                  <div
                    key={fav.favouriteId}
                    className={`${styles.favCard} ${isInactive ? styles.favCardInactive : ''}`}
                  >
                    <div className={styles.favCardImageWrapper}>
                      {fav.imageUrl ? (
                        <img
                          src={fav.imageUrl}
                          alt={fav.name}
                          className={styles.favCardImage}
                        />
                      ) : (
                        <div
                          style={{
                            width: '100%',
                            height: '100%',
                            background: '#f1f5f9',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            color: '#94a3b8',
                          }}
                        >
                          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                            <rect width="18" height="18" x="3" y="3" rx="2" ry="2" />
                            <circle cx="9" cy="9" r="2" />
                            <path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21" />
                          </svg>
                        </div>
                      )}

                      {isInactive && (
                        <div className={styles.favCardBadgeTopLeft}>
                          <StatusBadge variant="muted">Inactive</StatusBadge>
                        </div>
                      )}

                      <button
                        type="button"
                        className={styles.favCardHeartBtn}
                        onClick={() => handleRemove(fav)}
                        aria-label={`Remove ${fav.name} from favorites`}
                      >
                        <svg
                          width="20"
                          height="20"
                          viewBox="0 0 24 24"
                          fill="#0f766e"
                          stroke="#0f766e"
                          strokeWidth="2"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                        >
                          <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z" />
                        </svg>
                      </button>
                    </div>

                    <div className={styles.favCardBody}>
                      <h3 className={styles.favCardTitle}>{fav.name}</h3>

                      {fav.subtitle && (
                        <p className={styles.favCardSubtitle}>by {fav.subtitle}</p>
                      )}

                      {fav.category && (
                        <div style={{ marginBottom: '8px' }}>
                          <span
                            style={{
                              display: 'inline-block',
                              padding: '2px 8px',
                              background: '#f1f5f9',
                              color: '#475569',
                              borderRadius: '4px',
                              fontSize: '11px',
                              fontWeight: 500,
                            }}
                          >
                            {fav.category}
                          </span>
                        </div>
                      )}

                      {fav.rating != null && (
                        <div className={styles.favCardRatingRow}>
                          <span className={styles.favCardStar}>★</span>
                          <span>{Number(fav.rating).toFixed(1)}</span>
                          {fav.reviewCount != null && (
                            <span className={styles.favCardReviews}>
                              ({fav.reviewCount} reviews)
                            </span>
                          )}
                        </div>
                      )}

                      {fav.city && (
                        <div className={styles.favCardLocation}>
                          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                            <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z" />
                            <circle cx="12" cy="10" r="3" />
                          </svg>
                          <span>{fav.city}</span>
                        </div>
                      )}

                      {fav.priceFrom != null && (
                        <div className={styles.favCardPrice}>
                          {fav.type === 'PROVIDER' ? 'From ' : ''}
                          {fav.currency || 'LKR'} {formatPrice(fav.priceFrom)}
                          {fav.priceUnit && (
                            <span className={styles.favCardPriceUnit}> / {fav.priceUnit}</span>
                          )}
                        </div>
                      )}

                      {fav.type === 'PROVIDER' ? (
                        <Link
                          to={`/providers/${fav.targetId}`}
                          className={styles.btnSecondary}
                          data-testid="Link_ViewProfile"
                          style={{ width: '100%', marginTop: 'auto' }}
                        >
                          View profile
                        </Link>
                      ) : (
                        <button
                          type="button"
                          className={styles.btnPrimary}
                          disabled
                          aria-disabled="true"
                          title={isInactive ? 'This listing is inactive' : 'Booking is not available yet'}
                          style={{ width: '100%', marginTop: 'auto' }}
                        >
                          Book now
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>

            <Pagination
              currentPage={page}
              totalPages={totalPages}
              totalElements={totalElements}
              pageSize={pageSize}
              start={start}
              end={end}
              itemLabel="favorites"
              onPageChange={(newPage) => handlePageChange(newPage)}
            />
          </div>
        )}
      </div>
    </CustomerPage>
  );
}

export default FavoritesPage;
