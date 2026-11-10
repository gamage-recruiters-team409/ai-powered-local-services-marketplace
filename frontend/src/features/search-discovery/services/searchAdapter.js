import axios from 'axios';
import { MOCK_SEARCH_RESULTS } from '../fixtures/mockSearchResults.js';

const API_BASE_URL = (typeof import.meta !== 'undefined' && import.meta.env?.VITE_API_BASE_URL) || 'http://localhost:8080/api/v1';

/**
 * Search Adapter for WP04 Search & Discovery foundation.
 * Checks for live backend endpoint at `${API_BASE_URL}/search`.
 * Falls back to approved reference fixtures while backend integration is pending.
 */
export async function fetchSearchResults({
  query = '',
  location = '',
  filters = {},
  sortBy = 'recommended',
} = {}) {
  const trimmedQuery = query.trim().toLowerCase();
  const trimmedLocation = location.trim().toLowerCase();

  // Attempt live API if available
  try {
    const response = await axios.get(`${API_BASE_URL}/search`, {
      params: {
        q: trimmedQuery,
        location: trimmedLocation,
        sort: sortBy,
        verified: filters.verifiedOnly,
        serviceTypes: [
          filters.standardClean && 'standard',
          filters.deepClean && 'deep',
          filters.moveOutClean && 'moveout',
          filters.recurringClean && 'recurring',
        ].filter(Boolean).join(','),
      },
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
    console.info(
      `[WP04 SearchAdapter] Live search endpoint (${API_BASE_URL}/search) pending (${error.message}). Serving approved reference fixtures.`,
    );
  }

  // Brief latency simulation for realistic UI transition
  await new Promise((resolve) => setTimeout(resolve, 200));

  let results = [...MOCK_SEARCH_RESULTS];

  // 1. Text Query Filter
  if (trimmedQuery) {
    results = results.filter((item) => {
      const matchName = item.name.toLowerCase().includes(trimmedQuery);
      const matchDesc = item.description.toLowerCase().includes(trimmedQuery);
      const matchCat = item.category.toLowerCase().includes(trimmedQuery);
      return matchName || matchDesc || matchCat;
    });
  }

  // 2. Location Filter
  if (trimmedLocation) {
    results = results.filter((item) =>
      item.location.toLowerCase().includes(trimmedLocation),
    );
  }

  // 3. Service Type Filter
  const hasServiceTypeFilter =
    filters.standardClean !== undefined ||
    filters.deepClean !== undefined ||
    filters.moveOutClean !== undefined ||
    filters.recurringClean !== undefined;

  if (hasServiceTypeFilter) {
    const activeServiceTypes = [];
    if (filters.standardClean) activeServiceTypes.push('standard');
    if (filters.deepClean) activeServiceTypes.push('deep');
    if (filters.moveOutClean) activeServiceTypes.push('moveout');
    if (filters.recurringClean) activeServiceTypes.push('recurring');

    if (activeServiceTypes.length > 0) {
      results = results.filter((item) =>
        item.serviceTypes?.some((t) => activeServiceTypes.includes(t)),
      );
    } else {
      // If all service type checkboxes are explicitly unchecked, 0 results match
      results = [];
    }
  }

  // 4. Verified Only Filter
  if (filters.verifiedOnly) {
    results = results.filter((item) => item.isVerified);
  }

  // 5. Hourly Budget Filter
  if (typeof filters.budgetMax === 'number') {
    results = results.filter((item) => item.pricing.hourlyRate <= filters.budgetMax);
  }

  // 6. Rating Filter (4.5 stars & above)
  if (filters.highRating) {
    results = results.filter((item) => item.rating >= 4.8);
  }

  // 7. Sorting
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
