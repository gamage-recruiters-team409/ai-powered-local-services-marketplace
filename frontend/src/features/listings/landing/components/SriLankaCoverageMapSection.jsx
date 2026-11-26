import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import {
  DEFAULT_TRADE_FILTERS,
  SRI_LANKA_FALLBACK_DISTRICTS,
  SRI_LANKA_ISLAND_PIN_DIRECTIONS,
} from '../data/landingContent'
import { useInViewOnce } from '../hooks/useSpringScrollProgress'
import {
  CheckCircleIcon,
  EditorialBurstAccent,
  MapPinIcon,
  SearchIcon,
} from './LandingIcons'
import styles from '../styles/publicLanding.module.css'

const SRI_LANKA_ISLAND_CENTER = [7.72, 80.58]
const SRI_LANKA_ISLAND_ZOOM = 7.5
const SRI_LANKA_CITY_ZOOM = 10.5
const SRI_LANKA_MAX_BOUNDS = [
  [5.35, 78.75],
  [10.25, 82.65],
]
const COLOMBO_COORDS = [6.9271, 79.8612]
const HIGHLIGHT_DISTRICT_NAMES = ['Colombo', 'Gampaha', 'Kandy', 'Galle', 'Jaffna']

function resolveLabelDirection(city, lng) {
  if (SRI_LANKA_ISLAND_PIN_DIRECTIONS[city]) {
    return SRI_LANKA_ISLAND_PIN_DIRECTIONS[city]
  }
  return lng < 80.35 ? 'left' : 'right'
}

function buildDistrictMarkerIcon(pin, isActive) {
  const dir = resolveLabelDirection(pin.city, pin.lng)
  const dirClass = `localserve-pin-dir-${dir}`
  const activeClass = isActive ? 'localserve-pin-active' : ''
  return L.divIcon({
    className: 'localserve-leaflet-pin-host',
    html: `
      <div class="localserve-pin-wrap ${dirClass} ${activeClass}">
        ${isActive ? '<span class="localserve-pin-pulse"></span>' : ''}
        <span class="localserve-pin-dot"></span>
        <span class="localserve-pin-label">${pin.city}</span>
      </div>
    `,
    iconSize: [20, 20],
    iconAnchor: [10, 10],
  })
}

export function SriLankaCoverageMapSection({
  selectedCity = 'Colombo',
  onCityChange,
  liveDistricts = null,
  liveCategories = null,
}) {
  const navigate = useNavigate()
  const sectionRef = useRef(null)
  const mapContainerRef = useRef(null)
  const mapRef = useRef(null)
  const markersLayerRef = useRef(null)
  const circleRef = useRef(null)
  const routeLineRef = useRef(null)
  const finderRef = useRef(null)
  const inView = useInViewOnce(sectionRef, 0.14)

  const [viewMode, setViewMode] = useState('island') // 'island' | 'city'
  const [openMenu, setOpenMenu] = useState(null) // null | 'district' | 'trade'

  // All official Sri Lankan districts (25 from live API, or canonical 5 fallback if offline)
  const districts = useMemo(() => {
    if (Array.isArray(liveDistricts) && liveDistricts.length > 0) {
      return liveDistricts
    }
    return SRI_LANKA_FALLBACK_DISTRICTS
  }, [liveDistricts])

  // Real category options from live GET /api/v1/categories (or clean generic trade filters)
  const tradeOptions = useMemo(() => {
    if (Array.isArray(liveCategories) && liveCategories.length > 0) {
      return ['All Services', ...liveCategories.map((c) => c.name).filter(Boolean)]
    }
    return DEFAULT_TRADE_FILTERS
  }, [liveCategories])

  const [selectedTrade, setSelectedTrade] = useState('All Services')
  const effectiveTrade = tradeOptions.includes(selectedTrade) ? selectedTrade : tradeOptions[0]
  const isIslandwide = selectedCity === 'All Districts'
  const effectiveViewMode = isIslandwide ? 'island' : viewMode

  const activePin = useMemo(() => {
    return (
      districts.find((d) => d.city === selectedCity) ||
      districts.find((d) => d.city === 'Colombo') ||
      districts[0]
    )
  }, [districts, selectedCity])

  const displayDistrictLabel = isIslandwide ? 'All Districts' : activePin.city

  // 5 well-spaced canonical pins on Island View + active district if user picked another of the 25
  const mapPins = useMemo(() => {
    const highlighted = HIGHLIGHT_DISTRICT_NAMES.map((name) =>
      districts.find((d) => d.city === name)
    ).filter(Boolean)
    if (!isIslandwide && activePin && !highlighted.some((p) => p.city === activePin.city)) {
      highlighted.push(activePin)
    }
    return highlighted.length > 0 ? highlighted : districts.slice(0, 5)
  }, [districts, activePin, isIslandwide])

  useEffect(() => {
    if (!openMenu) return undefined
    const handleOutside = (event) => {
      if (finderRef.current && !finderRef.current.contains(event.target)) {
        setOpenMenu(null)
      }
    }
    const handleKey = (event) => {
      if (event.key === 'Escape') {
        setOpenMenu(null)
      }
    }
    document.addEventListener('mousedown', handleOutside)
    document.addEventListener('keydown', handleKey)
    return () => {
      document.removeEventListener('mousedown', handleOutside)
      document.removeEventListener('keydown', handleKey)
    }
  }, [openMenu])

  const handleSelectPin = useCallback(
    (pin, shouldFocus = false) => {
      onCityChange?.(pin.city)
      if (shouldFocus) {
        setViewMode('city')
      }
    },
    [onCityChange]
  )

  const selectPinRef = useRef(handleSelectPin)
  useEffect(() => {
    selectPinRef.current = handleSelectPin
  }, [handleSelectPin])

  // Initialize real interactive Leaflet map of Sri Lanka once on mount
  useEffect(() => {
    const container = mapContainerRef.current
    if (!container || mapRef.current) return undefined

    try {
      const mapInstance = L.map(container, {
        center: SRI_LANKA_ISLAND_CENTER,
        zoom: SRI_LANKA_ISLAND_ZOOM,
        zoomSnap: 0.5,
        minZoom: 7,
        maxZoom: 14,
        maxBounds: L.latLngBounds(SRI_LANKA_MAX_BOUNDS[0], SRI_LANKA_MAX_BOUNDS[1]),
        maxBoundsViscosity: 0.85,
        scrollWheelZoom: false,
        zoomControl: true,
      })

      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap contributors',
        maxZoom: 18,
      }).addTo(mapInstance)

      const circle = L.circle([activePin.lat, activePin.lng], {
        radius: 18000,
        color: '#0f766e',
        weight: 2,
        opacity: 0.85,
        fillColor: '#10b981',
        fillOpacity: 0.16,
      }).addTo(mapInstance)

      const routeLine = L.polyline([], {
        color: '#0f766e',
        weight: 2.2,
        opacity: 0.55,
        dashArray: '6 6',
      }).addTo(mapInstance)

      const markersLayer = L.layerGroup().addTo(mapInstance)

      mapRef.current = mapInstance
      markersLayerRef.current = markersLayer
      circleRef.current = circle
      routeLineRef.current = routeLine
    } catch {
      // Graceful fallback in non-layout headless DOM environments (e.g., jsdom tests)
    }

    return () => {
      if (mapRef.current) {
        try {
          mapRef.current.remove()
        } catch {
          // Ignore teardown errors
        }
        mapRef.current = null
        markersLayerRef.current = null
        circleRef.current = null
        routeLineRef.current = null
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Sync Leaflet markers, active coverage circle, Colombo corridor line, and smooth camera
  useEffect(() => {
    const map = mapRef.current
    const markersLayer = markersLayerRef.current
    const circle = circleRef.current
    const routeLine = routeLineRef.current
    if (!map || !markersLayer) return

    try {
      markersLayer.clearLayers()

      mapPins.forEach((pin) => {
        const isAct = !isIslandwide && pin.city === activePin.city
        const marker = L.marker([pin.lat, pin.lng], {
          icon: buildDistrictMarkerIcon(pin, isAct),
          zIndexOffset: isAct ? 600 : 100,
        })
        marker.on('click', () => {
          selectPinRef.current?.(pin, true)
        })
        marker.addTo(markersLayer)
      })

      if (circle) {
        if (isIslandwide) {
          circle.setStyle({ opacity: 0, fillOpacity: 0 })
        } else {
          circle.setLatLng([activePin.lat, activePin.lng])
          circle.setStyle({ opacity: 0.85, fillOpacity: 0.16 })
        }
      }

      if (routeLine) {
        if (isIslandwide || activePin.city === 'Colombo') {
          routeLine.setLatLngs([])
        } else {
          routeLine.setLatLngs([COLOMBO_COORDS, [activePin.lat, activePin.lng]])
        }
      }

      const reducedMotion =
        typeof window !== 'undefined' &&
        window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches

      const targetCenter =
        effectiveViewMode === 'city' ? [activePin.lat, activePin.lng] : SRI_LANKA_ISLAND_CENTER
      const targetZoom = effectiveViewMode === 'city' ? SRI_LANKA_CITY_ZOOM : SRI_LANKA_ISLAND_ZOOM

      if (reducedMotion || typeof map.flyTo !== 'function') {
        map.setView(targetCenter, targetZoom)
      } else {
        map.flyTo(targetCenter, targetZoom, { duration: 0.75, easeLinearity: 0.25 })
      }
    } catch {
      // Ignore viewport animation errors in non-layout environments
    }
  }, [activePin, effectiveViewMode, isIslandwide, mapPins])

  const handlePickDistrict = (pin) => {
    handleSelectPin(pin, true)
    setOpenMenu(null)
  }

  const handlePickAllDistricts = () => {
    onCityChange?.('All Districts')
    setViewMode('island')
    setOpenMenu(null)
  }

  const handlePickTrade = (trade) => {
    setSelectedTrade(trade)
    setOpenMenu(null)
  }

  const handleSubmitQuickSearch = (event) => {
    event.preventDefault()
    setOpenMenu(null)
    const params = new URLSearchParams()
    if (effectiveTrade && effectiveTrade !== 'All Services') {
      params.set('q', effectiveTrade)
    }
    if (!isIslandwide && activePin?.city) {
      params.set('location', activePin.city)
    }
    const qs = params.toString()
    navigate(qs ? `/search?${qs}` : '/search')
  }

  return (
    <section
      id="islandwide-coverage"
      ref={sectionRef}
      className={`${styles.coverageSection} ${inView ? styles.sectionRevealed : ''}`}
      aria-labelledby="islandwide-coverage-heading"
    >
      <div className={styles.sectionContainer}>
        <div className={styles.coverageGrid}>
          {/* Left: Real Interactive Leaflet Map of Sri Lanka */}
          <div className={styles.coverageMapCard}>
            <div className={styles.coverageLeafletStage}>
              <div
                className={styles.coverageMapControls}
                role="group"
                aria-label="Map zoom view mode"
              >
                <button
                  type="button"
                  aria-pressed={effectiveViewMode === 'island'}
                  onClick={() => setViewMode('island')}
                  className={`${styles.coverageModeBtn} ${
                    effectiveViewMode === 'island' ? styles.coverageModeBtnActive : ''
                  }`}
                >
                  Island View
                </button>
                {!isIslandwide && (
                  <button
                    type="button"
                    aria-pressed={effectiveViewMode === 'city'}
                    onClick={() => setViewMode('city')}
                    className={`${styles.coverageModeBtn} ${
                      effectiveViewMode === 'city' ? styles.coverageModeBtnActive : ''
                    }`}
                  >
                    Focus {activePin.city}
                  </button>
                )}
              </div>

              <div
                ref={mapContainerRef}
                className={styles.coverageLeafletCanvas}
                data-testid="landing-leaflet-map"
                data-city={displayDistrictLabel}
                data-view-mode={effectiveViewMode}
                aria-label={
                  isIslandwide
                    ? 'Interactive Sri Lanka service coverage map showing all districts'
                    : `Interactive Sri Lanka service coverage map focused on ${activePin.city}`
                }
              />
            </div>

            {/* Accessible Keyboard Pin Bar */}
            <div
              className={styles.coveragePinBar}
              role="group"
              aria-label="Interactive Sri Lanka district coverage map"
            >
              {mapPins.map((pin) => {
                const isActive = !isIslandwide && pin.city === activePin.city
                return (
                  <button
                    key={pin.id}
                    type="button"
                    aria-pressed={isActive}
                    aria-label={`${pin.city} (${pin.province})`}
                    onClick={() => handleSelectPin(pin, true)}
                    className={`${styles.coveragePinBarBtn} ${
                      isActive ? styles.coveragePinBarBtnActive : ''
                    }`}
                  >
                    <span className={styles.coveragePinBarDot} aria-hidden="true" />
                    <span>{pin.city}</span>
                  </button>
                )
              })}
            </div>
          </div>

          {/* Right: Editorial Copy + Borderless Popover Quick Finder */}
          <div className={styles.coverageCopyCol}>
            <span className={styles.sectionPill}>
              Islandwide Coverage · {districts.length > 5 ? districts.length : 25} Districts · 9
              Provinces
            </span>
            <h2 id="islandwide-coverage-heading" className={styles.coverageHeading}>
              <span>Colombo, Kandy, Galle —</span>{' '}
              <span className={styles.coverageHeadingTeal}>Verified Pros Everywhere</span>{' '}
              <span>You Need Them.</span>
              <EditorialBurstAccent size={24} className={styles.editorialBurstInline} />
            </h2>
            <p className={styles.coverageSubcopy}>
              Choose any of Sri Lanka&apos;s 25 official districts on the interactive map or finder
              below to sync your location across the landing page and browse verified providers.
            </p>

            <form
              ref={finderRef}
              className={styles.coverageSearchPill}
              onSubmit={handleSubmitQuickSearch}
              aria-label="Quick district and service finder"
            >
              {/* Custom Borderless District Popover Trigger */}
              <div className={styles.coverageDropdownWrap}>
                <button
                  type="button"
                  aria-haspopup="listbox"
                  aria-expanded={openMenu === 'district'}
                  aria-label={`Select district: ${displayDistrictLabel}`}
                  onClick={() =>
                    setOpenMenu((prev) => (prev === 'district' ? null : 'district'))
                  }
                  className={`${styles.coverageDropdownTrigger} ${
                    openMenu === 'district' ? styles.coverageDropdownTriggerOpen : ''
                  }`}
                >
                  <span className={styles.coverageFieldLabel}>
                    <MapPinIcon size={12} />
                    <span>District</span>
                  </span>
                  <span className={styles.coverageTriggerValueRow}>
                    <span className={styles.coverageTriggerValue}>{displayDistrictLabel}</span>
                    <svg
                      width="14"
                      height="14"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2.4"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      aria-hidden="true"
                      className={`${styles.coverageChevron} ${
                        openMenu === 'district' ? styles.coverageChevronOpen : ''
                      }`}
                    >
                      <path d="m6 9 6 6 6-6" />
                    </svg>
                  </span>
                </button>

                {openMenu === 'district' && (
                  <div
                    className={styles.coveragePopoverMenu}
                    role="listbox"
                    aria-label="Available Sri Lankan districts"
                  >
                    <button
                      type="button"
                      role="option"
                      aria-selected={isIslandwide}
                      onClick={handlePickAllDistricts}
                      className={`${styles.coveragePopoverOption} ${
                        isIslandwide ? styles.coveragePopoverOptionActive : ''
                      }`}
                    >
                      <span className={styles.coveragePopoverMain}>
                        <strong>All Districts</strong>
                        <small>Islandwide ({districts.length > 5 ? districts.length : 25} Districts)</small>
                      </span>
                      {isIslandwide && (
                        <CheckCircleIcon size={15} className={styles.coveragePopoverCheck} />
                      )}
                    </button>
                    {districts.map((pin) => {
                      const selected = !isIslandwide && pin.city === activePin.city
                      return (
                        <button
                          key={pin.id}
                          type="button"
                          role="option"
                          aria-selected={selected}
                          onClick={() => handlePickDistrict(pin)}
                          className={`${styles.coveragePopoverOption} ${
                            selected ? styles.coveragePopoverOptionActive : ''
                          }`}
                        >
                          <span className={styles.coveragePopoverMain}>
                            <strong>{pin.city}</strong>
                            <small>{pin.province}</small>
                          </span>
                          {selected && (
                            <CheckCircleIcon size={15} className={styles.coveragePopoverCheck} />
                          )}
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>

              <span className={styles.coveragePillDivider} aria-hidden="true" />

              {/* Custom Borderless Trade / Service Popover Trigger */}
              <div className={styles.coverageDropdownWrap}>
                <button
                  type="button"
                  aria-haspopup="listbox"
                  aria-expanded={openMenu === 'trade'}
                  aria-label={`Select trade or service: ${effectiveTrade}`}
                  onClick={() =>
                    setOpenMenu((prev) => (prev === 'trade' ? null : 'trade'))
                  }
                  className={`${styles.coverageDropdownTrigger} ${
                    openMenu === 'trade' ? styles.coverageDropdownTriggerOpen : ''
                  }`}
                >
                  <span className={styles.coverageFieldLabel}>Service Category</span>
                  <span className={styles.coverageTriggerValueRow}>
                    <span className={styles.coverageTriggerValue}>{effectiveTrade}</span>
                    <svg
                      width="14"
                      height="14"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2.4"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      aria-hidden="true"
                      className={`${styles.coverageChevron} ${
                        openMenu === 'trade' ? styles.coverageChevronOpen : ''
                      }`}
                    >
                      <path d="m6 9 6 6 6-6" />
                    </svg>
                  </span>
                </button>

                {openMenu === 'trade' && (
                  <div
                    className={styles.coveragePopoverMenu}
                    role="listbox"
                    aria-label="Available service categories"
                  >
                    {tradeOptions.map((trade) => {
                      const selected = trade === effectiveTrade
                      return (
                        <button
                          key={trade}
                          type="button"
                          role="option"
                          aria-selected={selected}
                          onClick={() => handlePickTrade(trade)}
                          className={`${styles.coveragePopoverOption} ${
                            selected ? styles.coveragePopoverOptionActive : ''
                          }`}
                        >
                          <span className={styles.coveragePopoverMain}>
                            <strong>{trade}</strong>
                          </span>
                          {selected && (
                            <CheckCircleIcon size={15} className={styles.coveragePopoverCheck} />
                          )}
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>

              <button
                type="submit"
                className={styles.coverageSearchSubmit}
                aria-label={
                  isIslandwide
                    ? `Search ${effectiveTrade} across Sri Lanka`
                    : `Search ${effectiveTrade} in ${activePin.city}`
                }
              >
                <SearchIcon size={18} />
              </button>
            </form>

            <p className={styles.coverageHintLine}>
              {isIslandwide ? (
                <>
                  Tip: Viewing <strong>All Districts</strong> islandwide — click any marker or
                  choose a district above to focus locally.
                </>
              ) : (
                <>
                  Tip: Click any marker on the map or toggle{' '}
                  <strong>Island View / Focus {activePin.city}</strong> to inspect live district
                  coverage.
                </>
              )}
            </p>
          </div>
        </div>
      </div>
    </section>
  )
}
