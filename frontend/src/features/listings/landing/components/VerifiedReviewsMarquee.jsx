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
  error = false,
  onRetry,
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
      ) : requiresAuth ? (
        <div className={styles.sectionContainer}>
          <div className={styles.reviewsEmptyCard} role="status">
            <div className={styles.reviewsEmptyStars} aria-hidden="true">
              {STAR_SLOTS.map((slot) => (
                <StarIcon key={slot} size={18} className={styles.warmAmberStar} />
              ))}
            </div>
            <h3 className={styles.reviewsEmptyTitle}>
              Sign In to View Verified Customer Reviews
            </h3>
            <p className={styles.reviewsEmptyCopy}>
              Published customer reviews from completed bookings are available when signed in. Sign
              in to read verified provider ratings or continue browsing active local services.
            </p>
            <div className={styles.emptyActionRow}>
              <Link to="/login" className={styles.directoryPrimaryLink}>
                <span>Sign In to View Reviews</span>
                <ArrowRightIcon size={14} className={styles.reviewsEmptyBtnIcon} />
              </Link>
              <Link to="/search" className={styles.directorySecondaryLink}>
                Browse Verified Services
              </Link>
            </div>
          </div>
        </div>
      ) : error ? (
        <div className={styles.sectionContainer}>
          <div className={styles.reviewsEmptyCard} role="status">
            <div className={styles.reviewsEmptyStars} aria-hidden="true">
              {STAR_SLOTS.map((slot) => (
                <StarIcon key={slot} size={18} className={styles.warmAmberStar} />
              ))}
            </div>
            <h3 className={styles.reviewsEmptyTitle}>
              Unable to Load Customer Reviews Right Now
            </h3>
            <p className={styles.reviewsEmptyCopy}>
              We could not retrieve published customer reviews from the server right now. Retry
              loading reviews or browse verified local services.
            </p>
            <div className={styles.emptyActionRow}>
              {onRetry && (
                <button type="button" className={styles.directoryClearBtn} onClick={onRetry}>
                  Retry Loading Reviews
                </button>
              )}
              <Link to="/search" className={styles.directorySecondaryLink}>
                Browse Verified Services
              </Link>
            </div>
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
              Verified customers can leave one 1–5 star review after each completed booking. Real
              customer feedback for providers will appear here automatically as jobs complete.
            </p>
            <div className={styles.emptyActionRow}>
              <Link to="/search" className={styles.directoryPrimaryLink}>
                <span>Browse Verified Services</span>
                <ArrowRightIcon size={14} className={styles.reviewsEmptyBtnIcon} />
              </Link>
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
