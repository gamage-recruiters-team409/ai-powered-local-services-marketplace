import { useState } from 'react'
import { ESCROW_SIMULATOR_STAGES, HOW_IT_WORKS_STEPS } from '../data/landingContent'
import {
  CategoryTradeIcon,
  CheckCircleIcon,
  EditorialBurstAccent,
  LockIcon,
  ShieldCheckIcon,
  UnlockIcon,
} from './LandingIcons'
import styles from '../styles/publicLanding.module.css'

export function HowMarketWorksSection() {
  const [activeStageIdx, setActiveStageIdx] = useState(0)
  const activeStage = ESCROW_SIMULATOR_STAGES[activeStageIdx]
  const isReleased = activeStage.statusTone === 'released'

  return (
    <section
      id="how-market-works"
      className={styles.howSection}
      aria-labelledby="how-market-works-heading"
    >
      <div className={styles.sectionContainer}>
        <div className={styles.sectionHeader}>
          <span className={styles.sectionPill}>Verified Local Services Marketplace</span>
          <h2 id="how-market-works-heading" className={styles.sectionTitle}>
            How LocalServe Works: Verified Profiles, Locked Pricing, Protected Escrow
            <EditorialBurstAccent size={24} className={styles.editorialBurstInline} />
          </h2>
          <p className={styles.sectionSubtitle}>
            Connecting Sri Lankan customers with independent, Admin-approved providers through
            atomic slot scheduling, contact-masked chat, and escrow-protected payouts.
          </p>
        </div>

        {/* 3 Interactive Step Cards with Glowing Progress Connector */}
        <div className={styles.stepsTrackWrap}>
          <div className={styles.stepsConnectorLine} aria-hidden="true">
            <span className={styles.stepsConnectorGlow} />
          </div>

          <div className={styles.stepsGrid}>
            {HOW_IT_WORKS_STEPS.map((step) => (
              <article key={step.number} className={styles.stepCard}>
                <div className={styles.stepMediaWrap}>
                  <img
                    src={step.imageUrl}
                    alt={step.imageAlt}
                    className={styles.stepPhoto}
                    loading="lazy"
                  />
                  <div className={styles.stepMediaScrim} aria-hidden="true" />
                  <span className={styles.stepBadgeOverlay}>Step {step.number}</span>
                  <span className={styles.stepIconWrapOverlay}>
                    <CategoryTradeIcon iconKey={step.icon} size={19} />
                  </span>
                </div>

                <div className={styles.stepCardBody}>
                  <h3 className={styles.stepTitle}>{step.title}</h3>
                  <p className={styles.stepDesc}>{step.description}</p>

                  <div className={styles.stepFooter}>
                    <span className={styles.stepHighlight}>
                      <CheckCircleIcon size={14} />
                      <span>{step.highlightLeft}</span>
                    </span>
                    <span className={styles.stepMetaRight}>{step.highlightRight}</span>
                  </div>
                </div>
              </article>
            ))}
          </div>
        </div>

        {/* Interactive Escrow Protection Simulator */}
        <div
          className={styles.escrowSimCard}
          role="region"
          aria-label="Interactive Escrow Protection Simulator"
        >
          <div className={styles.escrowSimHeader}>
            <div className={styles.escrowSimTitleGroup}>
              <span className={styles.escrowSimIcon}>
                <ShieldCheckIcon size={20} />
              </span>
              <div>
                <h3 className={styles.escrowSimTitle}>Escrow &amp; Dispute Lifecycle Walkthrough</h3>
                <p className={styles.escrowSimSub}>
                  How LocalServe protects every booking with{' '}
                  <strong>escrow hold, 48-hour review, and 72-hour dispute protection</strong>
                </p>
              </div>
            </div>

            <div
              className={styles.escrowStageTabs}
              role="tablist"
              aria-label="Escrow protection stages"
            >
              {ESCROW_SIMULATOR_STAGES.map((stage, idx) => {
                const selected = idx === activeStageIdx
                return (
                  <button
                    key={stage.id}
                    type="button"
                    role="tab"
                    aria-selected={selected}
                    className={`${styles.escrowStageBtn} ${selected ? styles.escrowStageBtnActive : ''}`}
                    onClick={() => setActiveStageIdx(idx)}
                  >
                    {stage.label}
                  </button>
                )
              })}
            </div>
          </div>

          <div className={styles.escrowSimBody}>
            <div className={styles.escrowSimStatusCol}>
              <span
                className={`${styles.escrowStatusPill} ${isReleased ? styles.escrowStatusReleased : styles.escrowStatusLocked}`}
              >
                {isReleased ? <UnlockIcon size={15} /> : <LockIcon size={15} />}
                <span>{activeStage.statusLabel}</span>
              </span>
              <p className={styles.escrowSimSummary}>{activeStage.summary}</p>
            </div>

            <div className={styles.escrowLedger}>
              <div className={styles.escrowLedgerItem}>
                <span className={styles.escrowLedgerLabel}>Held in LocalServe Escrow</span>
                <strong
                  className={`${styles.escrowLedgerValue} ${!isReleased ? styles.escrowLedgerTeal : ''}`}
                >
                  {activeStage.amountHeld}
                </strong>
              </div>
              <div className={styles.escrowLedgerDivider} aria-hidden="true" />
              <div className={styles.escrowLedgerItem}>
                <span className={styles.escrowLedgerLabel}>Technician Payout</span>
                <strong
                  className={`${styles.escrowLedgerValue} ${isReleased ? styles.escrowLedgerTeal : ''}`}
                >
                  {activeStage.providerReceives}
                </strong>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
