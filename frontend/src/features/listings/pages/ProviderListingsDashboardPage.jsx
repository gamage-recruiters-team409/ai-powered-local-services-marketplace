import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { useToast } from '../../../shared/components/feedback'
import { ListingCoverMedia } from '../components/ListingCoverMedia'
import { DashboardLoadingSkeleton } from '../components/ListingSkeletons'
import {
  IconAlertTriangle,
  IconArchive,
  IconArrowRight,
  IconCheckCircle,
  IconGrid,
  IconHomePin,
  IconLayers,
  IconList,
  IconLock,
  IconMapPin,
  IconPauseCircle,
  IconPencil,
  IconPlay,
  IconPlus,
  IconRefresh,
  IconSearch,
  IconShieldAlert,
  IconStar,
  IconWorkshop,
  IconX,
} from '../components/ListingIcons'
import { useAnimatedNumber, useSlidingIndicator } from '../hooks/useListingMotion'
import {
  extractListingApiError,
  extractListingErrorCode,
  formatModerationFeedback,
  getMyListings,
  getMyListingsSummary,
  isListingRequestCanceled,
  updateListingStatus,
} from '../services/listingService'
import styles from '../styles/providerListings.module.css'

const PAGE_SIZE = 8
const SEARCH_DEBOUNCE_MS = 220

const STATUS_BADGE_MAP = {
  ACTIVE: { label: 'Active', className: styles.statusActive },
  PAUSED: { label: 'Paused', className: styles.statusPaused },
  DRAFT: { label: 'Draft', className: styles.statusDraft },
  FLAGGED: { label: 'Flagged', className: styles.statusFlagged },
  REJECTED: { label: 'Rejected', className: styles.statusFlagged },
  ARCHIVED: { label: 'Archived', className: styles.statusArchived },
}

function formatLkr(value) {
  const numeric = Number(value || 0)
  return `Rs. ${numeric.toLocaleString('en-LK', {
    minimumFractionDigits: numeric % 1 === 0 ? 0 : 2,
    maximumFractionDigits: 2,
  })}`
}

function formatPad(num) {
  return String(num).padStart(2, '0')
}

export function ProviderListingsDashboardPage() {
  const navigate = useNavigate()
  const toast = useToast()

  const [activeTab, setActiveTab] = useState('ALL')
  const [searchInput, setSearchInput] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [sortBy, setSortBy] = useState('newest')
  const [viewMode, setViewMode] = useState('cards')
  const [page, setPage] = useState(0)

  const [listings, setListings] = useState([])
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [summary, setSummary] = useState({
    totalListings: 0,
    activeCount: 0,
    pausedCount: 0,
    draftCount: 0,
    archivedCount: 0,
    flaggedCount: 0,
    rejectedCount: 0,
    categoryInactiveCount: 0,
  })

  const [loading, setLoading] = useState(true)
  const [hasLoadedOnce, setHasLoadedOnce] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')
  const [verificationNotice, setVerificationNotice] = useState('')
  const [usingFallback, setUsingFallback] = useState(false)

  const [pauseTarget, setPauseTarget] = useState(null)
  const [archiveTarget, setArchiveTarget] = useState(null)
  const [actionBusy, setActionBusy] = useState(false)
  const [busyListingId, setBusyListingId] = useState('')
  const [reloadTick, setReloadTick] = useState(0)
  const hasLoadedOnceRef = useRef(false)

  useEffect(() => {
    const trimmed = searchInput.trim()
    if (trimmed === debouncedSearch) return undefined
    const timer = window.setTimeout(() => {
      setDebouncedSearch(trimmed)
      setPage(0)
    }, SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [searchInput, debouncedSearch])

  const triggerReload = () => {
    setLoading(true)
    setErrorMessage('')
    setReloadTick((tick) => tick + 1)
  }

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    async function fetchListings() {
      setLoading(true)
      setErrorMessage('')
      try {
        const isInactiveTab = activeTab === 'INACTIVE'
        const statusParam = isInactiveTab ? 'ALL' : activeTab

        const [summaryRes, listRes] = await Promise.all([
          getMyListingsSummary(controller.signal),
          getMyListings(
            {
              status: statusParam,
              search: debouncedSearch,
              page,
              size: PAGE_SIZE,
              sortBy,
              categoryInactiveOnly: isInactiveTab,
            },
            controller.signal,
          ),
        ])

        if (!active) return
        setSummary(summaryRes.summary)
        setListings(listRes.pageData.content || [])
        setTotalElements(listRes.pageData.totalElements || 0)
        setTotalPages(Math.max(1, listRes.pageData.totalPages || 1))
        setUsingFallback(Boolean(listRes.isFallback || summaryRes.isFallback))
        if (!hasLoadedOnceRef.current) {
          hasLoadedOnceRef.current = true
          setHasLoadedOnce(true)
        }
      } catch (error) {
        if (!active || isListingRequestCanceled(error)) {
          return
        }
        setErrorMessage(extractListingApiError(error, 'Failed to load service listings.'))
        if (!hasLoadedOnceRef.current) {
          hasLoadedOnceRef.current = true
          setHasLoadedOnce(true)
        }
      } finally {
        if (active) setLoading(false)
      }
    }

    fetchListings()
    return () => {
      active = false
      controller.abort()
    }
  }, [activeTab, debouncedSearch, page, sortBy, reloadTick])

  useEffect(() => {
    const handleKeyDown = (event) => {
      if (event.key === 'Escape' && !actionBusy) {
        setPauseTarget(null)
        setArchiveTarget(null)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [actionBusy])

  const needsAttentionCount = useMemo(
    () =>
      Number(summary.flaggedCount || 0) +
      Number(summary.rejectedCount || 0) +
      Number(summary.categoryInactiveCount || 0),
    [summary],
  )

  const animatedTotal = Math.round(useAnimatedNumber(summary.totalListings))
  const animatedActive = Math.round(useAnimatedNumber(summary.activeCount))
  const animatedPausedDraft = Math.round(
    useAnimatedNumber(Number(summary.pausedCount || 0) + Number(summary.draftCount || 0)),
  )
  const animatedAttention = Math.round(useAnimatedNumber(needsAttentionCount))

  const filterTabs = useMemo(
    () => [
      { id: 'ALL', text: 'All', count: Number(summary.totalListings || 0) },
      { id: 'ACTIVE', text: 'Active', count: Number(summary.activeCount || 0) },
      { id: 'PAUSED', text: 'Paused', count: Number(summary.pausedCount || 0) },
      { id: 'DRAFT', text: 'Drafts', count: Number(summary.draftCount || 0) },
      { id: 'FLAGGED', text: 'Flagged', count: Number(summary.flaggedCount || 0) },
      { id: 'REJECTED', text: 'Rejected', count: Number(summary.rejectedCount || 0) },
      { id: 'ARCHIVED', text: 'Archive', count: Number(summary.archivedCount || 0) },
      { id: 'INACTIVE', text: 'Inactive', count: Number(summary.categoryInactiveCount || 0) },
    ],
    [summary],
  )

  const tabsSummarySig = filterTabs.map((t) => `${t.id}:${t.count}`).join('|')
  const tabsRailRef = useRef(null)
  const viewToggleRef = useRef(null)
  const tabsIndicatorStyle = useSlidingIndicator(tabsRailRef, activeTab, tabsSummarySig)
  const viewIndicatorStyle = useSlidingIndicator(viewToggleRef, viewMode, viewMode)

  const handleSelectTab = (tabId) => {
    setActiveTab(tabId)
    setPage(0)
  }

  const handleSelectAttentionKpi = () => {
    if (Number(summary.flaggedCount || 0) > 0) {
      handleSelectTab('FLAGGED')
    } else if (Number(summary.rejectedCount || 0) > 0) {
      handleSelectTab('REJECTED')
    } else if (Number(summary.categoryInactiveCount || 0) > 0) {
      handleSelectTab('INACTIVE')
    } else {
      handleSelectTab('FLAGGED')
    }
  }

  const handleSelectPausedOrDraftKpi = () => {
    if (Number(summary.pausedCount || 0) === 0 && Number(summary.draftCount || 0) > 0) {
      handleSelectTab('DRAFT')
    } else {
      handleSelectTab('PAUSED')
    }
  }

  const handleResetFilters = () => {
    setActiveTab('ALL')
    setSearchInput('')
    setDebouncedSearch('')
    setSortBy('newest')
    setPage(0)
  }

  const handleConfirmPause = async () => {
    if (!pauseTarget) return
    setActionBusy(true)
    setBusyListingId(pauseTarget.id)
    try {
      await updateListingStatus(pauseTarget.id, { status: 'PAUSED', reason: 'Paused by provider from dashboard' })
      toast.success(`"${pauseTarget.title}" is now paused and hidden from new bookings.`, {
        title: 'Listing Paused',
      })
      setPauseTarget(null)
      triggerReload()
    } catch (error) {
      toast.error(extractListingApiError(error, 'Unable to pause listing.'), { title: 'Action Failed' })
    } finally {
      setActionBusy(false)
      setBusyListingId('')
    }
  }

  const handleResumeListing = async (listing) => {
    setActionBusy(true)
    setBusyListingId(listing.id)
    setVerificationNotice('')
    try {
      const updatedRes = await updateListingStatus(listing.id, {
        status: 'ACTIVE',
        reason: listing.status === 'DRAFT' ? 'Published by provider from dashboard' : 'Reactivated by provider',
      })
      const nextStatus = updatedRes?.listing?.status || 'ACTIVE'
      if (nextStatus === 'FLAGGED') {
        toast.warning(
          `"${listing.title}" was sent for moderator review because it appears to contain contact details or external links.`,
          { title: 'Listing Under Review' },
        )
      } else {
        toast.success(`"${listing.title}" is now active and bookable.`, { title: 'Listing Activated' })
      }
      triggerReload()
    } catch (error) {
      const code = extractListingErrorCode(error)
      const msg = extractListingApiError(error, 'Unable to activate listing.')
      if (code === 'PROVIDER_NOT_VERIFIED' || code === 'PROVIDER_VERIFICATION_UNAVAILABLE') {
        setVerificationNotice(msg)
      }
      toast.error(msg, { title: 'Activation Blocked' })
    } finally {
      setActionBusy(false)
      setBusyListingId('')
    }
  }

  const handleConfirmArchive = async () => {
    if (!archiveTarget) return
    setActionBusy(true)
    setBusyListingId(archiveTarget.id)
    try {
      await updateListingStatus(archiveTarget.id, {
        status: 'ARCHIVED',
        reason: 'Permanently archived by provider from dashboard',
      })
      toast.success(`"${archiveTarget.title}" has been permanently archived.`, {
        title: 'Listing Archived',
      })
      setArchiveTarget(null)
      triggerReload()
    } catch (error) {
      toast.error(extractListingApiError(error, 'Unable to archive listing.'), { title: 'Action Failed' })
    } finally {
      setActionBusy(false)
      setBusyListingId('')
    }
  }

  const isInitialLoading = loading && !hasLoadedOnce
  const isRefreshing = loading && hasLoadedOnce
  const isAttentionFilterActive =
    activeTab === 'FLAGGED' || activeTab === 'REJECTED' || activeTab === 'INACTIVE'
  const isPausedOrDraftActive = activeTab === 'PAUSED' || activeTab === 'DRAFT'

  const startEntry = totalElements === 0 ? 0 : page * PAGE_SIZE + 1
  const endEntry = Math.min(totalElements, page * PAGE_SIZE + listings.length)

  return (
    <div className={styles.pageContainer}>
      {/* Page Header */}
      <div className={styles.pageHeader}>
        <div>
          <div className={styles.headerTitleRow}>
            <h1 className={styles.pageTitle}>My Service Listings</h1>
            {usingFallback && (
              <span className={styles.fallbackBadge} title="Explicit DEV fixture mode active">
                Fixture Mode Preview
              </span>
            )}
          </div>
          <p className={styles.pageSubtitle}>
            Manage your active trades, upfront pricing, escrow parameters, and public booking availability.
          </p>
        </div>

        <button
          type="button"
          className={styles.primaryBtn}
          onClick={() => navigate('/provider/listings/new')}
        >
          <IconPlus size={16} />
          <span>Create New Listing</span>
        </button>
      </div>

      {/* Initial Full-Page Shimmer Skeleton (KPI + Toolbar + Cards) */}
      {isInitialLoading ? (
        <DashboardLoadingSkeleton />
      ) : (
        <>
          {/* 4 Interactive Soft Claymorphic KPI Quick-Filter Tiles */}
          <div className={styles.kpiGrid} role="group" aria-label="Listing summary metrics">
        <button
          type="button"
          className={`${styles.kpiCard} ${styles.kpiCardInteractive} ${
            activeTab === 'ALL' ? styles.kpiCardSelected : ''
          }`}
          aria-pressed={activeTab === 'ALL'}
          onClick={() => handleSelectTab('ALL')}
        >
          <div className={styles.kpiTopRow}>
            <span className={styles.kpiLabel}>TOTAL LISTINGS</span>
            <span className={`${styles.kpiIconBox} ${styles.kpiIconTeal}`}>
              <IconLayers size={17} />
            </span>
          </div>
          <div className={styles.kpiValueRow}>
            <span className={styles.kpiValue}>{animatedTotal}</span>
            <span className={styles.kpiMetaMuted}>{summary.archivedCount} Archived</span>
          </div>
        </button>

        <button
          type="button"
          className={`${styles.kpiCard} ${styles.kpiCardInteractive} ${
            activeTab === 'ACTIVE' ? styles.kpiCardSelected : ''
          }`}
          aria-pressed={activeTab === 'ACTIVE'}
          onClick={() => handleSelectTab('ACTIVE')}
        >
          <div className={styles.kpiTopRow}>
            <span className={styles.kpiLabel}>ACTIVE SERVICES</span>
            <span className={`${styles.kpiIconBox} ${styles.kpiIconEmerald}`}>
              <IconCheckCircle size={17} />
            </span>
          </div>
          <div className={styles.kpiValueRow}>
            <span className={styles.kpiValue}>{animatedActive}</span>
            <span className={styles.kpiMetaPositive}>Publicly Bookable</span>
          </div>
        </button>

        <button
          type="button"
          className={`${styles.kpiCard} ${styles.kpiCardInteractive} ${
            isPausedOrDraftActive ? styles.kpiCardSelected : ''
          }`}
          aria-pressed={isPausedOrDraftActive}
          onClick={handleSelectPausedOrDraftKpi}
        >
          <div className={styles.kpiTopRow}>
            <span className={styles.kpiLabel}>PAUSED / DRAFT</span>
            <span className={`${styles.kpiIconBox} ${styles.kpiIconAmber}`}>
              <IconPauseCircle size={17} />
            </span>
          </div>
          <div className={styles.kpiValueRow}>
            <span className={styles.kpiValue}>{animatedPausedDraft}</span>
            <span className={styles.kpiMetaMuted}>
              {summary.pausedCount} Paused · {summary.draftCount} Draft
            </span>
          </div>
        </button>

        <button
          type="button"
          className={`${styles.kpiCard} ${styles.kpiCardInteractive} ${
            isAttentionFilterActive ? styles.kpiCardSelected : ''
          } ${needsAttentionCount > 0 ? styles.kpiCardAttention : ''}`}
          aria-pressed={isAttentionFilterActive}
          onClick={handleSelectAttentionKpi}
        >
          <div className={styles.kpiTopRow}>
            <span className={styles.kpiLabel}>MODERATION &amp; POLICY</span>
            <span
              className={`${styles.kpiIconBox} ${
                needsAttentionCount > 0 ? styles.kpiIconRose : styles.kpiIconEmerald
              }`}
            >
              <IconShieldAlert size={17} />
            </span>
          </div>
          <div className={styles.kpiValueRow}>
            <span
              className={`${styles.kpiValue} ${
                needsAttentionCount > 0 ? styles.kpiValueRose : ''
              }`}
            >
              {animatedAttention}
            </span>
            {needsAttentionCount > 0 ? (
              <span className={styles.kpiMetaAlert}>
                <span className={styles.kpiPulseDot} aria-hidden="true" />
                {needsAttentionCount} Action Needed
              </span>
            ) : (
              <span className={styles.kpiMetaPositive}>All Clear</span>
            )}
          </div>
          <div className={styles.kpiFooterHint}>
            {summary.flaggedCount} Flagged · {summary.rejectedCount} Rejected · {summary.categoryInactiveCount} Inactive
          </div>
        </button>
      </div>

      {/* Segmented Filter Bar with Gliding Active Pill (Option A) */}
      <div className={styles.toolbarCard}>
        <div
          ref={tabsRailRef}
          className={styles.tabsRail}
          role="tablist"
          aria-label="Filter listings by status"
        >
          <span
            className={styles.tabsSlidingPill}
            style={tabsIndicatorStyle}
            aria-hidden="true"
          />
          {filterTabs.map((tab) => {
            const isSelected = activeTab === tab.id
            const isZeroMuted = tab.count === 0 && !isSelected && tab.id !== 'ALL'
            return (
              <button
                key={tab.id}
                data-slide-key={tab.id}
                type="button"
                role="tab"
                aria-selected={isSelected}
                className={`${styles.tabBtn} ${isSelected ? styles.tabBtnActive : ''} ${
                  isZeroMuted ? styles.tabBtnMuted : ''
                }`}
                onClick={() => handleSelectTab(tab.id)}
              >
                <span>{tab.text}</span>
                <span className={`${styles.tabCountBadge} ${isSelected ? styles.tabCountBadgeActive : ''}`}>
                  {tab.count}
                </span>
              </button>
            )
          })}
        </div>

        <div className={styles.controlsGroup}>
          <div className={styles.searchInputWrap}>
            <span className={styles.searchIcon}>
              <IconSearch size={15} />
            </span>
            <input
              type="search"
              className={styles.searchInput}
              placeholder="Search services..."
              aria-label="Search services"
              value={searchInput}
              onChange={(event) => {
                setSearchInput(event.target.value)
              }}
            />
            {searchInput && (
              <button
                type="button"
                className={styles.searchClearBtn}
                aria-label="Clear search"
                onClick={() => {
                  setSearchInput('')
                  setDebouncedSearch('')
                  setPage(0)
                }}
              >
                <IconX size={13} />
              </button>
            )}
          </div>

          <label className={styles.sortSelectWrap}>
            <span>Sort by:</span>
            <select
              className={styles.sortSelect}
              aria-label="Sort listings"
              value={sortBy}
              onChange={(event) => {
                setSortBy(event.target.value)
                setPage(0)
              }}
            >
              <option value="newest">Date Updated (Newest)</option>
              <option value="price_asc">Price: Low to High</option>
              <option value="price_desc">Price: High to Low</option>
              <option value="rating_desc">Rating: Highest</option>
            </select>
          </label>

          <div
            ref={viewToggleRef}
            className={styles.viewToggleGroup}
            role="group"
            aria-label="View mode"
          >
            <span
              className={styles.viewTogglePill}
              style={viewIndicatorStyle}
              aria-hidden="true"
            />
            <button
              data-slide-key="cards"
              type="button"
              className={`${styles.viewToggleBtn} ${viewMode === 'cards' ? styles.viewToggleBtnActive : ''}`}
              aria-pressed={viewMode === 'cards'}
              onClick={() => setViewMode('cards')}
            >
              <IconGrid size={14} />
              <span>Cards</span>
            </button>
            <button
              data-slide-key="list"
              type="button"
              className={`${styles.viewToggleBtn} ${viewMode === 'list' ? styles.viewToggleBtnActive : ''}`}
              aria-pressed={viewMode === 'list'}
              onClick={() => setViewMode('list')}
            >
              <IconList size={14} />
              <span>List</span>
            </button>
          </div>
        </div>
      </div>

      {/* Subtle Non-Jarring Refresh Bar */}
      {isRefreshing && (
        <div className={styles.refreshShimmerTrack} role="status" aria-label="Updating listings">
          <div className={styles.refreshShimmerFill} />
        </div>
      )}

      {/* Account Verification Gate Notice */}
      {verificationNotice && (
        <div className={styles.policyWarningBanner} role="alert">
          <span className={styles.inlineIconText}>
            <IconShieldAlert size={17} />
            <span>
              <strong>Account Verification Required:</strong> {verificationNotice} You can save and edit drafts
              anytime, and publish once your account is verified.
            </span>
          </span>
          <button
            type="button"
            className={styles.outlineBtn}
            onClick={() => navigate('/provider/verification')}
          >
            <span>Go to Verification</span>
            <IconArrowRight size={14} />
          </button>
        </div>
      )}

      {/* Error State */}
      {errorMessage && (
        <div className={styles.policyWarningBanner} role="alert">
          <span className={styles.inlineIconText}>
            <IconAlertTriangle size={17} />
            <span>{errorMessage}</span>
          </span>
          <button type="button" className={styles.outlineBtn} onClick={triggerReload}>
            Retry
          </button>
        </div>
      )}

      {/* Empty State */}
      {!loading && !errorMessage && listings.length === 0 && (
        <div className={styles.emptyCard}>
          <div className={styles.emptyIconCircle}>
            <IconSearch size={24} />
          </div>
          <h2 className={styles.emptyTitle}>No service listings found</h2>
          <p className={styles.emptyText}>
            We couldn&apos;t find any services matching your active filters. Try clearing filters or create a new
            service listing.
          </p>
          <div className={styles.controlsGroup}>
            <button type="button" className={styles.outlineBtn} onClick={handleResetFilters}>
              <IconRefresh size={14} />
              <span>Reset All Filters</span>
            </button>
            <button
              type="button"
              className={styles.primaryBtn}
              onClick={() => navigate('/provider/listings/new')}
            >
              <IconPlus size={15} />
              <span>Create Listing</span>
            </button>
          </div>
        </div>
      )}

      {/* Grid View */}
      {!isInitialLoading && listings.length > 0 && viewMode === 'cards' && (
        <div className={`${styles.cardsGrid} ${isRefreshing ? styles.contentRefreshing : ''}`}>
          {listings.map((listing, cardIdx) => {
            const categoryInactive = listing.categoryActive === false
            const isArchived = listing.status === 'ARCHIVED'
            const isPaused = listing.status === 'PAUSED'
            const isDraft = listing.status === 'DRAFT'
            const isFlaggedOrRejected = listing.status === 'FLAGGED' || listing.status === 'REJECTED'
            const badge = STATUS_BADGE_MAP[listing.status] || STATUS_BADGE_MAP.ACTIVE
            const unitLabel = listing.pricingModel === 'HOURLY' ? '/ hr' : '/ fixed'
            const imageSrc = listing.coverImageUrl || listing.images?.[0] || ''
            const hasPrice = Number(listing.basePrice || 0) > 0
            const hasRatings = Number(listing.ratingCount || 0) > 0
            const isThisBusy = busyListingId === listing.id

            return (
              <article
                key={listing.id}
                style={{ '--card-idx': Math.min(cardIdx, 7) }}
                className={`${styles.listingCard} ${categoryInactive ? styles.listingCardInactive : ''}`}
              >
                <div className={styles.cardMediaWrap}>
                  <ListingCoverMedia
                    src={imageSrc}
                    alt={listing.title}
                    categoryName={listing.categoryName || 'Service Category'}
                    isDraft={isDraft}
                    dimmed={categoryInactive || isPaused || isArchived}
                  />
                </div>

                <div className={styles.cardBody}>
                  <div className={styles.cardCategoryRow}>
                    <span className={styles.categoryChip}>{listing.categoryName || 'Service Category'}</span>
                    {categoryInactive ? (
                      <span className={styles.categoryInactiveChip}>
                        <IconLock size={11} />
                        <span>Category Inactive</span>
                      </span>
                    ) : (
                      <span className={`${styles.statusInlineBadge} ${badge.className}`}>
                        <span className={styles.statusDot} aria-hidden="true" />
                        <span>{badge.label}</span>
                      </span>
                    )}
                  </div>

                  <h3 className={styles.cardTitle}>{listing.title}</h3>

                  <div className={styles.cardPriceRow}>
                    {hasPrice ? (
                      <>
                        <span>From</span>
                        <strong className={styles.cardPriceStrong}>{formatLkr(listing.basePrice)}</strong>
                        <span>{unitLabel}</span>
                      </>
                    ) : (
                      <span className={styles.priceUnsetBadge}>Price not set yet</span>
                    )}
                  </div>

                  <div className={styles.cardLocationStrip}>
                    <span className={styles.cardLocationItem}>
                      <IconMapPin size={13} />
                      <span>{listing.baseCity || listing.district || 'Colombo'}</span>
                    </span>
                    <span className={styles.cardLocationDot} aria-hidden="true">·</span>
                    <span className={styles.cardLocationItem}>
                      {listing.executionModel === 'WORKSHOP' ? (
                        <>
                          <IconWorkshop size={13} />
                          <span>Workshop</span>
                        </>
                      ) : (
                        <>
                          <IconHomePin size={13} />
                          <span>On-Site</span>
                        </>
                      )}
                    </span>
                  </div>

                  {categoryInactive && (
                    <div className={styles.policyCalloutBox}>
                      <span className={styles.inlineIconText}>
                        <IconAlertTriangle size={14} />
                        <span>
                          Public bookings temporarily disabled by admin policy. Click Edit to assign an active category.
                        </span>
                      </span>
                    </div>
                  )}

                  {isFlaggedOrRejected && (
                    <div
                      className={`${styles.policyCalloutBox} ${
                        listing.status === 'REJECTED' ? styles.policyCalloutRejected : ''
                      }`}
                    >
                      <strong className={styles.policyCalloutTitle}>
                        <IconShieldAlert size={14} />
                        <span>
                          {listing.status === 'REJECTED'
                            ? 'Rejected by Moderator'
                            : 'Under Moderator Review'}
                        </span>
                      </strong>
                      <span>
                        {formatModerationFeedback(listing.moderationReason, listing.moderationFlags)}
                      </span>
                    </div>
                  )}

                  <div className={styles.cardMetaRow}>
                    <span className={styles.addOnsChip}>{listing.activeAddOnCount ?? 0} Add-Ons</span>
                    {hasRatings ? (
                      <span className={styles.ratingMeta}>
                        <IconStar size={13} filled className={styles.ratingStarIcon} />
                        <span>
                          {Number(listing.ratingAvg || 0).toFixed(1)} ({listing.ratingCount})
                        </span>
                      </span>
                    ) : (
                      <span className={styles.newRatingPill}>New listing</span>
                    )}
                  </div>

                  <div
                    className={`${styles.cardActionsRow} ${
                      isFlaggedOrRejected ? styles.cardActionsRowTwo : ''
                    }`}
                  >
                    <button
                      type="button"
                      className={`${styles.cardActionBtn} ${
                        isFlaggedOrRejected ? styles.cardActionBtnPrimary : ''
                      }`}
                      disabled={isArchived || actionBusy}
                      onClick={() => navigate(`/provider/listings/${listing.id}/edit`)}
                    >
                      <IconPencil size={13} />
                      <span>{isFlaggedOrRejected ? 'Fix & Resubmit' : 'Edit'}</span>
                    </button>

                    {!isFlaggedOrRejected &&
                      (isPaused ? (
                        <button
                          type="button"
                          className={styles.cardActionBtn}
                          disabled={categoryInactive || isArchived || actionBusy}
                          onClick={() => handleResumeListing(listing)}
                        >
                          <IconPlay size={13} />
                          <span>{isThisBusy ? 'Activating...' : 'Resume'}</span>
                        </button>
                      ) : isDraft ? (
                        <button
                          type="button"
                          className={`${styles.cardActionBtn} ${styles.cardActionBtnPrimary}`}
                          disabled={categoryInactive || isArchived || actionBusy}
                          onClick={() => handleResumeListing(listing)}
                        >
                          <IconPlay size={13} />
                          <span>{isThisBusy ? 'Publishing...' : 'Publish'}</span>
                        </button>
                      ) : (
                        <button
                          type="button"
                          className={styles.cardActionBtn}
                          disabled={
                            categoryInactive ||
                            isArchived ||
                            listing.status !== 'ACTIVE' ||
                            actionBusy
                          }
                          onClick={() => setPauseTarget(listing)}
                        >
                          <IconPauseCircle size={13} />
                          <span>Pause</span>
                        </button>
                      ))}

                    <button
                      type="button"
                      className={`${styles.cardActionBtn} ${styles.cardActionBtnArchive}`}
                      disabled={isArchived || actionBusy}
                      onClick={() => setArchiveTarget(listing)}
                    >
                      <IconArchive size={13} />
                      <span>Archive</span>
                    </button>
                  </div>
                </div>
              </article>
            )
          })}
        </div>
      )}

      {/* List / Table View */}
      {!isInitialLoading && listings.length > 0 && viewMode === 'list' && (
        <div className={`${styles.tableCard} ${isRefreshing ? styles.contentRefreshing : ''}`}>
          <div className={styles.tableScroll}>
            <table className={styles.listingsTable}>
              <thead>
                <tr>
                  <th>SERVICE NAME &amp; CATEGORY</th>
                  <th>PRICE &amp; BILLING MODEL</th>
                  <th>ADD-ONS &amp; RATING</th>
                  <th>LOCATION &amp; DELIVERY</th>
                  <th>STATUS</th>
                  <th>ACTIONS</th>
                </tr>
              </thead>
              <tbody>
                {listings.map((listing) => {
                  const categoryInactive = listing.categoryActive === false
                  const isArchived = listing.status === 'ARCHIVED'
                  const isPaused = listing.status === 'PAUSED'
                  const isDraft = listing.status === 'DRAFT'
                  const isFlaggedOrRejected = listing.status === 'FLAGGED' || listing.status === 'REJECTED'
                  const badge = STATUS_BADGE_MAP[listing.status] || STATUS_BADGE_MAP.ACTIVE
                  const imageSrc = listing.coverImageUrl || listing.images?.[0] || ''
                  const hasPrice = Number(listing.basePrice || 0) > 0
                  const hasRatings = Number(listing.ratingCount || 0) > 0

                  return (
                    <tr key={listing.id}>
                      <td>
                        <div className={styles.tableServiceCell}>
                          <ListingCoverMedia
                            src={imageSrc}
                            alt={listing.title}
                            categoryName={listing.categoryName || 'Service Category'}
                            isDraft={isDraft}
                            compact
                          />
                          <div>
                            <strong>{listing.title}</strong>
                            <div className={styles.cardCategoryRow} style={{ marginTop: 4 }}>
                              <span className={styles.categoryChip}>{listing.categoryName}</span>
                              {categoryInactive && (
                                <span className={styles.fieldHelper}>· Admin Category Inactive</span>
                              )}
                            </div>
                          </div>
                        </div>
                      </td>
                      <td>
                        {hasPrice ? (
                          <strong>{formatLkr(listing.basePrice)}</strong>
                        ) : (
                          <span className={styles.priceUnsetBadge}>Not set</span>
                        )}
                        <div className={styles.fieldHelper}>
                          {listing.pricingModel === 'HOURLY' ? 'Hourly rate' : 'Fixed rate'} · {listing.durationMin || 60} mins
                        </div>
                      </td>
                      <td>
                        {hasRatings ? (
                          <div className={styles.ratingMeta}>
                            <IconStar size={13} filled className={styles.ratingStarIcon} />
                            <span>
                              {Number(listing.ratingAvg || 0).toFixed(1)} ({listing.ratingCount})
                            </span>
                          </div>
                        ) : (
                          <span className={styles.newRatingPill}>New listing</span>
                        )}
                        <div className={styles.fieldHelper} style={{ marginTop: 4 }}>
                          {listing.activeAddOnCount ?? 0} active add-ons
                        </div>
                      </td>
                      <td>
                        <div>{listing.district || 'Colombo'}</div>
                        <div className={styles.fieldHelper}>
                          {listing.baseCity || listing.district} ·{' '}
                          {listing.executionModel === 'WORKSHOP' ? 'Workshop' : 'On-Site'}
                        </div>
                      </td>
                      <td>
                        {categoryInactive ? (
                          <span className={`${styles.statusInlineBadge} ${styles.statusPaused}`}>
                            <IconLock size={11} />
                            <span>Inactive</span>
                          </span>
                        ) : (
                          <span className={`${styles.statusInlineBadge} ${badge.className}`}>
                            <span className={styles.statusDot} aria-hidden="true" />
                            <span>{badge.label}</span>
                          </span>
                        )}
                      </td>
                      <td>
                        <div className={styles.tableActionsCell}>
                          <button
                            type="button"
                            className={styles.iconActionBtn}
                            title={isFlaggedOrRejected ? 'Fix & Resubmit listing' : 'Edit listing'}
                            aria-label={`${isFlaggedOrRejected ? 'Fix & Resubmit' : 'Edit'} ${listing.title}`}
                            disabled={isArchived || actionBusy}
                            onClick={() => navigate(`/provider/listings/${listing.id}/edit`)}
                          >
                            <IconPencil size={15} />
                          </button>
                          {!isFlaggedOrRejected && (
                            <button
                              type="button"
                              className={styles.iconActionBtn}
                              title={isPaused || isDraft ? 'Activate listing' : 'Pause listing'}
                              aria-label={`${isPaused || isDraft ? 'Resume' : 'Pause'} ${listing.title}`}
                              disabled={
                                categoryInactive ||
                                isArchived ||
                                (!isPaused && !isDraft && listing.status !== 'ACTIVE') ||
                                actionBusy
                              }
                              onClick={() =>
                                isPaused || isDraft ? handleResumeListing(listing) : setPauseTarget(listing)
                              }
                            >
                              {isPaused || isDraft ? <IconPlay size={15} /> : <IconPauseCircle size={15} />}
                            </button>
                          )}
                          <button
                            type="button"
                            className={`${styles.iconActionBtn} ${styles.iconActionBtnDanger}`}
                            title="Archive listing"
                            aria-label={`Archive ${listing.title}`}
                            disabled={isArchived || actionBusy}
                            onClick={() => setArchiveTarget(listing)}
                          >
                            <IconArchive size={15} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Pagination Bar */}
      {totalElements > 0 && (
        <div className={styles.paginationRow}>
          <span>
            Showing entries: <strong>{formatPad(startEntry)}</strong> to <strong>{formatPad(endEntry)}</strong> of{' '}
            <strong>{formatPad(totalElements)}</strong> Total Services
          </span>
          <div className={styles.paginationControls}>
            <button
              type="button"
              className={styles.outlineBtn}
              disabled={page <= 0}
              onClick={() => setPage((prev) => Math.max(0, prev - 1))}
            >
              ‹ Prev
            </button>
            <span>
              Page: <strong>{page + 1} / {totalPages}</strong>
            </span>
            <button
              type="button"
              className={styles.outlineBtn}
              disabled={page + 1 >= totalPages}
              onClick={() => setPage((prev) => prev + 1)}
            >
              Next ›
            </button>
          </div>
        </div>
      )}
        </>
      )}

      {/* Pause Service Listing Confirmation Modal */}
      {pauseTarget && (
        <div
          className={styles.modalBackdrop}
          role="presentation"
          onClick={(event) => {
            if (event.target === event.currentTarget && !actionBusy) {
              setPauseTarget(null)
            }
          }}
        >
          <div className={styles.modalDialog} role="dialog" aria-modal="true" aria-labelledby="pause-modal-title">
            <div className={styles.modalHeader}>
              <div className={styles.modalTitleGroup}>
                <span className={styles.modalIconAmber}>
                  <IconPauseCircle size={20} />
                </span>
                <div>
                  <h2 id="pause-modal-title" className={styles.modalTitle}>Pause Service Listing?</h2>
                  <p className={styles.modalSubtitle}>{pauseTarget.title}</p>
                </div>
              </div>
              <button
                type="button"
                className={styles.ghostBtn}
                aria-label="Close modal"
                disabled={actionBusy}
                onClick={() => setPauseTarget(null)}
              >
                <IconX size={16} />
              </button>
            </div>

            <p className={styles.modalBodyText}>
              Temporarily disables new customer bookings for this service while keeping it visible as paused in
              customer favourites. Use this when you are fully booked or away. You can reactivate anytime with one
              click.
            </p>

            <div className={styles.modalFooter}>
              <button
                type="button"
                className={styles.outlineBtn}
                disabled={actionBusy}
                onClick={() => setPauseTarget(null)}
              >
                Keep Active
              </button>
              <button
                type="button"
                className={styles.amberBtn}
                disabled={actionBusy}
                onClick={handleConfirmPause}
              >
                {actionBusy ? 'Pausing...' : 'Pause Service'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Archive Service Listing Confirmation Modal */}
      {archiveTarget && (
        <div
          className={styles.modalBackdrop}
          role="presentation"
          onClick={(event) => {
            if (event.target === event.currentTarget && !actionBusy) {
              setArchiveTarget(null)
            }
          }}
        >
          <div className={styles.modalDialog} role="dialog" aria-modal="true" aria-labelledby="archive-modal-title">
            <div className={styles.modalHeader}>
              <div className={styles.modalTitleGroup}>
                <span className={styles.modalIconRed}>
                  <IconAlertTriangle size={20} />
                </span>
                <div>
                  <h2 id="archive-modal-title" className={styles.modalTitle}>
                    Archive &lsquo;{archiveTarget.title}&rsquo;?
                  </h2>
                  <p className={styles.modalSubtitle}>Service Action Confirmation</p>
                </div>
              </div>
              <button
                type="button"
                className={styles.ghostBtn}
                aria-label="Close modal"
                disabled={actionBusy}
                onClick={() => setArchiveTarget(null)}
              >
                <IconX size={16} />
              </button>
            </div>

            <p className={styles.modalBodyText}>
              This service listing will be permanently removed from customer view and cannot be reactivated.
              Customers will no longer find or book this service. All existing and in-progress bookings remain intact.
            </p>

            <div className={styles.archiveInfoCard}>
              <div className={styles.archiveInfoRow}>
                <span><strong># Listing ID:</strong> {archiveTarget.id}</span>
                <span>
                  Rate: <strong>{formatLkr(archiveTarget.basePrice)}{archiveTarget.pricingModel === 'HOURLY' ? '/hr' : ''}</strong>
                </span>
              </div>
              <div className={styles.kpiMetaEmerald}>
                <span className={styles.inlineIconText}>
                  <IconCheckCircle size={14} />
                  <span>Existing &amp; upcoming booked slots protected &amp; intact</span>
                </span>
              </div>
            </div>

            <p className={styles.modalWarningNote}>
              Permanent removal: cannot be undone or re-listed automatically
            </p>

            <div className={styles.modalFooter}>
              <button
                type="button"
                className={styles.outlineBtn}
                disabled={actionBusy}
                onClick={() => setArchiveTarget(null)}
              >
                Cancel
              </button>
              <button
                type="button"
                className={styles.dangerBtn}
                disabled={actionBusy}
                onClick={handleConfirmArchive}
              >
                {actionBusy ? 'Archiving...' : 'Archive Listing'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
