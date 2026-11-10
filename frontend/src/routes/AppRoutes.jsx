import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import SearchResultsPage from '../features/search-discovery/pages/SearchResultsPage';

/**
 * Main Application Routes for frontend.
 * Provides routing for search results at /search and as index.
 */
export function AppRoutes() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/search" element={<SearchResultsPage />} />
        <Route path="/" element={<SearchResultsPage />} />
        <Route path="*" element={<Navigate to="/search" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default AppRoutes;
