import { useState } from 'react'

import CompletionIcon from './CompletionIcons'

const TONES = ['teal', 'indigo', 'amber']

/**
 * Shows a completion photo. Falls back to a coloured placeholder tile when the
 * image cannot be loaded (for example mock:// URLs before the media service exists).
 */
export default function PhotoThumb({ url, index = 0, size = 'medium', label }) {
  const [failed, setFailed] = useState(!url || url.startsWith('mock://'))
  const tone = TONES[index % TONES.length]

  return (
    <span className={`sc-photo sc-photo--${size} sc-photo--${tone}`}>
      {failed ? (
        <>
          <CompletionIcon name="image" size={size === 'large' ? 64 : 28} />
          <span className="sc-visually-hidden">{label ?? `Photo ${index + 1}`}</span>
        </>
      ) : (
        <img src={url} alt={label ?? `Completion photo ${index + 1}`} onError={() => setFailed(true)} />
      )}
    </span>
  )
}
