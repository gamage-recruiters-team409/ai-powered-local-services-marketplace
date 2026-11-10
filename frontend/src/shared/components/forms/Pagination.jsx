/**
 * File: Pagination.jsx
 * Module: WP07 - Shared Form & Basic UI Components
 *
 * Purpose:
 * Provides reusable pagination navigation for data
 * tables and other paginated content.
 *
 * Features:
 * - Previous and Next navigation
 * - Numbered page buttons
 * - Active page indication
 * - Compact pagination with ellipses
 * - Disabled boundary states
 * - Accessible navigation labels
 *
 * Design reference:
 * Shared Form & Basic UI Components - Figma.
 */

import "./forms.css";

/**
 * Builds a compact list of page numbers.
 *
 * The first and last pages are always displayed.
 * Ellipses replace page ranges that are not visible.
 *
 * Example:
 * [1, "...", 4, 5, 6, "...", 12]
 */
function getVisiblePages(currentPage, totalPages) {
  // Show every page when the page count is small.
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, index) => index + 1);
  }

  // Keep the current page and its immediate neighbours.
  const visiblePages = new Set([
    1,
    totalPages,
    currentPage - 1,
    currentPage,
    currentPage + 1,
  ]);

  // Show additional pages near the beginning.
  if (currentPage <= 3) {
    [2, 3, 4].forEach((page) => visiblePages.add(page));
  }

  // Show additional pages near the end.
  if (currentPage >= totalPages - 2) {
    [totalPages - 3, totalPages - 2, totalPages - 1].forEach((page) =>
      visiblePages.add(page),
    );
  }

  // Remove invalid page numbers and sort the result.
  const sortedPages = [...visiblePages]
    .filter((page) => page >= 1 && page <= totalPages)
    .sort((a, b) => a - b);

  const result = [];

  // Insert an ellipsis only when pages are skipped.
  sortedPages.forEach((page, index) => {
    if (index > 0) {
      const previousPage = sortedPages[index - 1];
      const gap = page - previousPage;

      if (gap === 2) {
        result.push(previousPage + 1);
      } else if (gap > 2) {
        result.push("...");
      }
    }

    result.push(page);
  });

  return result;
}

/**
 * Pagination
 *
 * Props:
 * - currentPage: Currently selected page (1-based)
 * - totalPages: Total number of available pages
 * - onPageChange: Callback receiving the selected page
 * - disabled: Disables pagination interactions
 * - className: Additional wrapper CSS classes
 *
 * Important:
 * This is a controlled component. The parent must
 * update currentPage after onPageChange is called.
 */
function Pagination({
  currentPage = 1,
  totalPages = 1,
  onPageChange,
  disabled = false,
  className = "",
}) {
  // Ensure navigation calculations stay within valid bounds.
  const safeTotalPages = Math.max(1, Math.floor(totalPages));
  const safeCurrentPage = Math.min(
    Math.max(1, Math.floor(currentPage)),
    safeTotalPages,
  );

  const isFirstPage = safeCurrentPage === 1;
  const isLastPage = safeCurrentPage === safeTotalPages;

  // Determine which page buttons should be displayed.
  const visiblePages = getVisiblePages(safeCurrentPage, safeTotalPages);

  /**
   * Notifies the parent when a valid new page is selected.
   * Prevents out-of-range or disabled navigation.
   */
  const handlePageChange = (page) => {
    if (
      disabled ||
      page < 1 ||
      page > safeTotalPages ||
      page === safeCurrentPage
    ) {
      return;
    }

    if (typeof onPageChange === "function") {
      onPageChange(page);
    }
  };

  return (
    <nav
      className={["shared-pagination", className].filter(Boolean).join(" ")}
      aria-label="Pagination navigation"
    >
      {/* Navigate to the previous page. */}
      <button
        type="button"
        className="shared-pagination__button shared-pagination__previous"
        onClick={() => handlePageChange(safeCurrentPage - 1)}
        disabled={disabled || isFirstPage}
        aria-label="Go to previous page"
      >
        <span aria-hidden="true">‹</span>
        <span>Previous</span>
      </button>

      {/* Numbered pages and non-interactive ellipses. */}
      <div className="shared-pagination__pages">
        {visiblePages.map((page, index) =>
          page === "..." ? (
            <span
              key={`ellipsis-${index}`}
              className="shared-pagination__ellipsis"
              aria-hidden="true"
            >
              …
            </span>
          ) : (
            <button
              key={page}
              type="button"
              className={[
                "shared-pagination__button",
                "shared-pagination__page",
                page === safeCurrentPage
                  ? "shared-pagination__page--active"
                  : "",
              ]
                .filter(Boolean)
                .join(" ")}
              onClick={() => handlePageChange(page)}
              disabled={disabled}
              aria-label={`Go to page ${page}`}
              aria-current={page === safeCurrentPage ? "page" : undefined}
            >
              {page}
            </button>
          ),
        )}
      </div>

      {/* Navigate to the next page. */}
      <button
        type="button"
        className="shared-pagination__button shared-pagination__next"
        onClick={() => handlePageChange(safeCurrentPage + 1)}
        disabled={disabled || isLastPage}
        aria-label="Go to next page"
      >
        <span>Next</span>
        <span aria-hidden="true">›</span>
      </button>
    </nav>
  );
}

export default Pagination;
