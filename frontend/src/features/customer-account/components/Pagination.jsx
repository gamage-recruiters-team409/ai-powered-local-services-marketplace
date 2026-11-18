import styles from '../customerAccount.module.css';

export function Pagination({
  currentPage = 0,
  totalPages = 1,
  totalElements = 0,
  pageSize = 10,
  itemLabel = 'items',
  onPageChange,
}) {
  if (totalElements === 0) return null;

  const start = currentPage * pageSize + 1;
  const end = Math.min((currentPage + 1) * pageSize, totalElements);

  // Generate page numbers to show
  const pageNumbers = [];
  for (let i = 0; i < totalPages; i++) {
    pageNumbers.push(i);
  }

  return (
    <div className={styles.paginationFooter}>
      <span className={styles.paginationSummary}>
        Showing {start}-{end} of {totalElements} {itemLabel}
      </span>

      <div className={styles.paginationControls}>
        <button
          type="button"
          className={styles.paginationBtn}
          disabled={currentPage === 0}
          onClick={() => onPageChange(currentPage - 1)}
          aria-label="Previous page"
        >
          &lt; Prev
        </button>

        {pageNumbers.map((num) => (
          <button
            key={num}
            type="button"
            className={`${styles.paginationBtn} ${num === currentPage ? styles.paginationBtnActive : ''}`}
            onClick={() => onPageChange(num)}
            aria-current={num === currentPage ? 'page' : undefined}
          >
            {num + 1}
          </button>
        ))}

        <button
          type="button"
          className={styles.paginationBtn}
          disabled={currentPage >= totalPages - 1}
          onClick={() => onPageChange(currentPage + 1)}
          aria-label="Next page"
        >
          Next &gt;
        </button>
      </div>
    </div>
  );
}

export default Pagination;
