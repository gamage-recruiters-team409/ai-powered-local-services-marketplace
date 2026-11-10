import { useState, useEffect } from 'react';
import { fetchSearchResults } from '../services/searchAdapter';
import LocalServeNavbar from '../components/LocalServeNavbar';
import SearchInputHeader from '../components/SearchInputHeader';
import SearchFiltersSidebar from '../components/SearchFiltersSidebar';
import SearchResultCard from '../components/SearchResultCard';
import SearchComparisonBanner from '../components/SearchComparisonBanner';
import LocalServeFooter from '../components/LocalServeFooter';
import {
  SearchResultSkeleton,
  SearchEmptyState,
  SearchErrorState,
} from '../components/SearchResultsState';
import '../styles/search-discovery.css';

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

  const [results, setResults] = useState([]);
  const [totalCount, setTotalCount] = useState(18);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [selectedProviders, setSelectedProviders] = useState([]);

  useEffect(() => {
    let isCancelled = false;

    async function loadData() {
      try {
        const data = await fetchSearchResults({
          query: activeQuery,
          location: activeLocation,
          sortBy,
        });
        if (!isCancelled) {
          const items = data.results || [];
          setResults(items);
          setTotalCount(data.totalCount || items.length);
          // Pre-populate comparison with initial items that have inComparison: true
          const initialComparing = items.filter((item) => item.inComparison);
          setSelectedProviders(initialComparing);
          setError(null);
        }
      } catch (err) {
        if (!isCancelled) {
          console.error('[SearchResultsPage] Search error:', err);
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
  }, [activeQuery, activeLocation, sortBy]);

  const handleSearchSubmit = ({ query: newQuery, location: newLocation }) => {
    setIsLoading(true);
    setActiveQuery(newQuery);
    setActiveLocation(newLocation);
  };

  const handleResetSearch = () => {
    setQuery('Home cleaning');
    setLocation('Austin, TX 78701');
    setIsLoading(true);
    setActiveQuery('Home cleaning');
    setActiveLocation('Austin, TX 78701');
  };

  const handleToggleComparison = (provider, isAdded) => {
    setSelectedProviders((prev) => {
      if (isAdded) {
        if (prev.some((p) => p.id === provider.id)) return prev;
        if (prev.length >= 3) {
          alert('You can select up to 3 providers to compare.');
          return prev;
        }
        return [...prev, provider];
      } else {
        return prev.filter((p) => p.id !== provider.id);
      }
    });
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
          />

          {/* Two-Column Search Layout */}
          <div className="search-layout-grid">
            {/* Left Column: Filters Sidebar */}
            <SearchFiltersSidebar onReset={handleResetSearch} />

            {/* Right Column: Cards, States, Comparison & Pagination */}
            <section className="search-results-column" aria-label="Search results list">
              {error && (
                <SearchErrorState
                  error={error}
                  onRetry={() => {
                    setIsLoading(true);
                    setActiveQuery(query);
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
                <SearchEmptyState query={activeQuery} onReset={handleResetSearch} />
              ) : (
                <>
                  <div className="results-cards-list">
                    {results.map((item) => (
                      <SearchResultCard
                        key={item.id}
                        item={item}
                        onToggleComparison={handleToggleComparison}
                        onSelect={(selected) => {
                          console.info('[LocalServe] Viewing profile for:', selected.name);
                        }}
                      />
                    ))}
                  </div>

                  {/* Comparison Banner */}
                  <SearchComparisonBanner
                    selectedProviders={selectedProviders}
                    onCompare={(providers) => {
                      console.info('[LocalServe] Comparing providers:', providers.map((p) => p.name));
                    }}
                  />

                  {/* Pagination */}
                  <nav className="search-pagination-bar" aria-label="Search pagination">
                    <span className="pagination-info">
                      Showing 1–{results.length} of {totalCount} providers
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
    </div>
  );
}

export default SearchResultsPage;
