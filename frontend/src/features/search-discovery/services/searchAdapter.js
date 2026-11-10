import axios from 'axios';
import { MOCK_SEARCH_RESULTS } from '../fixtures/mockSearchResults';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

/**
 * Search Adapter for WP04 Search & Discovery foundation.
 *
 * Checks if a live backend search endpoint exists at `${API_BASE_URL}/search`.
 * If live search endpoints are not yet implemented in backend, it falls back
 * to the isolated fixtures in `mockSearchResults.js` and records live integration
 * as pending.
 */
export async function fetchSearchResults({ query = '', category = '', sortBy = 'relevance' } = {}) {
  const trimmedQuery = query.trim().toLowerCase();

  // Attempt live API if available
  try {
    const response = await axios.get(`${API_BASE_URL}/search`, {
      params: { q: trimmedQuery, category, sort: sortBy },
      timeout: 2500,
    });
    if (response.data && Array.isArray(response.data.results)) {
      return {
        isLive: true,
        results: response.data.results,
        totalCount: response.data.totalCount || response.data.results.length,
      };
    }
  } catch (error) {
    // Expected fallback while backend WP04 endpoints are under active development
    console.info(
      `[WP04 SearchAdapter] Live search endpoint (${API_BASE_URL}/search) pending (${error.message}). Serving isolated local fixtures.`,
    );
  }

  // Simulate minimal UI transition delay for realistic loading state verification
  await new Promise((resolve) => setTimeout(resolve, 320));

  // Client-side filtering of fixtures
  let results = [...MOCK_SEARCH_RESULTS];

  if (trimmedQuery) {
    results = results.filter((item) => {
      const matchTitle = item.title.toLowerCase().includes(trimmedQuery);
      const matchCat = item.category.toLowerCase().includes(trimmedQuery);
      const matchDesc = item.description.toLowerCase().includes(trimmedQuery);
      const matchProvider =
        item.provider.name.toLowerCase().includes(trimmedQuery) ||
        item.provider.businessName.toLowerCase().includes(trimmedQuery);
      const matchLocation = item.location.toLowerCase().includes(trimmedQuery);
      const matchTags = item.tags.some((tag) => tag.toLowerCase().includes(trimmedQuery));

      return matchTitle || matchCat || matchDesc || matchProvider || matchLocation || matchTags;
    });
  }

  if (category && category !== 'All') {
    results = results.filter(
      (item) => item.category.toLowerCase() === category.toLowerCase(),
    );
  }

  // Sorting
  if (sortBy === 'rating') {
    results.sort((a, b) => b.provider.rating - a.provider.rating);
  } else if (sortBy === 'price_asc') {
    results.sort((a, b) => a.pricing.startingPrice - b.pricing.startingPrice);
  } else if (sortBy === 'price_desc') {
    results.sort((a, b) => b.pricing.startingPrice - a.pricing.startingPrice);
  }

  return {
    isLive: false,
    results,
    totalCount: results.length,
  };
}

