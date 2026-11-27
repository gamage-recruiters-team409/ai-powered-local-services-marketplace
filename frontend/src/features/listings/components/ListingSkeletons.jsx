import styles from '../styles/providerListings.module.css'

/**
 * Exact layout-matched shimmer skeleton for the Provider Listings Dashboard
 * (4 KPI cards + segmented toolbar + 4 listing cards) shown on first load.
 */
export function DashboardLoadingSkeleton() {
  return (
    <div
      className={styles.skeletonStack}
      role="status"
      aria-busy="true"
      aria-label="Loading service listings"
      data-testid="dashboard-loading-skeleton"
    >
      {/* 4 KPI Skeleton Cards */}
      <div className={styles.kpiGrid}>
        {Array.from({ length: 4 }, (_, idx) => (
          <div key={idx} className={styles.kpiSkeletonCard} style={{ '--sk-idx': idx }}>
            <div className={styles.kpiTopRow}>
              <span className={`${styles.shimmerBlock} ${styles.skLabel}`} />
              <span className={`${styles.shimmerBlock} ${styles.skIconBox}`} />
            </div>
            <div className={styles.kpiValueRow}>
              <span className={`${styles.shimmerBlock} ${styles.skBigNum}`} />
              <span className={`${styles.shimmerBlock} ${styles.skPill}`} />
            </div>
          </div>
        ))}
      </div>

      {/* Toolbar Shimmer Skeleton */}
      <div className={styles.toolbarSkeleton}>
        <div className={styles.skTabsRow}>
          {Array.from({ length: 5 }, (_, idx) => (
            <span key={idx} className={`${styles.shimmerBlock} ${styles.skTabPill}`} />
          ))}
        </div>
        <div className={styles.skControlsRow}>
          <span className={`${styles.shimmerBlock} ${styles.skSearchBox}`} />
          <span className={`${styles.shimmerBlock} ${styles.skSelectBox}`} />
          <span className={`${styles.shimmerBlock} ${styles.skToggleBox}`} />
        </div>
      </div>

      {/* 4 Listing Card Skeletons matching the exact Studio Card anatomy */}
      <div className={styles.cardsGrid}>
        {Array.from({ length: 4 }, (_, idx) => (
          <div key={idx} className={styles.listingSkeletonCard} style={{ '--sk-idx': idx }}>
            <div className={`${styles.shimmerBlock} ${styles.skMedia}`} />
            <div className={styles.skCardBody}>
              <span className={`${styles.shimmerBlock} ${styles.skCatChip}`} />
              <span className={`${styles.shimmerBlock} ${styles.skTitleLine}`} />
              <span className={`${styles.shimmerBlock} ${styles.skTitleShort}`} />
              <span className={`${styles.shimmerBlock} ${styles.skPriceLine}`} />
              <div className={styles.skMetaRow}>
                <span className={`${styles.shimmerBlock} ${styles.skSmallPill}`} />
                <span className={`${styles.shimmerBlock} ${styles.skSmallPill}`} />
              </div>
              <div className={styles.skActionsRow}>
                <span className={`${styles.shimmerBlock} ${styles.skActionBtn}`} />
                <span className={`${styles.shimmerBlock} ${styles.skActionBtn}`} />
                <span className={`${styles.shimmerBlock} ${styles.skActionBtn}`} />
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

/**
 * Exact layout-matched shimmer skeleton for the 4-Step Create/Edit Listing Wizard
 * (Header + 4-Step Progress Stepper + 2-Column Form & Map Skeleton).
 */
export function WizardLoadingSkeleton() {
  return (
    <div
      className={styles.wizardWrap}
      role="status"
      aria-busy="true"
      aria-label="Loading service listing editor"
      data-testid="wizard-loading-skeleton"
    >
      {/* Header Shimmer */}
      <div className={styles.skWizardHeader}>
        <div className={styles.skWizardTitleGroup}>
          <span className={`${styles.shimmerBlock} ${styles.skBreadcrumb}`} />
          <span className={`${styles.shimmerBlock} ${styles.skPageHeading}`} />
          <span className={`${styles.shimmerBlock} ${styles.skSubtitle}`} />
        </div>
        <span className={`${styles.shimmerBlock} ${styles.skDraftBtn}`} />
      </div>

      {/* 4-Step Ring Stepper Shimmer */}
      <div className={styles.stepperSkeletonCard}>
        {Array.from({ length: 4 }, (_, idx) => (
          <div key={idx} className={styles.skStepCol}>
            <span className={`${styles.shimmerBlock} ${styles.skStepRing}`} />
            <span className={`${styles.shimmerBlock} ${styles.skStepLabel}`} />
            <span className={`${styles.shimmerBlock} ${styles.skStepSub}`} />
          </div>
        ))}
      </div>

      {/* 2-Column Step Form + Map Shimmer */}
      <div className={styles.wizardStepGrid}>
        <div className={styles.wizardStackCol}>
          <div className={styles.skSectionCard}>
            <span className={`${styles.shimmerBlock} ${styles.skSectionTitle}`} />
            <span className={`${styles.shimmerBlock} ${styles.skInput}`} />
            <div className={styles.fieldGridTwo}>
              <span className={`${styles.shimmerBlock} ${styles.skInput}`} />
              <span className={`${styles.shimmerBlock} ${styles.skInput}`} />
            </div>
            <span className={`${styles.shimmerBlock} ${styles.skStrip}`} />
          </div>
          <div className={styles.skSectionCard}>
            <span className={`${styles.shimmerBlock} ${styles.skSectionTitle}`} />
            <span className={`${styles.shimmerBlock} ${styles.skTextarea}`} />
          </div>
        </div>

        <div className={styles.skSectionCard}>
          <span className={`${styles.shimmerBlock} ${styles.skSectionTitle}`} />
          <span className={`${styles.shimmerBlock} ${styles.skSegmentBar}`} />
          <span className={`${styles.shimmerBlock} ${styles.skInput}`} />
          <span className={`${styles.shimmerBlock} ${styles.skMapBox}`} />
          <div className={styles.skTabsRow}>
            <span className={`${styles.shimmerBlock} ${styles.skSmallPill}`} />
            <span className={`${styles.shimmerBlock} ${styles.skSmallPill}`} />
            <span className={`${styles.shimmerBlock} ${styles.skSmallPill}`} />
          </div>
        </div>
      </div>
    </div>
  )
}
