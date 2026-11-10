import axios from 'axios';
import { MOCK_SEARCH_RESULTS } from '../fixtures/mockSearchResults';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

/**
 * Search Adapter for WP04 Search & Discovery foundation.
 * Checks for live backend endpoint at `${API_BASE_URL}/search`.
 * Falls back to approved reference fixtures while backend integration is pending.
 */
export async function fetchSearchResults({
  query = '',
  location = '',
  category = '',
  sortBy = 'recommended',
} = {}) {
  const trimmedQuery = query.trim().toLowerCase();
  const trimmedLocation = location.trim().toLowerCase();

  // Attempt live API if available
  try {
    const response = await axios.get(`${API_BASE_URL}/search`, {
      params: { q: trimmedQuery, location: trimmedLocation, category, sort: sortBy },
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
    // Expected fallback while backend WP04 search endpoints are in development
    console.info(
      `[WP04 SearchAdapter] Live search endpoint (${API_BASE_URL}/search) pending (${error.message}). Serving approved reference fixtures.`,
    );
  }

  // Brief latency simulation for realistic UI transition
  await new Promise((resolve) => setTimeout(resolve, 280));

  let results = [...MOCK_SEARCH_RESULTS];

  if (trimmedQuery) {
    results = results.filter((item) => {
      const matchName = item.name.toLowerCase().includes(trimmedQuery);
      const matchDesc = item.description.toLowerCase().includes(trimmedQuery);
      const matchCat = item.category.toLowerCase().includes(trimmedQuery);
      return matchName || matchDesc || matchCat;
    });
  }

  if (trimmedLocation) {
    results = results.filter((item) =>
      item.location.toLowerCase().includes(trimmedLocation),
    );
  }

  if (sortBy === 'rating') {
    results.sort((a, b) => b.rating - a.rating);
  } else if (sortBy === 'price_asc') {
    results.sort((a, b) => a.pricing.hourlyRate - b.pricing.hourlyRate);
  } else if (sortBy === 'price_desc') {
    results.sort((a, b) => b.pricing.hourlyRate - a.pricing.hourlyRate);
  }

  return {
    isLive: false,
    results,
    totalCount: results.length,
  };
}
