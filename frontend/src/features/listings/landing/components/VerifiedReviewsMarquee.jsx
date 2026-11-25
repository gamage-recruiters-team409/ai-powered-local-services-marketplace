import { useRef } from 'react'
import { Link } from 'react-router-dom'
import { useInViewOnce } from '../hooks/useSpringScrollProgress'
import {
  ArrowRightIcon,
  CheckCircleIcon,
  EditorialBurstAccent,
  StarIcon,
} from './LandingIcons'
import styles from '../styles/publicLanding.module.css'

const STAR_SLOTS = [1, 2, 3, 4, 5]

function ReviewCard({ review, duplicate = false }) {
  const filledStars = Math.max(1, Math.min(5, Math.round(review.rating || 5)))

  return (
    <article
      className={styles.reviewCardEditorial}
      aria-hidden={duplicate || undefined}
    >
      <div className={styles.reviewQuoteHeader}>
        <span className={styles.reviewOversizedQuote} aria-hidden="true">
          &ldquo;
        </span>
        <div
          className={styles.reviewStarGroup}
          aria-label={`${Number(review.rating || 5).toFixed(1)} out of 5 stars`}
        >
          <span className={styles.reviewStarsRow} aria-hidden="true">
            {STAR_SLOTS.map((slot) => (
              <StarIcon
                key={slot}
                size={14}
                className={slot <= filledStars ? styles.warmAmberStar : styles.mutedStar}
              />
            ))}
          </span>
          <span className={styles.reviewRatingScore}>
            {Number(review.rating || 5).toFixed(1)}
          </span>
        </div>
      </div>

      <span className={styles.reviewServiceTag}>
        <CheckCircleIcon size={13} />
        <span>
          {review.serviceTag} · {review.providerName}
        </span>
      </span>

      <blockquote className={styles.reviewQuote}>&ldquo;{review.quote}&rdquo;</blockquote>

      <div className={styles.reviewEditorialFooter}>
        <div className={styles.reviewAuthor}>
          <span className={styles.reviewAvatar} aria-hidden="true">
            {review.initials}
          </span>
          <div>
            <h3 className={styles.reviewName}>{review.customerName}</h3>
            <p className={styles.reviewLocation}>{review.location}</p>
          </div>
        </div>
        <p className={styles.reviewMetaCompact}>{review.meta}</p>
      </div>
    </article>
  )
}

export function VerifiedReviewsMarquee({
  reviews = [],
  loading = false,
  requiresAuth = false,
}) {
  const sectionRef = useRef(null)
  const inView = useInViewOnce(sectionRef, 0.14)
  const list = Array.isArray(reviews) ? reviews : []
  const useMarquee = list.length >= 3

  return (
    <section
      id="verified-reviews"
      ref={sectionRef}
      className={`${styles.reviewsSection} ${inView ? styles.sectionRevealed : ''}`}
      aria-labelledby="verified-reviews-heading"
    >
      <div className={styles.sectionContainer}>
        <div className={styles.sectionHeader}>
          <span className={styles.sectionPill}>Customer-to-Provider Ratings</span>
          <h2 id="verified-reviews-heading" className={styles.sectionTitle}>
            Verified Customer Reviews
            <EditorialBurstAccent size={24} className={styles.editorialBurstInline} />
          </h2>
          <p className={styles.sectionSubtitle}>
            Authentic 1–5 star ratings and feedback submitted by customers after completed bookings
            with verified Sri Lankan service providers.
          </p>
        </div>
      </div>

      {loading ? (
        <div className={styles.sectionContainer}>
          <div className={styles.featuredGrid} aria-busy="true" aria-label="Loading verified reviews">
            {[0, 1, 2].map((slot) => (
              <div key={slot} className={styles.liveSkeletonCard}>
                <div className={styles.liveSkeletonLineSm} />
                <div className={styles.liveSkeletonLineLg} />
                <div className={styles.liveSkeletonLineSm} />
              </div>
            ))}
          </div>
        </div>
      ) : list.length === 0 ? (
        <div className={styles.sectionContainer}>
          <div className={styles.reviewsEmptyCard} role="status">
            <div className={styles.reviewsEmptyStars} aria-hidden="true">
              {STAR_SLOTS.map((slot) => (
                <StarIcon key={slot} size={18} className={styles.warmAmberStar} />
              ))}
            </div>
            <h3 className={styles.reviewsEmptyTitle}>No Customer Reviews Published Yet</h3>
            <p className={styles.reviewsEmptyCopy}>
              {requiresAuth
                ? 'Customer reviews for verified providers are written strictly after completed bookings. Sign in to view published provider reviews or explore active listings.'
                : 'Verified customers can leave one 1–5 star review after each completed booking. Real customer feedback for providers will appear here automatically as jobs complete.'}
            </p>
            <div className={styles.emptyActionRow}>
              <Link to="/search" className={styles.directoryPrimaryLink}>
                <span>Browse Verified Services</span>
                <ArrowRightIcon size={14} className={styles.reviewsEmptyBtnIcon} />
              </Link>
              {requiresAuth && (
                <Link to="/login" className={styles.directorySecondaryLink}>
                  Sign In to View Reviews
                </Link>
              )}
            </div>
          </div>
        </div>
      ) : useMarquee ? (
        <div
          className={styles.marqueeViewport}
          role="region"
          aria-label="Verified customer reviews marquee"
        >
          <div className={styles.marqueeTrack}>
            {list.map((review) => (
              <ReviewCard key={review.id} review={review} />
            ))}
            {list.map((review) => (
              <ReviewCard key={`${review.id}-dup`} review={review} duplicate />
            ))}
          </div>
        </div>
      ) : (
        <div className={styles.sectionContainer}>
          <div
            className={styles.reviewsStaticGrid}
            role="region"
            aria-label="Verified customer reviews list"
          >
            {list.map((review) => (
              <ReviewCard key={review.id} review={review} />
            ))}
          </div>
        </div>
      )}
    </section>
  )
}
