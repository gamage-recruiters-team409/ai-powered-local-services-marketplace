import { useState } from 'react'

import { resolveCategoryIconAsset } from '../services/listingService'
import styles from '../styles/providerListings.module.css'

/**
 * Smart cover media renderer for Provider Listings cards, table rows, and Step 4 preview.
 * Displays real uploaded photos with smooth fade-in, and falls back gracefully to a clean,
 * light architectural studio backdrop featuring that listing's TRUE 3D category SVG icon
 * (eliminating wrong-trade "Plumbing & Pipe Leak Repair" banners on broken/missing URLs).
 */
export function ListingCoverMedia({
  src,
  alt = 'Service listing cover',
  categoryName = 'Service Category',
  categoryIcon = '',
  isDraft = false,
  dimmed = false,
  compact = false,
}) {
  const [failedSrc, setFailedSrc] = useState('')
  const [loadedSrc, setLoadedSrc] = useState('')

  const trimmedSrc = typeof src === 'string' ? src.trim() : ''
  const hasUsableSrc = Boolean(trimmedSrc) && failedSrc !== trimmedSrc
  const isImageLoaded = hasUsableSrc && loadedSrc === trimmedSrc

  const categoryAsset = resolveCategoryIconAsset({
    name: categoryName,
    icon: categoryIcon,
  })

  if (!hasUsableSrc) {
    return (
      <div
        className={`${styles.studioCoverFallback} ${compact ? styles.studioCoverCompact : ''} ${
          dimmed ? styles.cardImageDimmed : ''
        }`}
        role="img"
        aria-label={alt}
        data-testid="studio-cover-fallback"
      >
        <div className={styles.studioCoverTile}>
          <img
            src={categoryAsset}
            alt=""
            aria-hidden="true"
            className={compact ? styles.studioCoverIconCompact : styles.studioCoverIcon}
          />
        </div>
        {!compact && (
          <span className={styles.studioCoverCaption}>
            {isDraft ? 'No cover photo yet' : categoryName || 'Service Portfolio'}
          </span>
        )}
      </div>
    )
  }

  return (
    <img
      src={trimmedSrc}
      alt={alt}
      loading="lazy"
      className={`${compact ? styles.tableThumb : styles.cardImage} ${
        isImageLoaded ? styles.cardImageLoaded : ''
      } ${dimmed ? styles.cardImageDimmed : ''}`}
      onLoad={() => setLoadedSrc(trimmedSrc)}
      onError={() => setFailedSrc(trimmedSrc)}
    />
  )
}
