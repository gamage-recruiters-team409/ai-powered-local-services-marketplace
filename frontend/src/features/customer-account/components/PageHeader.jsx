import styles from '../customerAccount.module.css';

export function PageHeader({ title, subtitle, actions = null }) {
  return (
    <div className={styles.pageHeader}>
      <div>
        <h1 className={styles.pageHeaderTitle}>{title}</h1>
        {subtitle && <p className={styles.pageHeaderSubtitle}>{subtitle}</p>}
      </div>
      {actions && <div>{actions}</div>}
    </div>
  );
}

export default PageHeader;
