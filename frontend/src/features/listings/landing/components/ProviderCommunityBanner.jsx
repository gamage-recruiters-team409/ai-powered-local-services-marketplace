import { useRef } from 'react'
import { Link } from 'react-router-dom'
import {
  PROVIDER_CONSTELLATION_BUBBLES,
  PROVIDER_CONSTELLATION_TRADES,
} from '../data/landingContent'
import { useInViewOnce } from '../hooks/useSpringScrollProgress'
import {
  ArrowRightIcon,
  CheckCircleIcon,
  EditorialBurstAccent,
  ShieldCheckIcon,
  SparklesIcon,
} from './LandingIcons'
import styles from '../styles/publicLanding.module.css'

const TONE_CLASS = {
  sky: styles.orbitAvatarSky,
  amber: styles.orbitAvatarAmber,
  emerald: styles.orbitAvatarEmerald,
  violet: styles.orbitAvatarViolet,
}

const POSITION_CLASS = {
  topLeft: styles.orbitPosTopLeft,
  topRight: styles.orbitPosTopRight,
  bottomLeft: styles.orbitPosBottomLeft,
  bottomRight: styles.orbitPosBottomRight,
}

const BUBBLE_TONE_CLASS = {
  mint: styles.orbitBubbleMint,
  dark: styles.orbitBubbleDark,
  amber: styles.orbitBubbleAmber,
}

const BUBBLE_POS_CLASS = {
  top: styles.orbitBubbleTop,
  right: styles.orbitBubbleRight,
  bottom: styles.orbitBubbleBottom,
}

export function ProviderCommunityBanner() {
  const sectionRef = useRef(null)
  const inView = useInViewOnce(sectionRef, 0.14)

  return (
    <section
      id="become-a-provider"
      ref={sectionRef}
      className={`${styles.proBannerSection} ${inView ? styles.sectionRevealed : ''}`}
      aria-labelledby="provider-community-heading"
    >
      <div className={styles.sectionContainer}>
        <div className={styles.proBannerCard}>
          {/* Left: SRS-Accurate Provider Onboarding & Listing Pitch */}
          <div className={styles.proBannerCopy}>
            <span className={styles.proBannerEyebrow}>For Certified Sri Lankan Providers</span>
            <h2 id="provider-community-heading" className={styles.proBannerHeading}>
              Skilled Trade Professionals Are{' '}
              <span className={styles.proBannerHeadingAccent}>Growing Here.</span>
              <EditorialBurstAccent size={24} className={styles.editorialBurstInline} />
            </h2>
            <p className={styles.proBannerSubcopy}>
              Register as an individual or business, complete Admin document verification, and
              publish fixed, hourly, or quote LKR packages with up to 8 portfolio photos, optional
              add-ons, weekly slot calendars, and escrow-backed bank payouts.
            </p>

            <div className={styles.proBannerActions}>
              <Link to="/register?role=provider" className={styles.proBannerCta}>
                <span>Join as a Verified Pro</span>
                <ArrowRightIcon size={16} className={styles.proBannerCtaArrow} />
              </Link>

              <div className={styles.proBannerMetaList}>
                <span className={styles.proBannerMetaItem}>
                  <CheckCircleIcon size={14} />
                  <span>Admin-Verified Badge</span>
                </span>
                <span className={styles.proBannerMetaItem}>
                  <ShieldCheckIcon size={14} />
                  <span>Transparent Commission &amp; Bank Payouts</span>
                </span>
              </div>
            </div>
          </div>

          {/* Right: Floating Trade & SRS Capability Constellation (Zero Fake People) */}
          <div className={styles.orbitStage} aria-hidden="true">
            <div className={styles.orbitRingInner} />
            <div className={styles.orbitRingOuter} />

            <div className={styles.orbitCenterBadge}>
              <ShieldCheckIcon size={30} className={styles.orbitCenterIcon} />
              <span className={styles.orbitCenterLabel}>VERIFIED</span>
            </div>

            {PROVIDER_CONSTELLATION_TRADES.map((trade) => (
              <div
                key={trade.id}
                className={`${styles.orbitAvatarWrap} ${POSITION_CLASS[trade.position]}`}
              >
                <span className={`${styles.orbitAvatarCircle} ${TONE_CLASS[trade.tone]}`}>
                  {trade.code}
                </span>
                <span className={styles.orbitAvatarCaption}>
                  <strong>{trade.label}</strong>
                  <small>{trade.detail}</small>
                </span>
              </div>
            ))}

            {PROVIDER_CONSTELLATION_BUBBLES.map((bubble) => (
              <div
                key={bubble.id}
                className={`${styles.orbitBubble} ${BUBBLE_TONE_CLASS[bubble.tone]} ${
                  BUBBLE_POS_CLASS[bubble.position]
                }`}
              >
                {bubble.tone === 'dark' ? (
                  <SparklesIcon size={12} className={styles.warmAmberStar} />
                ) : (
                  <CheckCircleIcon size={12} />
                )}
                <span>{bubble.text}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}
