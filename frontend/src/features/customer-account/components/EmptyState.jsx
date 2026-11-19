import { Link } from 'react-router-dom';
import styles from '../customerAccount.module.css';

export function EmptyState({
  icon,
  title,
  description,
  actionLabel = null,
  onAction = null,
  actionTo = null,
}) {
  return (
    <div className={styles.emptyStateContainer}>
      {icon && <div className={styles.emptyStateIconCircle}>{icon}</div>}
      <h2 className={styles.emptyStateTitle}>{title}</h2>
      {description && <p className={styles.emptyStateDescription}>{description}</p>}
      {actionLabel && actionTo && (
        <Link to={actionTo} className={styles.btnPrimary}>
          {actionLabel}
        </Link>
      )}
      {actionLabel && !actionTo && onAction && (
        <button type="button" onClick={onAction} className={styles.btnPrimary}>
          {actionLabel}
        </button>
      )}
    </div>
  );
}

export default EmptyState;
