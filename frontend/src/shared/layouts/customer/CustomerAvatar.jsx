import { useState } from 'react'

import styles from './CustomerLayout.module.css'
import { getInitials } from './customerLayoutUtils'

/**
 * Presentational customer avatar component.
 * Displays an image if a valid src string is provided and hasn't failed to load.
 * Falls back to initials when src is missing, empty, or fails to load.
 */
export function CustomerAvatar({ name, src, className }) {
  const [failedSrc, setFailedSrc] = useState(null)

  const hasImage = typeof src === 'string' && src.trim() !== '' && failedSrc !== src

  return (
    <span className={className} aria-hidden="true">
      {hasImage ? (
        <img
          src={src}
          alt=""
          className={styles.avatarImage}
          onError={() => setFailedSrc(src)}
        />
      ) : (
        getInitials(name)
      )}
    </span>
  )
}
