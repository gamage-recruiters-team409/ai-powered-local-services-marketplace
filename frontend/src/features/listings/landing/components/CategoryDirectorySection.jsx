import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  iconCatElectrical,
  iconCatSolar,
  iconCatPlumbing,
  iconCatHvac,
  iconCatWoodwork,
  iconCatCivil,
  iconCatAppliance,
  iconCatPainting,
  iconCatCleaning,
  iconCatCctv,
  iconCatGardening,
  iconCatSafety,
  iconCatBeauty,
  iconCatBarbering,
  iconCatWellness,
  iconCatItTech,
  iconCatMoving,
  iconCatVideo,
} from '../../assets'
import {
  ArrowRightIcon,
  ArrowUpRightIcon,
  CheckCircleIcon,
  EditorialBurstAccent,
  SearchIcon,
} from './LandingIcons'
import styles from '../styles/publicLanding.module.css'

const CATEGORY_ICON_MAP = {
  electrician: { img: iconCatElectrical, panelBg: '#d7f7eb', panelBgEnd: '#e6fbf3' },
  sun: { img: iconCatSolar, panelBg: '#fef3c7', panelBgEnd: '#fef9e7' },
  water: { img: iconCatPlumbing, panelBg: '#dbeafe', panelBgEnd: '#eff6ff' },
  temp: { img: iconCatHvac, panelBg: '#e0f2fe', panelBgEnd: '#f0f9ff' },
  pencil: { img: iconCatWoodwork, panelBg: '#ffedd5', panelBgEnd: '#fff7ed' },
  grid: { img: iconCatCivil, panelBg: '#e2e8f0', panelBgEnd: '#f1f5f9' },
  wrench: { img: iconCatAppliance, panelBg: '#ccfbf1', panelBgEnd: '#f0fdfa' },
  roller: { img: iconCatPainting, panelBg: '#fce7f3', panelBgEnd: '#fdf2f8' },
  sparkles: { img: iconCatCleaning, panelBg: '#d1fae5', panelBgEnd: '#ecfdf5' },
  camera: { img: iconCatCctv, panelBg: '#ffedd5', panelBgEnd: '#fff5eb' },
  plant: { img: iconCatGardening, panelBg: '#dcfce7', panelBgEnd: '#f0fdf4' },
  shield: { img: iconCatSafety, panelBg: '#ccfbf1', panelBgEnd: '#e6fffa' },
  salon: { img: iconCatBeauty, panelBg: '#ffe4e6', panelBgEnd: '#fff1f2' },
  scissors: { img: iconCatBarbering, panelBg: '#ede9fe', panelBgEnd: '#f5f3ff' },
  lotus: { img: iconCatWellness, panelBg: '#d1fae5', panelBgEnd: '#f0fdfa' },
  laptop: { img: iconCatItTech, panelBg: '#e0e7ff', panelBgEnd: '#eef2ff' },
  package: { img: iconCatMoving, panelBg: '#ffedd5', panelBgEnd: '#fff7ed' },
  video: { img: iconCatVideo, panelBg: '#f3e8ff', panelBgEnd: '#faf5ff' },
}

const FALLBACK_PASTELS = [
  { panelBg: '#d7f7eb', panelBgEnd: '#e6fbf3' },
  { panelBg: '#ffedd5', panelBgEnd: '#fff7ed' },
  { panelBg: '#e0f2fe', panelBgEnd: '#f0f9ff' },
  { panelBg: '#ede9fe', panelBgEnd: '#f5f3ff' },
  { panelBg: '#fef3c7', panelBgEnd: '#fef9e7' },
]

const ICON_ALIASES = {
  electrician: 'electrician',
  electrical: 'electrician',
  electricity: 'electrician',
  power: 'electrician',
  sun: 'sun',
  solar: 'sun',
  renewable: 'sun',
  water: 'water',
  plumbing: 'water',
  temp: 'temp',
  hvac: 'temp',
  ac: 'temp',
  climate: 'temp',
  pencil: 'pencil',
  carpentry: 'pencil',
  woodwork: 'pencil',
  grid: 'grid',
  masonry: 'grid',
  civil: 'grid',
  wrench: 'wrench',
  appliance: 'wrench',
  roller: 'roller',
  paint: 'roller',
  painting: 'roller',
  sparkles: 'sparkles',
  clean: 'sparkles',
  cleaning: 'sparkles',
  camera: 'camera',
  cctv: 'camera',
  security: 'camera',
  plant: 'plant',
  garden: 'plant',
  gardening: 'plant',
  shield: 'shield',
  safety: 'shield',
  pest: 'shield',
  salon: 'salon',
  beauty: 'salon',
  scissors: 'scissors',
  barber: 'scissors',
  lotus: 'lotus',
  wellness: 'lotus',
  spa: 'lotus',
  laptop: 'laptop',
  it: 'laptop',
  tech: 'laptop',
  package: 'package',
  moving: 'package',
  video: 'video',
  photography: 'video',
}

function resolveCategoryVisual(category, index) {
  const rawIcon = (category.icon || '').toString().trim().toLowerCase()
  const aliasKey = ICON_ALIASES[rawIcon]
  if (aliasKey && CATEGORY_ICON_MAP[aliasKey]) {
    return CATEGORY_ICON_MAP[aliasKey]
  }

  const text = `${category.slug || ''} ${category.name || ''}`.toLowerCase()
  if (/(solar|renewable|\bsun\b)/.test(text)) return CATEGORY_ICON_MAP.sun
  if (/(elec|wiring|switchboard|breaker|\bpower\b)/.test(text)) return CATEGORY_ICON_MAP.electrician
  if (/(plumb|pipe|drain|tank|bath|water(?!proof))/.test(text)) return CATEGORY_ICON_MAP.water
  if (/(hvac|air[-_\s]?cond|\bac\b|cool|climate)/.test(text)) return CATEGORY_ICON_MAP.temp
  if (/(carp|wood|pantry|furniture|door)/.test(text)) return CATEGORY_ICON_MAP.pencil
  if (/(mason|civil|tile|roof|waterproof)/.test(text)) return CATEGORY_ICON_MAP.grid
  if (/(paint|finish|roller)/.test(text)) return CATEGORY_ICON_MAP.roller
  if (/(clean|sanit|pest|steam|sparkle)/.test(text)) return CATEGORY_ICON_MAP.sparkles
  if (/(cctv|camera|surveillance|smart\s?home)/.test(text)) return CATEGORY_ICON_MAP.camera
  if (/(garden|lawn|landscap|plant)/.test(text)) return CATEGORY_ICON_MAP.plant
  if (/(salon|beauty|makeup)/.test(text)) return CATEGORY_ICON_MAP.salon
  if (/(barber|hair|groom)/.test(text)) return CATEGORY_ICON_MAP.scissors
  if (/(spa|wellness|massage)/.test(text)) return CATEGORY_ICON_MAP.lotus
  if (/(laptop|computer|\bit\b|tech|network)/.test(text)) return CATEGORY_ICON_MAP.laptop
  if (/(moving|delivery|assembly|relocat)/.test(text)) return CATEGORY_ICON_MAP.package
  if (/(video|photo|film|shoot)/.test(text)) return CATEGORY_ICON_MAP.video

  const fallbackPalette = FALLBACK_PASTELS[index % FALLBACK_PASTELS.length]
  return { img: iconCatAppliance, ...fallbackPalette }
}

export function CategoryDirectorySection({
  selectedCity = 'Colombo',
  liveCategories = [],
  loading = false,
  error = false,
  onRetry,
}) {
  const navigate = useNavigate()
  const [filterText, setFilterText] = useState('')

  const categories = useMemo(() => {
    const source = Array.isArray(liveCategories) ? liveCategories : []
    return source.map((cat, idx) => {
      const visual = resolveCategoryVisual(cat, idx)
      return {
        id: cat.id || cat.slug,
        name: cat.name,
        slug: cat.slug,
        description:
          cat.description ||
          'Admin-managed service category with verified LocalServe providers.',
        iconImg: visual.img,
        panelBg: visual.panelBg,
        panelBgEnd: visual.panelBgEnd,
        subcategories: Array.isArray(cat.subcategories) ? cat.subcategories : [],
      }
    })
  }, [liveCategories])

  const totalSubcategories = useMemo(
    () => categories.reduce((sum, cat) => sum + (cat.subcategories?.length || 0), 0),
    [categories]
  )

  const filteredCategories = useMemo(() => {
    const term = filterText.trim().toLowerCase()
    if (!term) return categories

    return categories
      .map((cat) => {
        const catMatches =
          cat.name.toLowerCase().includes(term) ||
          (cat.description || '').toLowerCase().includes(term)
        const matchedSubs = (cat.subcategories || []).filter((sub) =>
          sub.name.toLowerCase().includes(term)
        )
        if (catMatches) {
          return cat
        }
        if (matchedSubs.length > 0) {
          return { ...cat, subcategories: matchedSubs }
        }
        return null
      })
      .filter(Boolean)
  }, [categories, filterText])

  const handleOpenSearch = (queryText, slug) => {
    const params = new URLSearchParams()
    if (queryText) params.set('q', queryText)
    if (slug) params.set('category', slug)
    if (selectedCity && selectedCity !== 'All Districts') {
      params.set('location', selectedCity)
    }
    navigate(`/search?${params.toString()}`)
  }

  return (
    <section
      id="service-directory"
      className={styles.directorySection}
      aria-labelledby="service-directory-heading"
    >
      <div className={styles.sectionContainer}>
        <div className={styles.directoryHeaderRow}>
          <div>
            <span className={styles.sectionPill}>
              {selectedCity === 'All Districts'
                ? 'Available Islandwide (25 Districts)'
                : `Available in ${selectedCity} & Suburbs`}
            </span>
            <h2 id="service-directory-heading" className={styles.sectionTitle}>
              Explore Service Directory By Category
              <EditorialBurstAccent size={24} className={styles.editorialBurstInline} />
            </h2>
            <p className={styles.sectionSubtitle}>
              Live categories and subcategories managed directly from the LocalServe catalogue
              database.
            </p>
          </div>

          {categories.length > 0 && (
            <div className={styles.directoryControls}>
              <div className={styles.directoryCountBadge}>
                <strong>
                  {categories.length}{' '}
                  {categories.length === 1 ? 'Primary Category' : 'Primary Categories'}
                </strong>
                <span aria-hidden="true">·</span>
                <span>
                  {totalSubcategories}{' '}
                  {totalSubcategories === 1 ? 'Subcategory' : 'Subcategories'}
                </span>
              </div>

              <div className={styles.directorySearchWrap}>
                <SearchIcon size={16} className={styles.directorySearchIcon} />
                <input
                  type="search"
                  className={styles.directorySearchInput}
                  placeholder="Filter categories or services..."
                  aria-label="Filter categories or subcategories"
                  value={filterText}
                  onChange={(e) => setFilterText(e.target.value)}
                />
              </div>
            </div>
          )}
        </div>

        {loading ? (
          <div className={styles.directoryGrid} aria-busy="true" aria-label="Loading service categories">
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
              Unable to reach the live category catalogue right now. Start the backend server or
              try again.
            </p>
            <div className={styles.emptyActionRow}>
              {onRetry && (
                <button type="button" className={styles.directoryClearBtn} onClick={onRetry}>
                  Retry Loading Categories
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
        ) : categories.length === 0 ? (
          <div className={styles.directoryEmpty} role="status">
            <p>No active service categories are published in the catalogue yet.</p>
            <button
              type="button"
              className={styles.directoryClearBtn}
              onClick={() => navigate('/search')}
            >
              Browse Marketplace Search
            </button>
          </div>
        ) : filteredCategories.length === 0 ? (
          <div className={styles.directoryEmpty}>
            <p>
              No categories or subcategories matched <strong>&ldquo;{filterText}&rdquo;</strong>.
            </p>
            <button
              type="button"
              className={styles.directoryClearBtn}
              onClick={() => setFilterText('')}
            >
              Clear Filter
            </button>
          </div>
        ) : (
          <div className={styles.directoryGrid}>
            {filteredCategories.map((category) => {
              const subCount = category.subcategories.length
              const shortLabel = category.name.split(/[&,]/)[0].trim()

              return (
                <article key={category.id} className={styles.categoryCard}>
                  <div
                    className={styles.categoryPastelPanel}
                    style={{
                      '--panel-bg': category.panelBg,
                      '--panel-bg-end': category.panelBgEnd,
                    }}
                  >
                    <div className={styles.categoryPanelTopRow}>
                      <span className={styles.categoryGlassIconBox}>
                        <img
                          src={category.iconImg}
                          alt=""
                          width="36"
                          height="36"
                          className={styles.categorySvgIcon}
                        />
                      </span>

                      <span className={styles.categoryStatusPill}>
                        {subCount > 0
                          ? `${subCount} ${subCount === 1 ? 'Specialty' : 'Specialties'}`
                          : 'Service Category'}
                      </span>
                    </div>

                    <h3 className={styles.categoryCardTitle}>{category.name}</h3>
                    <p className={styles.categoryDesc}>{category.description}</p>
                  </div>

                  <div className={styles.categoryCardBody}>
                    {subCount > 0 ? (
                      <ul className={styles.subcategoryList}>
                        {category.subcategories.map((sub) => (
                          <li key={sub.id || sub.slug} className={styles.subcategoryListItem}>
                            <button
                              type="button"
                              className={styles.subcategoryRowBtn}
                              onClick={() => handleOpenSearch(sub.name, sub.slug)}
                            >
                              <span>{sub.name}</span>
                              <ArrowRightIcon size={15} className={styles.subcategoryArrow} />
                            </button>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <div className={styles.directBookingBoxWrap}>
                        <button
                          type="button"
                          className={styles.directBookingPill}
                          onClick={() => handleOpenSearch(category.name, category.slug)}
                        >
                          <CheckCircleIcon size={16} className={styles.directBookingIcon} />
                          <span>Browse services in this category</span>
                        </button>
                      </div>
                    )}
                  </div>

                  <div className={styles.categoryCardFoot}>
                    <button
                      type="button"
                      className={styles.categoryExploreBtn}
                      onClick={() => handleOpenSearch(category.name, category.slug)}
                    >
                      <span>Explore {shortLabel}</span>
                      <span className={styles.categoryExploreOrb} aria-hidden="true">
                        <ArrowUpRightIcon size={14} className={styles.categoryExploreArrow} />
                      </span>
                    </button>
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
