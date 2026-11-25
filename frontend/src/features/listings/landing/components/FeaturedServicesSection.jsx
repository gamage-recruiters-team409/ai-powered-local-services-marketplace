import { useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  iconCatAppliance,
  iconCatCleaning,
  iconCatElectrical,
  iconCatHvac,
  iconCatPainting,
  iconCatPlumbing,
  iconCatSolar,
  iconCatWoodwork,
} from '../../assets'
import { useInViewOnce } from '../hooks/useSpringScrollProgress'
import {
  ArrowUpRightIcon,
  CheckCircleIcon,
  EditorialBurstAccent,
  MapPinIcon,
  StarIcon,
} from './LandingIcons'
import styles from '../styles/publicLanding.module.css'

const ICON_ASSET_BY_KEY = {
  water: { img: iconCatPlumbing, bg: '#dbeafe' },
  electrician: { img: iconCatElectrical, bg: '#fef3c7' },
  sun: { img: iconCatSolar, bg: '#fef9c3' },
  temp: { img: iconCatHvac, bg: '#ccfbf1' },
  sparkles: { img: iconCatCleaning, bg: '#d1fae5' },
  roller: { img: iconCatPainting, bg: '#ffedd5' },
  pencil: { img: iconCatWoodwork, bg: '#ffedd5' },
}

function resolveVisualForItem(item) {
  const haystack = `${item.categoryName || ''} ${item.title || ''}`.toLowerCase()
  if (/(plumb|pipe|leak|water(?!proof)|drain)/.test(haystack)) return ICON_ASSET_BY_KEY.water
  if (/(solar|inverter)/.test(haystack)) return ICON_ASSET_BY_KEY.sun
  if (/(elec|wiring|breaker)/.test(haystack)) return ICON_ASSET_BY_KEY.electrician
  if (/(hvac|air[\s-]*cond|\bac\b|cool)/.test(haystack)) return ICON_ASSET_BY_KEY.temp
  if (/(clean|sanit|pest|steam)/.test(haystack)) return ICON_ASSET_BY_KEY.sparkles
  if (/(carp|wood|paint|mason|waterproof|tile)/.test(haystack)) return ICON_ASSET_BY_KEY.roller
  return { img: iconCatAppliance, bg: '#e6fbf3' }
}

function formatLkrAmount(amount) {
  if (typeof amount !== 'number' || !Number.isFinite(amount) || amount <= 0) {
    return null
  }
  return `Rs. ${Math.round(amount).toLocaleString('en-LK')}`
}

function formatPricingUnit(model) {
  if (model === 'HOURLY') return '/ hr'
  if (model === 'QUOTE') return '/ quote'
  return '/ fixed'
}

function FeaturedCardMedia({ item, visual }) {
  const [imgFailed, setImgFailed] = useState(false)
  const showPhoto = Boolean(item.imageUrl) && !imgFailed

  if (showPhoto) {
    return (
      <img
        src={item.imageUrl}
        alt={item.title}
        className={styles.featuredPhoto}
        loading="lazy"
        onError={() => setImgFailed(true)}
      />
    )
  }

  return (
    <div
      className={styles.featuredStudioFallback}
      style={{ '--feat-panel-bg': visual.bg }}
    >
      <span className={styles.featuredStudioOrb}>
        <img
          src={visual.img}
          alt=""
          width="46"
          height="46"
          className={styles.featuredStudioIcon}
        />
      </span>
    </div>
  )
}

export function FeaturedServicesSection({
  selectedCity = 'Colombo',
  liveServices = [],
  loading = false,
  error = false,
  isIslandwideFallback = false,
  onRetry,
}) {
  const navigate = useNavigate()
  const sectionRef = useRef(null)
  const inView = useInViewOnce(sectionRef, 0.1)
  const [activeCategory, setActiveCategory] = useState('ALL')

  const items = useMemo(
    () => (Array.isArray(liveServices) ? liveServices : []),
    [liveServices]
  )

  // Build category tabs dynamically from real database results only (zero fake tabs)
  const categoryTabs = useMemo(() => {
    const names = Array.from(
      new Set(items.map((item) => item.categoryName).filter(Boolean))
    )
    return [{ id: 'ALL', label: 'All Services' }, ...names.map((name) => ({ id: name, label: name }))]
  }, [items])

  const effectiveCategory = categoryTabs.some((t) => t.id === activeCategory)
    ? activeCategory
    : 'ALL'

  const visibleItems = useMemo(() => {
    if (effectiveCategory === 'ALL') return items
    return items.filter((item) => item.categoryName === effectiveCategory)
  }, [effectiveCategory, items])

  const handleOpenService = (item) => {
    if (item.profileId) {
      navigate(`/providers/${item.profileId}`)
      return
    }
    const params = new URLSearchParams()
    params.set('q', item.title)
    if (selectedCity && selectedCity !== 'All Districts') {
      params.set('location', selectedCity)
    } else if (item.location) {
      params.set('location', item.location)
    }
    navigate(`/search?${params.toString()}`)
  }

  return (
    <section
      id="featured-services"
      ref={sectionRef}
      className={`${styles.featuredSection} ${inView ? styles.sectionRevealed : ''}`}
      aria-labelledby="featured-services-heading"
    >
      <div className={styles.sectionContainer}>
        <div className={styles.featuredHeaderRow}>
          <div>
            <div className={styles.featuredEyebrowRow}>
              <span className={styles.sectionPill}>
                {selectedCity === 'All Districts' || isIslandwideFallback
                  ? 'Live Marketplace Services · Sri Lanka'
                  : `Live Services in ${selectedCity} & Suburbs`}
              </span>
              {items.length > 0 && (
                <span className={styles.featuredLiveBadge}>
                  <span className={styles.featuredLiveDot} aria-hidden="true" />
                  <span>
                    {isIslandwideFallback
                      ? 'Islandwide Live Listings'
                      : 'Live Marketplace Listings'}
                  </span>
                </span>
              )}
            </div>
            <h2 id="featured-services-heading" className={styles.sectionTitle}>
              Featured Verified Services Ready to Book
              <EditorialBurstAccent size={24} className={styles.editorialBurstInline} />
            </h2>
            <p className={styles.sectionSubtitle}>
              Real active service listings fetched directly from verified LocalServe providers with
              price-locked LKR rates.
            </p>
          </div>

          <button
            type="button"
            className={styles.featuredBrowseAllBtn}
            onClick={() => {
              const qs =
                selectedCity && selectedCity !== 'All Districts'
                  ? `?location=${encodeURIComponent(selectedCity)}`
                  : ''
              navigate(`/search${qs}`)
            }}
          >
            <span>Browse All Services</span>
            <ArrowUpRightIcon size={15} />
          </button>
        </div>

        {/* Dynamic Category Tabs (shown only when 2+ real categories exist) */}
        {categoryTabs.length > 2 && (
          <div
            className={styles.featuredFilterRow}
            role="tablist"
            aria-label="Filter featured services by trade"
          >
            {categoryTabs.map((tab) => {
              const selected = effectiveCategory === tab.id
              return (
                <button
                  key={tab.id}
                  type="button"
                  role="tab"
                  aria-selected={selected}
                  className={`${styles.featuredFilterPill} ${
                    selected ? styles.featuredFilterPillActive : ''
                  }`}
                  onClick={() => setActiveCategory(tab.id)}
                >
                  {tab.label}
                </button>
              )
            })}
          </div>
        )}

        {loading ? (
          <div className={styles.featuredGrid} aria-busy="true" aria-label="Loading featured services">
            {[0, 1, 2].map((slot) => (
              <div key={slot} className={styles.liveSkeletonCard}>
                <div className={styles.liveSkeletonBanner} />
                <div className={styles.liveSkeletonLineLg} />
                <div className={styles.liveSkeletonLineSm} />
              </div>
            ))}
          </div>
        ) : error ? (
          <div className={styles.directoryEmpty} role="status">
            <p>
              Unable to load live marketplace listings right now. Check that the backend API is
              running and retry.
            </p>
            <div className={styles.emptyActionRow}>
              {onRetry && (
                <button type="button" className={styles.directoryClearBtn} onClick={onRetry}>
                  Retry Loading Listings
                </button>
              )}
              <button
                type="button"
                className={styles.directoryClearBtn}
                onClick={() => navigate('/search')}
              >
                Open Service Search
              </button>
            </div>
          </div>
        ) : visibleItems.length === 0 ? (
          <div className={styles.directoryEmpty} role="status">
            <p>
              No active verified listings are published{' '}
              {selectedCity !== 'All Districts' ? `in ${selectedCity}` : 'across Sri Lanka'} yet.
              Verified providers can register and publish fixed, hourly, or quote LKR services.
            </p>
            <div className={styles.emptyActionRow}>
              <button
                type="button"
                className={styles.directoryClearBtn}
                onClick={() => navigate('/search')}
              >
                Search All Services
              </button>
              <Link to="/register?role=provider" className={styles.directoryPrimaryLink}>
                Become a Verified Provider
              </Link>
            </div>
          </div>
        ) : (
          <div key={effectiveCategory} className={styles.featuredGrid}>
            {visibleItems.map((item, idx) => {
              const visual = resolveVisualForItem(item)
              const formattedPrice = formatLkrAmount(item.priceAmount)
              const hasRating =
                typeof item.rating === 'number' &&
                item.rating > 0 &&
                (item.reviewCount == null || item.reviewCount > 0)
              const unitLabel = formatPricingUnit(item.pricingModel)

              return (
                <article
                  key={item.id}
                  className={styles.featuredCard}
                  style={{ '--reveal-delay': `${Math.min(idx, 5) * 65}ms` }}
                >
                  <div className={styles.featuredMediaWrap}>
                    <FeaturedCardMedia item={item} visual={visual} />
                    <span className={styles.featuredVerifyPill}>
                      <CheckCircleIcon size={12} />
                      <span>{item.badge || (item.verified ? 'Admin-Verified Pro' : 'Active')}</span>
                    </span>
                    {hasRating ? (
                      <span
                        className={styles.featuredRatingPill}
                        aria-label={`Rated ${item.rating.toFixed(1)} out of 5`}
                      >
                        <StarIcon size={12} className={styles.warmAmberStar} />
                        <span>
                          {item.rating.toFixed(1)}
                          {item.reviewCount > 0 ? ` (${item.reviewCount})` : ''}
                        </span>
                      </span>
                    ) : (
                      <span className={styles.featuredNewPill}>New Listing</span>
                    )}
                  </div>

                  <div className={styles.featuredCardBody}>
                    <span className={styles.featuredCategoryLabel}>{item.categoryName}</span>
                    <h3 className={styles.featuredCardTitle}>{item.title}</h3>

                    <div className={styles.featuredMetaRow}>
                      <span className={styles.featuredProviderName}>
                        {item.providerName || 'Verified Provider'}
                      </span>
                      <span className={styles.featuredLocationChip}>
                        <MapPinIcon size={12} />
                        <span>{item.location || selectedCity}</span>
                      </span>
                    </div>

                    <div className={styles.featuredCardFooter}>
                      <div className={styles.featuredPriceBlock}>
                        {formattedPrice ? (
                          <>
                            <span className={styles.featuredPricePrefix}>From</span>
                            <strong className={styles.featuredPriceAmount}>
                              {formattedPrice}
                            </strong>
                            <span className={styles.featuredPriceUnit}>{unitLabel}</span>
                          </>
                        ) : (
                          <span className={styles.featuredPricePrefix}>Custom quote available</span>
                        )}
                      </div>

                      <button
                        type="button"
                        className={styles.featuredCtaBtn}
                        onClick={() => handleOpenService(item)}
                      >
                        <span>View Service</span>
                        <ArrowUpRightIcon size={14} className={styles.featuredCtaArrow} />
                      </button>
                    </div>
                  </div>
                </article>
              )
            })}
          </div>
        )}
      </div>
    </section>
  )
}
