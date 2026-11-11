import { useState, useEffect, useRef } from 'react';
import { fetchSearchResults } from '../services/searchAdapter';
import LocalServeNavbar from '../components/LocalServeNavbar';
import SearchInputHeader from '../components/SearchInputHeader';
import SearchFiltersSidebar from '../components/SearchFiltersSidebar';
import SearchResultCard from '../components/SearchResultCard';
import SearchComparisonBanner from '../components/SearchComparisonBanner';
import LocalServeFooter from '../components/LocalServeFooter';
import FeaturePendingModal from '../components/FeaturePendingModal';
import {
  SearchResultSkeleton,
  SearchEmptyState,
  SearchErrorState,
} from '../components/SearchResultsState';
import '../styles/search-discovery.css';

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

/**
 * Main Search Results Page matching the approved LocalServe design.
 * Features full-width responsive header/footer, sidebar filters,
 * provider cards with authentic images, comparison tray, and pagination.
 */
export function SearchResultsPage() {
  const [query, setQuery] = useState('Home cleaning');
  const [location, setLocation] = useState('Austin, TX 78701');
  const [activeQuery, setActiveQuery] = useState('Home cleaning');
  const [activeLocation, setActiveLocation] = useState('Austin, TX 78701');
  const [sortBy, setSortBy] = useState('recommended');
  const [filters, setFilters] = useState(DEFAULT_FILTERS);

  const [results, setResults] = useState([]);
  const [totalCount, setTotalCount] = useState(3);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [isFixture, setIsFixture] = useState(false);
  const [selectedProviders, setSelectedProviders] = useState([]);
  // Incremented by every explicit Search / Retry / Reset action so the request
  // effect runs even when the query and filters are unchanged.
  const [searchNonce, setSearchNonce] = useState(0);

  // Milestone Notice Modal
  const [modalState, setModalState] = useState({
    isOpen: false,
    title: '',
    message: '',
    badgeText: '',
  });
  // Ref to the element that last opened the modal; focus is restored on close.
  const modalTriggerRef = useRef(null);

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
        });
        if (!isCancelled) {
          const items = data.results || [];
          setResults(items);
          setTotalCount(data.totalCount || items.length);
          setIsFixture(Boolean(data.isFixture));

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
          setIsFixture(false);
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
  }, [activeQuery, activeLocation, filters, sortBy, searchNonce]);

  const handleSearchSubmit = ({ query: newQuery, location: newLocation }) => {
    setIsLoading(true);
    setActiveQuery(newQuery);
    setActiveLocation(newLocation);
    setSearchNonce((n) => n + 1);
  };

  const handleFilterChange = (key, value) => {
    setIsLoading(true);
    setFilters((prev) => ({
      ...prev,
      [key]: value,
    }));
  };

  const handleClearAllChips = () => {
    setIsLoading(true);
    setFilters((prev) => ({
      ...prev,
      standardClean: false,
      verifiedOnly: false,
    }));
  };

  const handleResetAll = () => {
    setQuery('Home cleaning');
    setLocation('Austin, TX 78701');
    setIsLoading(true);
    setActiveQuery('Home cleaning');
    setActiveLocation('Austin, TX 78701');
    setSortBy('recommended');
    setFilters(DEFAULT_FILTERS);
    setSearchNonce((n) => n + 1);
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

  const handleViewProfile = (provider, triggerEl) => {
    modalTriggerRef.current = triggerEl ?? null;
    setModalState({
      isOpen: true,
      badgeText: 'MILESTONE BOUNDARY',
      title: `Provider Profile: ${provider.name}`,
      message:
        'The individual provider profile screen is scheduled for an upcoming milestone. Detailed credentials, services catalog, and booking availability will be available once that module is integrated.',
    });
  };

  const handleCompareClick = (triggerEl) => {
    modalTriggerRef.current = triggerEl ?? null;
    setModalState({
      isOpen: true,
      badgeText: 'SCHEDULED FOR MILESTONE 2',
      title: 'Side-by-Side Comparison',
      message: `The full side-by-side comparison screen for your ${selectedProviders.length} selected providers (${selectedProviders
        .map((p) => p.name)
        .join(', ')}) is scheduled for Milestone 2.`,
    });
  };

  const formatPaginationText = () => {
    if (results.length === 0) return 'Showing 0 of 0 providers';
    if (results.length === 1) return `Showing 1 of ${totalCount} provider`;
    return `Showing 1–${results.length} of ${totalCount} providers`;
  };

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
            onSortChange={setSortBy}
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
            <section className="search-results-column" aria-label="Search results list">
              {isFixture && (
                <p className="search-demo-notice" role="status">
                  Demo mode: showing sample providers while live search is not connected.
                </p>
              )}

              {error && (
                <SearchErrorState
                  error={error}
                  onRetry={() => {
                    setIsLoading(true);
                    setActiveQuery(query);
                    setActiveLocation(location);
                    setSearchNonce((n) => n + 1);
                  }}
                />
              )}

              {isLoading ? (
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
                        key={item.id}
                        item={item}
                        isSelected={selectedProviders.some((p) => p.id === item.id)}
                        onToggleComparison={handleToggleComparison}
                        onSelect={handleViewProfile}
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
                      <button type="button" className="pagination-btn" aria-label="Previous page">
                        ‹
                      </button>
                      <button
                        type="button"
                        className="pagination-btn pagination-btn-active"
                        aria-current="page"
                      >
                        1
                      </button>
                      <button type="button" className="pagination-btn">
                        2
                      </button>
                      <button type="button" className="pagination-btn">
                        3
                      </button>
                      <button type="button" className="pagination-btn" aria-label="Next page">
                        ›
                      </button>
                    </div>
                  </nav>
                </>
              )}
            </section>
          </div>
        </div>
      </main>

      {/* Footer */}
      <LocalServeFooter />

      {/* Explicit Milestone Limitation Modal */}
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
