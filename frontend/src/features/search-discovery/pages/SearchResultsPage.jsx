import { useState, useEffect } from 'react';
import { fetchSearchResults } from '../services/searchAdapter';
import SearchResultCard from '../components/SearchResultCard';
import SearchInputHeader from '../components/SearchInputHeader';
import {
  SearchResultSkeleton,
  SearchEmptyState,
  SearchErrorState,
} from '../components/SearchResultsState';
import '../styles/search-discovery.css';

/**
 * Main Search Results Page for WP04 Search & Discovery.
 * Responsive, handles loading/empty/error states, and connects
 * through the isolated searchAdapter.
 */
export function SearchResultsPage() {
  const [query, setQuery] = useState('');
  const [activeSearchTerm, setActiveSearchTerm] = useState('');
  const [results, setResults] = useState([]);
  const [totalCount, setTotalCount] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(null);
  const [isLive, setIsLive] = useState(false);

  useEffect(() => {
    let isCancelled = false;

    async function loadData() {
      try {
        const data = await fetchSearchResults({ query: activeSearchTerm });
        if (!isCancelled) {
          setResults(data.results || []);
          setTotalCount(data.totalCount || 0);
          setIsLive(data.isLive || false);
          setError(null);
        }
      } catch (err) {
        if (!isCancelled) {
          console.error('[SearchResultsPage] Search execution error:', err);
          setError('Failed to fetch search results. Please check your network and try again.');
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
  }, [activeSearchTerm]);

  const handleSearchSubmit = (searchTerm) => {
    setIsLoading(true);
    setActiveSearchTerm(searchTerm);
  };

  const handleResetSearch = () => {
    setQuery('');
    setIsLoading(true);
    setActiveSearchTerm('');
  };

  return (
    <main className="search-discovery-container" id="main-content">
      <SearchInputHeader
        query={query}
        onQueryChange={setQuery}
        onSearch={handleSearchSubmit}
        totalCount={totalCount}
        isLoading={isLoading}
        isLive={isLive}
      />

      {error && (
        <SearchErrorState
          error={error}
          onRetry={() => {
            setIsLoading(true);
            setActiveSearchTerm(query);
          }}
        />
      )}

      {isLoading ? (
        <div className="results-grid" aria-label="Loading search results" aria-busy="true">
          {Array.from({ length: 6 }).map((_, index) => (
            <SearchResultSkeleton key={index} />
          ))}
        </div>
      ) : results.length === 0 ? (
        <SearchEmptyState query={activeSearchTerm} onReset={handleResetSearch} />
      ) : (
        <div className="results-grid" role="region" aria-label="Search results list">
          {results.map((item) => (
            <SearchResultCard
              key={item.id}
              item={item}
              onSelect={(selected) => {
                console.info('[WP04] Service card selected:', selected.title);
              }}
            />
          ))}
        </div>
      )}
    </main>
  );
}

export default SearchResultsPage;
