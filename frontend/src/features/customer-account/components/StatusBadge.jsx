import styles from '../customerAccount.module.css';

export function StatusBadge({ variant = 'default', children, icon = null }) {
  let variantClass = styles.badgeDefault;
  if (variant === 'success') variantClass = styles.badgeSuccess;
  else if (variant === 'info') variantClass = styles.badgeInfo;
  else if (variant === 'warning') variantClass = styles.badgeWarning;
  else if (variant === 'danger') variantClass = styles.badgeDanger;
  else if (variant === 'muted') variantClass = styles.badgeMuted;

  return (
    <span className={`${styles.badge} ${variantClass}`}>
      {icon && <span>{icon}</span>}
      {children}
    </span>
  );
}

export default StatusBadge;
