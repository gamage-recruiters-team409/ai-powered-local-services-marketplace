import { useState, useEffect, useRef } from 'react';
import { fetchSearchResults, isFixtureModeEnabled } from '../services/searchAdapter';
import LocalServeNavbar from '../components/LocalServeNavbar';
import SearchInputHeader from '../components/SearchInputHeader';
import SearchFiltersSidebar from '../components/SearchFiltersSidebar';
import SearchResultCard from '../components/SearchResultCard';
import SearchComparisonBanner from '../components/SearchComparisonBanner';
import LocalServeFooter from '../components/LocalServeFooter';
import FeaturePendingModal from '../components/FeaturePendingModal';
import ProviderComparisonModal from '../components/ProviderComparisonModal';
import {
  SearchResultSkeleton,
  SearchEmptyState,
  SearchErrorState,
} from '../components/SearchResultsState';
import '../styles/search-discovery.css';

const PAGE_SIZE = 12;

const DEFAULT_FILTERS = {
  standardClean: true,
  deepClean: false,
  moveOutClean: false,
  recurringClean: false,
  verifiedOnly: true,
  budgetMax: 60,
  availability: 'any',
  highRating: false,
  backgroundChecked: false,
  distance: '5',
};

// "Clear all" removes every restriction (as opposed to Reset, which restores
// the default filters). Budget and distance move to their widest values so no
// provider is excluded.
const CLEARED_FILTERS = {
  standardClean: false,
  deepClean: false,
  moveOutClean: false,
  recurringClean: false,
  verifiedOnly: false,
  budgetMax: 80,
  availability: 'any',
  highRating: false,
  backgroundChecked: false,
  distance: '25',
};

const FIXTURE_DEFAULT_QUERY = 'Home cleaning';
const FIXTURE_DEFAULT_LOCATION = 'Austin, TX 78701';
const LIVE_DEFAULT_QUERY = '';
const LIVE_DEFAULT_LOCATION = '';

function getDefaultSearchValues() {
  if (isFixtureModeEnabled()) {
    return { query: FIXTURE_DEFAULT_QUERY, location: FIXTURE_DEFAULT_LOCATION };
  }

  return { query: LIVE_DEFAULT_QUERY, location: LIVE_DEFAULT_LOCATION };
}

/**
 * Main Search Results Page matching the approved LocalServe design.
 * Features full-width responsive header/footer, sidebar filters,
 * provider cards with authentic images, comparison tray, and pagination.
 */
export function SearchResultsPage() {
  const [defaultSearchValues] = useState(getDefaultSearchValues);
  const [query, setQuery] = useState(defaultSearchValues.query);
  const [location, setLocation] = useState(defaultSearchValues.location);
  const [activeQuery, setActiveQuery] = useState(defaultSearchValues.query);
  const [activeLocation, setActiveLocation] = useState(defaultSearchValues.location);
  const [sortBy, setSortBy] = useState('recommended');
  const [filters, setFilters] = useState(DEFAULT_FILTERS);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(PAGE_SIZE);

  const [results, setResults] = useState([]);
  const [totalCount, setTotalCount] = useState(3);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [isFixture, setIsFixture] = useState(false);
  const [isLive, setIsLive] = useState(false);
  const [unsupportedFilters, setUnsupportedFilters] = useState([]);
  const [selectedProviders, setSelectedProviders] = useState([]);
  // Incremented by every explicit Search / Retry / Reset action so the request
  // effect runs even when the query and filters are unchanged.
  const [searchNonce, setSearchNonce] = useState(0);

  // Selection limit notice modal
  const [modalState, setModalState] = useState({
    isOpen: false,
    title: '',
    message: '',
    badgeText: '',
  });
  // Ref to the element that last opened the modal; focus is restored on close.
  const modalTriggerRef = useRef(null);

  // Comparison dialog state. The trigger and a stable fallback are kept so
  // focus can return somewhere sensible if the shortlist becomes empty.
  const [isComparisonOpen, setIsComparisonOpen] = useState(false);
  const comparisonTriggerRef = useRef(null);
  const resultsSectionRef = useRef(null);
  const wasComparisonOpenRef = useRef(false);

  useEffect(() => {
    if (wasComparisonOpenRef.current && !isComparisonOpen) {
      const trigger = comparisonTriggerRef.current;

      if (trigger?.isConnected && !trigger.disabled) {
        trigger.focus();
      } else {
        resultsSectionRef.current?.focus();
      }
    }

    wasComparisonOpenRef.current = isComparisonOpen;
  }, [isComparisonOpen]);

  useEffect(() => {
    let isCancelled = false;

    async function loadData() {
      setIsLoading(true);
      setError(null);
      try {
        const data = await fetchSearchResults({
          query: activeQuery,
          location: activeLocation,
          filters,
          sortBy,
          page,
          size: pageSize,
        });
        if (!isCancelled) {
          const items = data.results || [];
          const responseSize =
            Number.isInteger(data.size) && data.size > 0 ? data.size : pageSize;
          setResults(items);
          setTotalCount(data.totalCount ?? items.length);
          setPageSize(responseSize);
          setIsFixture(Boolean(data.isFixture));
          setIsLive(Boolean(data.isLive));
          setUnsupportedFilters(
            Array.isArray(data.unsupportedFilters) ? data.unsupportedFilters : [],
          );

          // Seed default comparison on initial load
          setSelectedProviders((prev) => {
            if (prev.length === 0) {
              return items.filter((item) => item.inComparison);
            }
            return prev;
          });
          setError(null);
        }
      } catch (err) {
        if (!isCancelled) {
          console.error('[SearchResultsPage] Search error:', err);
          // A failed request must not leave results from a previous successful
          // search visible as if they were the current result set.
          setResults([]);
          setTotalCount(0);
          setSelectedProviders([]);
          setIsFixture(false);
          setIsLive(false);
          setUnsupportedFilters([]);
          setError('Failed to fetch provider listings. Please try again.');
        }
      } finally {
        if (!isCancelled) {
          setIsLoading(false);
        }
      }
    }

    loadData();

    return () => {
      isCancelled = true;
    };
  }, [activeQuery, activeLocation, filters, sortBy, page, pageSize, searchNonce]);

  const handleSearchSubmit = ({ query: newQuery, location: newLocation }) => {
    setIsLoading(true);
    setPage(0);
    setActiveQuery(newQuery);
    setActiveLocation(newLocation);
    setSearchNonce((n) => n + 1);
  };

  const handleFilterChange = (key, value) => {
    setIsLoading(true);
    setPage(0);
    setFilters((prev) => ({
      ...prev,
      [key]: value,
    }));
  };

  const handleSortChange = (value) => {
    setIsLoading(true);
    setPage(0);
    setSortBy(value);
  };

  const handleClearAllChips = () => {
    setIsLoading(true);
    setPage(0);
    setFilters(CLEARED_FILTERS);
    setSearchNonce((n) => n + 1);
  };

  const handleResetAll = () => {
    const defaults = getDefaultSearchValues();
    setQuery(defaults.query);
    setLocation(defaults.location);
    setIsLoading(true);
    setPage(0);
    setActiveQuery(defaults.query);
    setActiveLocation(defaults.location);
    setSortBy('recommended');
    setFilters(DEFAULT_FILTERS);
    setSearchNonce((n) => n + 1);
  };

  const totalPages = Math.ceil(totalCount / pageSize);

  const handlePageChange = (nextPage) => {
    if (
      nextPage < 0 ||
      nextPage >= totalPages ||
      nextPage === page ||
      isLoading
    ) {
      return;
    }

    setIsLoading(true);
    setPage(nextPage);
  };

  const handleToggleComparison = (provider, isAdding, triggerEl) => {
    if (isAdding) {
      if (selectedProviders.some((p) => p.id === provider.id)) return;
      if (selectedProviders.length >= 3) {
        modalTriggerRef.current = triggerEl ?? null;
        setModalState({
          isOpen: true,
          badgeText: 'LIMIT REACHED',
          title: 'Maximum 3 Providers Allowed',
          message:
            'You can compare a maximum of 3 providers side-by-side. Please deselect one of your existing selections before adding another.',
        });
        return;
      }
      setSelectedProviders((prev) => [...prev, provider]);
    } else {
      setSelectedProviders((prev) => prev.filter((p) => p.id !== provider.id));
    }
  };

  const handleCompareClick = (triggerEl) => {
    if (selectedProviders.length < 2) {
      return;
    }

    comparisonTriggerRef.current = triggerEl ?? null;
    setIsComparisonOpen(true);
  };

  const handleCloseComparison = () => {
    setIsComparisonOpen(false);
  };

  const handleRemoveComparisonProvider = (providerId) => {
    setSelectedProviders((prev) => prev.filter((provider) => provider.id !== providerId));
  };

  const formatPaginationText = () => {
    if (!totalCount) return 'Showing 0 of 0 providers';
    if (results.length === 0) return `Showing 0 of ${totalCount} providers`;
    if (results.length === 1 && page === 0 && totalCount === 1) {
      return 'Showing 1 of 1 provider';
    }

    const start = page * pageSize + 1;
    const end = Math.min(start + results.length - 1, totalCount);
    const noun = totalCount === 1 ? 'provider' : 'providers';

    return `Showing ${start}–${end} of ${totalCount} ${noun}`;
  };

  const hasActiveUnsupportedFilters =
    filters.highRating ||
    filters.backgroundChecked ||
    (filters.distance != null && String(filters.distance).trim() !== '') ||
    Boolean(filters.availability && filters.availability !== 'any') ||
    Boolean(
      filters.standardClean ||
        filters.deepClean ||
        filters.moveOutClean ||
        filters.recurringClean,
    );
  const showUnsupportedNotice =
    isLive &&
    (unsupportedFilters.length > 0 || hasActiveUnsupportedFilters || sortBy === 'rating');

  return (
    <div className="localserve-page-wrapper">
      {/* Top Navigation */}
      <LocalServeNavbar />

      {/* Main Body */}
      <main className="localserve-main-content" id="main-content">
        <div className="localserve-content-container">
          {/* Search Inputs, Breadcrumbs, Title & Active Chips */}
          <SearchInputHeader
            query={query}
            location={location}
            onQueryChange={setQuery}
            onLocationChange={setLocation}
            onSearch={handleSearchSubmit}
            totalCount={totalCount}
            sortBy={sortBy}
            onSortChange={handleSortChange}
            filters={filters}
            onToggleFilter={handleFilterChange}
            onClearAll={handleClearAllChips}
          />

          {/* Two-Column Search Layout */}
          <div className="search-layout-grid">
            {/* Left Column: Filters Sidebar */}
            <SearchFiltersSidebar
              filters={filters}
              onFilterChange={handleFilterChange}
              onReset={handleResetAll}
            />

            {/* Right Column: Cards, States, Comparison & Pagination */}
            <section
              className="search-results-column"
              aria-label="Search results list"
              ref={resultsSectionRef}
              tabIndex={-1}
            >
              {isFixture && (
                <p className="search-demo-notice" role="status">
                  Demo mode: showing sample providers while live search is not connected.
                </p>
              )}

              {showUnsupportedNotice && (
                <p className="search-unsupported-notice" role="status">
                  Live search does not enforce availability, distance, background check,
                  service type, or rating filters. Results may not reflect these preferences.
                  {sortBy === 'rating' && ' Highest-rated sorting is also not supported.'}
                </p>
              )}

              {error && (
                <SearchErrorState
                  error={error}
                  onRetry={() => {
                    setIsLoading(true);
                    setSearchNonce((n) => n + 1);
                  }}
                />
              )}

              {!error && (isLoading ? (
                <div className="results-cards-list" aria-busy="true">
                  {Array.from({ length: 3 }).map((_, idx) => (
                    <SearchResultSkeleton key={idx} />
                  ))}
                </div>
              ) : results.length === 0 ? (
                <SearchEmptyState query={activeQuery} onReset={handleResetAll} />
              ) : (
                <>
                  <div className="results-cards-list">
                    {results.map((item) => (
                      <SearchResultCard
                        key={`${item.providerId || item.id || 'provider'}:${item.listingId || 'profile'}`}
                        item={item}
                        isSelected={selectedProviders.some((p) => p.id === item.id)}
                        onToggleComparison={handleToggleComparison}
                      />
                    ))}
                  </div>

                  {/* Comparison Banner */}
                  <SearchComparisonBanner
                    selectedProviders={selectedProviders}
                    onCompare={handleCompareClick}
                  />

                  {/* Pagination */}
                  <nav className="search-pagination-bar" aria-label="Search pagination">
                    <span className="pagination-info">
                      {formatPaginationText()}
                    </span>

                    <div className="pagination-controls">
                      <button
                        type="button"
                        className="pagination-btn"
                        aria-label="Previous page"
                        onClick={() => handlePageChange(page - 1)}
                        disabled={page <= 0}
                      >
                        ‹
                      </button>
                      {Array.from({ length: totalPages }, (_, index) => (
                        <button
                          key={index}
                          type="button"
                          className={`pagination-btn${
                            index === page ? ' pagination-btn-active' : ''
                          }`}
                          aria-current={index === page ? 'page' : undefined}
                          onClick={() => handlePageChange(index)}
                        >
                          {index + 1}
                        </button>
                      ))}
                      <button
                        type="button"
                        className="pagination-btn"
                        aria-label="Next page"
                        onClick={() => handlePageChange(page + 1)}
                        disabled={page >= totalPages - 1}
                      >
                        ›
                      </button>
                    </div>
                  </nav>
                </>
              ))}
            </section>
          </div>
        </div>
      </main>

      {/* Footer */}
      <LocalServeFooter />

      {/* Full side-by-side provider comparison */}
      {isComparisonOpen && (
        <ProviderComparisonModal
          providers={selectedProviders}
          serviceLabel={activeQuery}
          locationLabel={activeLocation}
          filters={filters}
          onClose={handleCloseComparison}
          onRemove={handleRemoveComparisonProvider}
        />
      )}

      {/* Selection limit notice */}
      <FeaturePendingModal
        isOpen={modalState.isOpen}
        title={modalState.title}
        message={modalState.message}
        badgeText={modalState.badgeText}
        triggerRef={modalTriggerRef}
        onClose={() => setModalState({ isOpen: false, title: '', message: '', badgeText: '' })}
      />
    </div>
  );
}

export default SearchResultsPage;
