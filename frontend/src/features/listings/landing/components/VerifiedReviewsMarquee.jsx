import { VERIFIED_REVIEWS } from '../data/landingContent'
import { CheckCircleIcon, StarIcon } from './LandingIcons'
import styles from '../styles/publicLanding.module.css'

function ReviewCard({ review, duplicate = false }) {
  return (
    <article
      className={styles.reviewCard}
      aria-hidden={duplicate || undefined}
    >
      <div className={styles.reviewTop}>
        <div className={styles.reviewAuthor}>
          <span className={styles.reviewAvatar} aria-hidden="true">
            {review.initials}
          </span>
          <div>
            <h3 className={styles.reviewName}>{review.customerName}</h3>
            <p className={styles.reviewLocation}>{review.location}</p>
          </div>
        </div>

        <span className={styles.reviewRatingPill} aria-label={`${review.rating} out of 5 stars`}>
          <StarIcon size={13} />
          <span>{review.rating.toFixed(1)}</span>
        </span>
      </div>

      <span className={styles.reviewServiceTag}>
        <CheckCircleIcon size={13} />
        <span>{review.serviceTag}</span>
      </span>

      <blockquote className={styles.reviewQuote}>&ldquo;{review.quote}&rdquo;</blockquote>

      <p className={styles.reviewMeta}>{review.meta}</p>
    </article>
  )
}

export function VerifiedReviewsMarquee() {
  return (
    <section
      id="verified-reviews"
      className={styles.reviewsSection}
      aria-labelledby="verified-reviews-heading"
    >
      <div className={styles.sectionContainer}>
        <div className={styles.sectionHeader}>
          <span className={styles.sectionPill}>Verified Jobs Across Sri Lanka</span>
          <h2 id="verified-reviews-heading" className={styles.sectionTitle}>
            Verified Customer Reviews (Direct Booking Experiences)
          </h2>
          <p className={styles.sectionSubtitle}>
            Authentic feedback from real homeowners with escrow-protected direct bookings across
            Colombo, Kandy, Galle, Negombo, Dehiwala, and Gampaha.
          </p>
        </div>
      </div>

      <div className={styles.marqueeViewport} aria-label="Verified customer reviews carousel">
        <div className={styles.marqueeTrack}>
          {VERIFIED_REVIEWS.map((review) => (
            <ReviewCard key={review.id} review={review} />
          ))}
          {VERIFIED_REVIEWS.map((review) => (
            <ReviewCard key={`${review.id}-dup`} review={review} duplicate />
          ))}
        </div>
      </div>
    </section>
  )
}
