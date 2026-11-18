import styles from '../disputes.module.css'

/**
 * TEMPORARY page frame for customer dispute screens: the customer layout (WP03) is not in the repository yet.
 * Replace this with the customer layout when it lands; the page itself needs no change.
 */
export function CustomerDisputeShell({ children }) {
  return (
    <main className={styles.standalone}>
      <div className={styles.container}>{children}</div>
    </main>
  )
}
