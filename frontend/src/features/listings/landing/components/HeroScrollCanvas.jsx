import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { PUBLIC_CITIES } from '../../../../shared/layouts/public'
import {
  HERO_FRAME_CONFIG,
  ROTATING_PLACEHOLDERS,
  buildFrameUrl,
} from '../data/landingContent'
import { useSpringScrollProgress } from '../hooks/useSpringScrollProgress'
import {
  ArrowRightIcon,
  CheckCircleIcon,
  SearchIcon,
  TrustEscrowVaultIcon,
  TrustPriceLockIcon,
  TrustVerifiedSealIcon,
} from './LandingIcons'
import styles from '../styles/publicLanding.module.css'

function clamp01(value) {
  return Math.min(Math.max(value, 0), 1)
}

function rangeProgress(value, start, end) {
  if (end <= start) return value >= end ? 1 : 0
  return clamp01((value - start) / (end - start))
}

export function HeroScrollCanvas({
  selectedCity = 'Colombo',
  onCityChange,
  cities = PUBLIC_CITIES,
  liveDistricts = [],
  liveListingCount = null,
  districtCount = 25,
}) {
  const navigate = useNavigate()
  const containerRef = useRef(null)
  const canvasRef = useRef(null)
  const framesRef = useRef([])
  const posterRef = useRef(null)
  const latestProgressRef = useRef(0)

  // Direct DOM refs for 60fps zero-re-render beat choreography
  const beat1Ref = useRef(null)
  const readabilityGradientRef = useRef(null)
  const beat2Ref = useRef(null)
  const beat2HeadingRef = useRef(null)
  const badge1Ref = useRef(null)
  const badge2Ref = useRef(null)
  const badge3Ref = useRef(null)
  const handoffOverlayRef = useRef(null)
  const progressFillRef = useRef(null)
  const scrollHintRef = useRef(null)

  const [searchQuery, setSearchQuery] = useState('')
  const [typedPlaceholder, setTypedPlaceholder] = useState(ROTATING_PLACEHOLDERS[0])
  const [firstPaintReady, setFirstPaintReady] = useState(false)
  const [preloadPercent, setPreloadPercent] = useState(0)

  const activeCityObj = useMemo(() => {
    if (!selectedCity || selectedCity === 'All Districts') {
      return {
        id: 'All Districts',
        label: 'All Districts',
        region: `Islandwide (${districtCount} Districts)`,
        isIslandwide: true,
      }
    }
    const presetMatch = cities.find((c) => c.id === selectedCity)
    if (presetMatch) {
      return { ...presetMatch, isIslandwide: false }
    }
    const liveMatch = Array.isArray(liveDistricts)
      ? liveDistricts.find((d) => d.city === selectedCity)
      : null
    if (liveMatch) {
      return {
        id: liveMatch.city,
        label: liveMatch.city,
        region: liveMatch.province || 'Sri Lanka',
        isIslandwide: false,
      }
    }
    return {
      id: selectedCity,
      label: selectedCity,
      region: 'Sri Lanka',
      isIslandwide: false,
    }
  }, [cities, districtCount, liveDistricts, selectedCity])

  const heroCityPills = useMemo(() => {
    if (activeCityObj.isIslandwide || cities.some((c) => c.id === activeCityObj.id)) {
      return cities
    }
    return [{ id: activeCityObj.id, label: activeCityObj.label, region: activeCityObj.region }, ...cities]
  }, [activeCityObj, cities])

  // Animated typing / rotating placeholder in the search input
  useEffect(() => {
    const prefersReduced =
      typeof window !== 'undefined' &&
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    if (prefersReduced) {
      return undefined
    }

    let wordIdx = 0
    let charIdx = ROTATING_PLACEHOLDERS[0].length
    let deleting = true
    let timerId = null

    const tick = () => {
      const currentWord = ROTATING_PLACEHOLDERS[wordIdx]
      if (!deleting) {
        charIdx += 1
        setTypedPlaceholder(currentWord.slice(0, charIdx))
        if (charIdx === currentWord.length) {
          deleting = true
          timerId = window.setTimeout(tick, 1800)
          return
        }
        timerId = window.setTimeout(tick, 68)
      } else {
        charIdx -= 1
        setTypedPlaceholder(currentWord.slice(0, Math.max(charIdx, 0)))
        if (charIdx <= 0) {
          deleting = false
          wordIdx = (wordIdx + 1) % ROTATING_PLACEHOLDERS.length
          timerId = window.setTimeout(tick, 280)
          return
        }
        timerId = window.setTimeout(tick, 34)
      }
    }

    timerId = window.setTimeout(tick, 2000)
    return () => {
      if (timerId !== null) window.clearTimeout(timerId)
    }
  }, [])

  // Draw the current frame (or high-DPI cinematic zoom/pan over poster fallback) onto the canvas
  const drawCanvasFrame = useCallback((progress) => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext?.('2d')
    if (!ctx) return

    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const cssWidth = canvas.clientWidth || window.innerWidth || 1280
    const cssHeight = canvas.clientHeight || window.innerHeight || 720
    const targetW = Math.round(cssWidth * dpr)
    const targetH = Math.round(cssHeight * dpr)

    if (canvas.width !== targetW || canvas.height !== targetH) {
      canvas.width = targetW
      canvas.height = targetH
    }

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, cssWidth, cssHeight)

    const { frameCount } = HERO_FRAME_CONFIG
    let img = null
    let isPosterFallback = false

    if (frameCount > 0) {
      const exactIdx = Math.min(
        frameCount - 1,
        Math.max(0, Math.round(progress * (frameCount - 1)))
      )
      img = framesRef.current[exactIdx]
      // Fall back to nearest already-loaded frame while progressive loading finishes
      if (!img || !img.complete || !img.naturalWidth) {
        for (let offset = 1; offset < frameCount; offset += 1) {
          const prev = framesRef.current[exactIdx - offset]
          if (prev && prev.complete && prev.naturalWidth) {
            img = prev
            break
          }
          const next = framesRef.current[exactIdx + offset]
          if (next && next.complete && next.naturalWidth) {
            img = next
            break
          }
        }
      }
    }

    if (!img || !img.complete || !img.naturalWidth) {
      img = posterRef.current
      isPosterFallback = true
    }

    if (!img || !img.complete || !img.naturalWidth) {
      // Soft architectural mint-white backdrop while frame 1 / poster is still decoding
      const bg = ctx.createLinearGradient(0, 0, cssWidth, cssHeight)
      bg.addColorStop(0, '#ffffff')
      bg.addColorStop(0.55, '#f0fdfa')
      bg.addColorStop(1, '#ccfbf1')
      ctx.fillStyle = bg
      ctx.fillRect(0, 0, cssWidth, cssHeight)
      return
    }

    // Cover-fit math (zero stretching and zero edge gaps on any aspect ratio)
    const baseScale = Math.max(cssWidth / img.naturalWidth, cssHeight / img.naturalHeight)
    // Gentle Apple-style dolly-in zoom so the technician's full head & smile stay cleanly framed
    const cinematicZoom = isPosterFallback ? 1.04 + progress * 0.06 : 1 + progress * 0.03
    const scale = baseScale * cinematicZoom
    const drawW = img.naturalWidth * scale
    const drawH = img.naturalHeight * scale
    const isDesktop = cssWidth >= 900
    const maxHorizontalShift = Math.max(0, (drawW - cssWidth) * 0.5)
    const desiredBias = isPosterFallback && isDesktop ? cssWidth * 0.06 * (1 - progress * 0.55) : 0
    const horizontalBias = Math.min(desiredBias, maxHorizontalShift)
    const drawX = Math.min(0, Math.max(cssWidth - drawW, (cssWidth - drawW) * 0.5 + horizontalBias))
    // Anchor close to the top (0.04) so the technician's forehead & hair always have generous headroom below the navbar
    const drawY = Math.min(0, (cssHeight - drawH) * 0.04)

    ctx.imageSmoothingEnabled = true
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(img, drawX, drawY, drawW, drawH)
  }, [])

  // Preload poster + first 15 numbered frames immediately, then progressively load remaining frames
  useEffect(() => {
    let cancelled = false
    const { frameCount, eagerPreloadCount, posterUrl } = HERO_FRAME_CONFIG

    const poster = new window.Image()
    poster.decoding = 'async'
    poster.onload = () => {
      if (cancelled) return
      posterRef.current = poster
      setFirstPaintReady(true)
      setPreloadPercent(frameCount === 0 ? 100 : 15)
      drawCanvasFrame(latestProgressRef.current)
    }
    poster.onerror = () => {
      if (cancelled) return
      setFirstPaintReady(true)
      setPreloadPercent(100)
    }
    poster.src = posterUrl

    if (frameCount <= 0) {
      return () => {
        cancelled = true
      }
    }

    framesRef.current = new Array(frameCount)
    let loadedCount = 0
    const eagerCount = Math.min(eagerPreloadCount, frameCount)

    const loadFrame = (index) =>
      new Promise((resolve) => {
        const img = new window.Image()
        img.decoding = 'async'
        img.onload = () => {
          if (cancelled) return resolve()
          framesRef.current[index] = img
          loadedCount += 1
          setPreloadPercent(Math.round((loadedCount / frameCount) * 100))
          if (index === 0) {
            setFirstPaintReady(true)
          }
          drawCanvasFrame(latestProgressRef.current)
          resolve()
        }
        img.onerror = () => resolve()
        img.src = buildFrameUrl(index)
      })

    const loadAll = async () => {
      await Promise.all(Array.from({ length: eagerCount }, (_, i) => loadFrame(i)))
      // Progressively preload remaining frames in gentle batches of 8
      for (let start = eagerCount; start < frameCount && !cancelled; start += 8) {
        const batch = []
        for (let i = start; i < Math.min(start + 8, frameCount); i += 1) {
          batch.push(loadFrame(i))
        }
        await Promise.all(batch)
      }
    }

    loadAll()

    return () => {
      cancelled = true
    }
  }, [drawCanvasFrame])

  // Redraw canvas cleanly on viewport resize
  useEffect(() => {
    const onResize = () => drawCanvasFrame(latestProgressRef.current)
    window.addEventListener('resize', onResize, { passive: true })
    return () => window.removeEventListener('resize', onResize)
  }, [drawCanvasFrame])

  // Apply 3 choreographed scroll beats at 60fps directly on DOM refs + canvas
  const handleSpringProgress = useCallback(
    (progress) => {
      latestProgressRef.current = progress
      drawCanvasFrame(progress)

      // Beat 1: 0% – 30% (fully visible at 0%, glides upward & fades out between 16% and 32%)
      const beat1Out = rangeProgress(progress, 0.14, 0.32)
      const beat1Opacity = 1 - beat1Out
      if (beat1Ref.current) {
        beat1Ref.current.style.opacity = String(beat1Opacity)
        beat1Ref.current.style.transform = `translate3d(0, ${-beat1Out * 48}px, 0)`
        beat1Ref.current.style.pointerEvents = beat1Opacity > 0.15 ? 'auto' : 'none'
      }
      if (readabilityGradientRef.current) {
        readabilityGradientRef.current.style.opacity = String(0.38 + beat1Opacity * 0.62)
      }

      // Beat 2: 20% – 74% (Apple Keynote Spatial Callouts gliding in at staggered heights)
      const beat2FadeOut = rangeProgress(progress, 0.72, 0.88)
      const spatialConfigs = [
        { ref: beat2HeadingRef, start: 0.18, end: 0.31, xFrom: -18, yFrom: 18 },
        { ref: badge1Ref, start: 0.23, end: 0.37, xFrom: -22, yFrom: 22 },
        { ref: badge2Ref, start: 0.29, end: 0.43, xFrom: -18, yFrom: 26 },
        { ref: badge3Ref, start: 0.35, end: 0.49, xFrom: 22, yFrom: 20 },
      ]
      let anyBadgeVisible = false
      spatialConfigs.forEach(({ ref, start, end, xFrom, yFrom }) => {
        if (!ref.current) return
        const enter = rangeProgress(progress, start, end)
        const opacity = enter * (1 - beat2FadeOut)
        if (opacity > 0.05) anyBadgeVisible = true
        const translateX = (1 - enter) * xFrom
        const translateY = (1 - enter) * yFrom - beat2FadeOut * 18
        const scale = 0.96 + enter * 0.04
        ref.current.style.opacity = String(opacity)
        ref.current.style.transform = `translate3d(${translateX}px, ${translateY}px, 0) scale(${scale})`
      })
      if (beat2Ref.current) {
        beat2Ref.current.style.pointerEvents = anyBadgeVisible ? 'auto' : 'none'
      }

      // Beat 3: 72% – 100% (smooth white gradient handoff into the Trust Metrics Strip below)
      const handoff = rangeProgress(progress, 0.72, 0.98)
      if (handoffOverlayRef.current) {
        handoffOverlayRef.current.style.opacity = String(handoff)
      }

      // Slim vertical scroll-progress indicator
      if (progressFillRef.current) {
        progressFillRef.current.style.transform = `scaleY(${Math.max(progress, 0.04)})`
      }

      // Fade out bottom "Scroll to explore" cue once scrolling starts
      if (scrollHintRef.current) {
        scrollHintRef.current.style.opacity = String(1 - rangeProgress(progress, 0.02, 0.14))
      }
    },
    [drawCanvasFrame]
  )

  useSpringScrollProgress(containerRef, handleSpringProgress, {
    stiffness: 180,
    damping: 30,
  })

  const handleSearchSubmit = (event) => {
    event.preventDefault()
    const params = new URLSearchParams()
    if (searchQuery.trim()) {
      params.set('q', searchQuery.trim())
    }
    if (selectedCity && selectedCity !== 'All Districts') {
      params.set('location', selectedCity)
    }
    const qs = params.toString()
    navigate(qs ? `/search?${qs}` : '/search')
  }

  return (
    <section
      ref={containerRef}
      className={styles.heroScrollTrack}
      aria-label="LocalServe Verified Home Services Showcase"
    >
      <div className={styles.heroStickyViewport}>
        {/* High-DPI Full-Bleed Canvas */}
        <canvas
          ref={canvasRef}
          className={styles.heroCanvas}
          aria-hidden="true"
        />

        {/* Minimal First-Paint Loader */}
        {!firstPaintReady && (
          <div className={styles.heroLoader} role="status" aria-label="Loading visual scene">
            <span className={styles.heroLoaderWordmark}>
              <span className={styles.wordmarkNavy}>Local</span>
              <span className={styles.wordmarkTeal}>Serve</span>
            </span>
            <div className={styles.heroLoaderTrack}>
              <div
                className={styles.heroLoaderFill}
                style={{ width: `${Math.max(preloadPercent, 18)}%` }}
              />
            </div>
          </div>
        )}

        {/* Left-to-Right Readability Gradient Overlay for Beat 1 */}
        <div
          ref={readabilityGradientRef}
          className={styles.heroReadabilityGradient}
          aria-hidden="true"
        />

        {/* BEAT 1 (0% – 30%): Immediately Interactive Hero Block */}
        <div className={styles.heroContentShell}>
          <div ref={beat1Ref} className={styles.heroBeat1}>
            <div className={styles.heroEyebrow}>
              <span className={styles.liveDot} aria-hidden="true" />
              <span>
                {typeof liveListingCount === 'number' && liveListingCount > 0 ? (
                  activeCityObj.isIslandwide ? (
                    <>
                      <strong>{liveListingCount}</strong> verified active{' '}
                      {liveListingCount === 1 ? 'service' : 'services'} across{' '}
                      <strong>Sri Lanka</strong>
                    </>
                  ) : (
                    <>
                      <strong>{liveListingCount}</strong> verified active{' '}
                      {liveListingCount === 1 ? 'service' : 'services'} in{' '}
                      <strong>{activeCityObj.label}</strong>
                    </>
                  )
                ) : activeCityObj.isIslandwide ? (
                  <>
                    Admin-verified local services · <strong>Islandwide</strong> ({districtCount}{' '}
                    Districts)
                  </>
                ) : (
                  <>
                    Admin-verified local services · <strong>{activeCityObj.label}</strong> (
                    {activeCityObj.region})
                  </>
                )}
              </span>
            </div>

            <h1 className={styles.heroHeadline}>
              Hire <span className={styles.heroUnderline}>verified pros</span> with absolute
              certainty.
            </h1>

            <p className={styles.heroSubtext}>
              Compare admin-verified providers, upfront price-locked LKR rates, and escrow
              protection with a <strong>72-hour dispute window</strong> across{' '}
              <strong>{districtCount} Sri Lankan districts</strong>.
            </p>

            {/* Elevated Interactive Search Bar */}
            <form
              className={styles.heroSearchCard}
              onSubmit={handleSearchSubmit}
              role="search"
              aria-label="Find a verified local service"
            >
              <div className={styles.heroSearchInputRow}>
                <SearchIcon size={20} className={styles.heroSearchIcon} />
                <div className={styles.heroSearchFieldWrap}>
                  <label htmlFor="hero-service-search" className={styles.heroSearchMiniLabel}>
                    SERVICE, TRADE, OR ISSUE
                  </label>
                  <input
                    id="hero-service-search"
                    type="text"
                    className={styles.heroSearchInput}
                    placeholder={typedPlaceholder || 'Electrician...'}
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    autoComplete="off"
                  />
                </div>
                <button type="submit" className={styles.heroSearchSubmit}>
                  <span>Find Service</span>
                  <ArrowRightIcon size={16} />
                </button>
              </div>

              {/* Interactive City Filter Pills */}
              <div className={styles.heroCityRow}>
                <span className={styles.heroCityLabel}>Coverage:</span>
                <div className={styles.heroCityPills} role="group" aria-label="Select service city">
                  {heroCityPills.map((city) => {
                    const active = city.id === activeCityObj.id
                    return (
                      <button
                        key={city.id}
                        type="button"
                        className={`${styles.heroCityPill} ${active ? styles.heroCityPillActive : ''}`}
                        aria-pressed={active}
                        onClick={() => onCityChange?.(city.id)}
                      >
                        {city.label}
                      </button>
                    )
                  })}
                </div>
              </div>

              <div className={styles.heroTrustCaption}>
                <CheckCircleIcon size={15} className={styles.heroTrustIcon} />
                <span>
                  <strong>Admin-Verified Providers</strong> · Price-locked LKR bookings with 48h
                  review &amp; 72h dispute protection
                </span>
              </div>
            </form>
          </div>
        </div>

        {/* BEAT 2 (20% – 74%): Concept B — Apple Keynote Spatial Callouts Framed Around Technician */}
        <div ref={beat2Ref} className={styles.heroBeat2Layer} aria-label="Platform trust highlights">
          <div className={styles.heroBeat2Spatial}>
            <div className={styles.heroBeat2LeftRail}>
              <div ref={beat2HeadingRef} className={styles.beat2EditorialHeader}>
                <span className={styles.beat2Eyebrow}>
                  <span className={styles.beat2EyebrowDot} aria-hidden="true" />
                  Verified Local Marketplace
                </span>
                <h2 className={styles.beat2Headline}>
                  Built for absolute <span className={styles.beat2HeadlineAccent}>certainty.</span>
                </h2>
                <p className={styles.beat2Subcopy}>
                  Every provider manually audited by Admin. Every booking protected in escrow.
                </p>
              </div>

              <div ref={badge1Ref} className={`${styles.glassBadge} ${styles.spatialCalloutPrimary}`}>
                <span className={`${styles.glassBadgeIcon} ${styles.glassBadgeIconTeal}`}>
                  <TrustVerifiedSealIcon size={26} />
                </span>
                <div>
                  <p className={styles.glassBadgeTitle}>100% Admin-Verified Providers</p>
                  <p className={styles.glassBadgeSub}>NIC, business &amp; trade documents audited before publishing</p>
                </div>
                <span className={styles.spatialAnchorDot} aria-hidden="true" />
              </div>

              <div ref={badge2Ref} className={`${styles.glassBadge} ${styles.spatialCalloutOffset}`}>
                <span className={`${styles.glassBadgeIcon} ${styles.glassBadgeIconEmerald}`}>
                  <TrustEscrowVaultIcon size={26} />
                </span>
                <div>
                  <p className={styles.glassBadgeTitle}>Escrow &amp; 72h Dispute Protection</p>
                  <p className={styles.glassBadgeSub}>Payments held safely through 48h review &amp; 72h dispute window</p>
                </div>
              </div>
            </div>

            <div className={styles.heroBeat2RightRail}>
              <div ref={badge3Ref} className={`${styles.glassBadge} ${styles.spatialCalloutRight}`}>
                <span className={`${styles.glassBadgeIcon} ${styles.glassBadgeIconAmber}`}>
                  <TrustPriceLockIcon size={26} />
                </span>
                <div>
                  <p className={styles.glassBadgeTitle}>Price-Locked Upfront LKR Rates</p>
                  <p className={styles.glassBadgeSub}>Fixed, hourly &amp; quote rates snapshot-locked across 25 districts</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* BEAT 3 (68% – 100%): Smooth Bottom White Handoff Blend */}
        <div
          ref={handoffOverlayRef}
          className={styles.heroHandoffBlend}
          aria-hidden="true"
        />

        {/* Right-Edge Slim Scroll Progress Indicator */}
        <div className={styles.scrollProgressRail} aria-hidden="true">
          <div ref={progressFillRef} className={styles.scrollProgressFill} />
        </div>

        {/* Scroll Cue (Fades cleanly once scrolling starts) */}
        <div ref={scrollHintRef} className={styles.scrollCue} aria-hidden="true">
          <span className={styles.scrollCueMouse}>
            <span className={styles.scrollCueWheel} />
          </span>
          <span>Scroll to explore</span>
        </div>
      </div>
    </section>
  )
}
