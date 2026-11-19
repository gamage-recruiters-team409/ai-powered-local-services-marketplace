import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { SkeletonCard, useToast } from '../../../shared/components/feedback'
import {
  DEFAULT_PORTFOLIO_THUMBNAILS,
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
  const [searchQuery, setSearchQuery] = useState('')
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
  const [errorMessage, setErrorMessage] = useState('')
  const [verificationNotice, setVerificationNotice] = useState('')
  const [usingFallback, setUsingFallback] = useState(false)

  const [pauseTarget, setPauseTarget] = useState(null)
  const [archiveTarget, setArchiveTarget] = useState(null)
  const [actionBusy, setActionBusy] = useState(false)
  const [reloadTick, setReloadTick] = useState(0)

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
              search: searchQuery,
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
      } catch (error) {
        if (!active || isListingRequestCanceled(error)) {
          return
        }
        setErrorMessage(extractListingApiError(error, 'Failed to load service listings.'))
      } finally {
        if (active) setLoading(false)
      }
    }

    fetchListings()
    return () => {
      active = false
      controller.abort()
    }
  }, [activeTab, searchQuery, page, sortBy, reloadTick])

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

  const filterTabs = useMemo(
    () => [
      { id: 'ALL', label: `All (${summary.totalListings})` },
      { id: 'ACTIVE', label: `Active (${summary.activeCount})` },
      { id: 'PAUSED', label: `Paused (${summary.pausedCount})` },
      { id: 'DRAFT', label: `Drafts (${summary.draftCount})` },
      { id: 'FLAGGED', label: `Flagged (${summary.flaggedCount})` },
      { id: 'REJECTED', label: `Rejected (${summary.rejectedCount})` },
      { id: 'ARCHIVED', label: `Archive (${summary.archivedCount})` },
      { id: 'INACTIVE', label: `Inactive (${summary.categoryInactiveCount})` },
    ],
    [summary],
  )

  const handleSelectTab = (tabId) => {
    setActiveTab(tabId)
    setPage(0)
  }

  const handleResetFilters = () => {
    setActiveTab('ALL')
    setSearchQuery('')
    setSortBy('newest')
    setPage(0)
  }

  const handleConfirmPause = async () => {
    if (!pauseTarget) return
    setActionBusy(true)
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
    }
  }

  const handleResumeListing = async (listing) => {
    setActionBusy(true)
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
    }
  }

  const handleConfirmArchive = async () => {
    if (!archiveTarget) return
    setActionBusy(true)
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
    }
  }

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
          <span>+</span>
          <span>Create New Listing</span>
        </button>
      </div>

      {/* 4 KPI Summary Cards (100% Backend-Backed Metrics) */}
      <div className={styles.kpiGrid} aria-label="Listing summary metrics">
        <div className={styles.kpiCard}>
          <div className={styles.kpiTopRow}>
            <span className={styles.kpiLabel}>TOTAL LISTINGS</span>
            <span className={styles.kpiIconBox} aria-hidden="true">≡</span>
          </div>
          <div className={styles.kpiValueRow}>
            <span className={styles.kpiValue}>{summary.totalListings}</span>
            <span className={styles.kpiMetaMuted}>{summary.archivedCount} Archived</span>
          </div>
        </div>

        <div className={styles.kpiCard}>
          <div className={styles.kpiTopRow}>
            <span className={styles.kpiLabel}>ACTIVE SERVICES</span>
            <span className={`${styles.kpiIconBox} ${styles.kpiIconEmerald}`} aria-hidden="true">✓</span>
          </div>
          <div className={styles.kpiValueRow}>
            <span className={`${styles.kpiValue} ${styles.kpiValueEmerald}`}>{summary.activeCount}</span>
            <span className={styles.kpiMetaPill}>Publicly Bookable</span>
          </div>
        </div>

        <div className={styles.kpiCard}>
          <div className={styles.kpiTopRow}>
            <span className={styles.kpiLabel}>PAUSED / DRAFT</span>
            <span className={`${styles.kpiIconBox} ${styles.kpiIconAmber}`} aria-hidden="true">❚❚</span>
          </div>
          <div className={styles.kpiValueRow}>
            <span className={styles.kpiValue}>{summary.pausedCount + summary.draftCount}</span>
            <span className={styles.kpiMetaMuted}>
              {summary.pausedCount} Paused · {summary.draftCount} Draft
            </span>
          </div>
        </div>

        <div className={styles.kpiCard}>
          <div className={styles.kpiTopRow}>
            <span className={styles.kpiLabel}>MODERATION &amp; POLICY</span>
            <span
              className={`${styles.kpiIconBox} ${
                needsAttentionCount > 0 ? styles.kpiIconAmber : styles.kpiIconEmerald
              }`}
              aria-hidden="true"
            >
              🛡
            </span>
          </div>
          <div className={styles.kpiValueRow}>
            <span className={styles.kpiValue}>{needsAttentionCount}</span>
            <span className={styles.kpiMetaMuted}>
              {summary.flaggedCount} Flagged · {summary.rejectedCount} Rejected · {summary.categoryInactiveCount} Inactive
            </span>
          </div>
        </div>
      </div>

      {/* Filter Tabs, Search, Sort & View Toggle */}
      <div className={styles.toolbarCard}>
        <div className={styles.tabsGroup} role="tablist" aria-label="Filter listings by status">
          {filterTabs.map((tab) => (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.id}
              className={`${styles.tabBtn} ${activeTab === tab.id ? styles.tabBtnActive : ''}`}
              onClick={() => handleSelectTab(tab.id)}
            >
              {tab.label}
            </button>
          ))}
        </div>

        <div className={styles.controlsGroup}>
          <div className={styles.searchInputWrap}>
            <span className={styles.searchIcon} aria-hidden="true">🔍</span>
            <input
              type="search"
              className={styles.searchInput}
              placeholder="Search services..."
              aria-label="Search services"
              value={searchQuery}
              onChange={(event) => {
                setSearchQuery(event.target.value)
                setPage(0)
              }}
            />
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

          <div className={styles.viewToggleGroup} role="group" aria-label="View mode">
            <button
              type="button"
              className={`${styles.viewToggleBtn} ${viewMode === 'cards' ? styles.viewToggleBtnActive : ''}`}
              aria-pressed={viewMode === 'cards'}
              onClick={() => setViewMode('cards')}
            >
              ⊞ Cards
            </button>
            <button
              type="button"
              className={`${styles.viewToggleBtn} ${viewMode === 'list' ? styles.viewToggleBtnActive : ''}`}
              aria-pressed={viewMode === 'list'}
              onClick={() => setViewMode('list')}
            >
              ☰ List
            </button>
          </div>
        </div>
      </div>

      {/* Account Verification Gate Notice */}
      {verificationNotice && (
        <div className={styles.policyWarningBanner} role="alert">
          <span>
            🛡 <strong>Account Verification Required:</strong> {verificationNotice} You can save and edit drafts
            anytime, and publish once your account is verified.
          </span>
          <button
            type="button"
            className={styles.outlineBtn}
            onClick={() => navigate('/provider/verification')}
          >
            Go to Verification →
          </button>
        </div>
      )}

      {/* Error State */}
      {errorMessage && (
        <div className={styles.policyWarningBanner} role="alert">
          <span>{errorMessage}</span>
          <button type="button" className={styles.outlineBtn} onClick={triggerReload}>
            Retry
          </button>
        </div>
      )}

      {/* Loading Skeleton Grid */}
      {loading && (
        <div className={styles.cardsGrid} aria-label="Loading service listings">
          {Array.from({ length: 4 }, (_, index) => (
            <SkeletonCard key={index} />
          ))}
        </div>
      )}

      {/* Empty State */}
      {!loading && !errorMessage && listings.length === 0 && (
        <div className={styles.emptyCard}>
          <div className={styles.emptyIconCircle} aria-hidden="true">🔍</div>
          <h2 className={styles.emptyTitle}>No service listings found</h2>
          <p className={styles.emptyText}>
            We couldn&apos;t find any services matching your active filters. Try clearing filters or create a new
            service listing.
          </p>
          <button type="button" className={styles.outlineBtn} onClick={handleResetFilters}>
            ↻ Reset All Filters
          </button>
        </div>
      )}

      {/* Grid View */}
      {!loading && listings.length > 0 && viewMode === 'cards' && (
        <div className={styles.cardsGrid}>
          {listings.map((listing) => {
            const categoryInactive = listing.categoryActive === false
            const isArchived = listing.status === 'ARCHIVED'
            const isPaused = listing.status === 'PAUSED'
            const isDraft = listing.status === 'DRAFT'
            const isFlaggedOrRejected = listing.status === 'FLAGGED' || listing.status === 'REJECTED'
            const badge = STATUS_BADGE_MAP[listing.status] || STATUS_BADGE_MAP.ACTIVE
            const unitLabel = listing.pricingModel === 'HOURLY' ? '/ hr' : '/ fixed'
            const imageSrc = listing.coverImageUrl || listing.images?.[0] || DEFAULT_PORTFOLIO_THUMBNAILS.plumbing

            return (
              <article
                key={listing.id}
                className={`${styles.listingCard} ${categoryInactive ? styles.listingCardInactive : ''}`}
              >
                <div className={styles.cardMediaWrap}>
                  <img
                    src={imageSrc}
                    alt={listing.title}
                    className={`${styles.cardImage} ${categoryInactive || isPaused || isArchived ? styles.cardImageDimmed : ''}`}
                    onError={(event) => {
                      event.currentTarget.src = DEFAULT_PORTFOLIO_THUMBNAILS.plumbing
                    }}
                  />
                  {!categoryInactive && (
                    <span className={`${styles.statusOverlayBadge} ${badge.className}`}>
                      ● {badge.label}
                    </span>
                  )}
                </div>

                <div className={styles.cardBody}>
                  <div className={styles.cardCategoryRow}>
                    <span className={styles.categoryChip}>{listing.categoryName || 'Service Category'}</span>
                    {categoryInactive && (
                      <span className={styles.categoryInactiveChip}>🔒 Category Inactive</span>
                    )}
                  </div>

                  <h3 className={styles.cardTitle}>{listing.title}</h3>

                  <div className={styles.cardPriceRow}>
                    <span>From</span>
                    <strong className={styles.cardPriceStrong}>{formatLkr(listing.basePrice)}</strong>
                    <span>{unitLabel}</span>
                  </div>

                  {categoryInactive && (
                    <div className={styles.policyCalloutBox}>
                      ⚠ Public bookings temporarily disabled by admin policy. Click Edit to assign an active category.
                    </div>
                  )}

                  {isFlaggedOrRejected && (
                    <div
                      className={`${styles.policyCalloutBox} ${
                        listing.status === 'REJECTED' ? styles.policyCalloutRejected : ''
                      }`}
                    >
                      <strong className={styles.policyCalloutTitle}>
                        {listing.status === 'REJECTED'
                          ? '🛡 Rejected by Moderator'
                          : '🛡 Under Moderator Review'}
                      </strong>
                      <span>
                        {formatModerationFeedback(listing.moderationReason, listing.moderationFlags)}
                      </span>
                    </div>
                  )}

                  <div className={styles.cardMetaRow}>
                    <span className={styles.addOnsChip}>{listing.activeAddOnCount ?? 0} Add-Ons</span>
                    <span className={styles.ratingMeta}>
                      ★ {Number(listing.ratingAvg || 0).toFixed(1)} ({listing.ratingCount || 0})
                    </span>
                  </div>

                  <div
                    className={`${styles.cardActionsRow} ${
                      isFlaggedOrRejected ? styles.cardActionsRowTwo : ''
                    }`}
                  >
                    <button
                      type="button"
                      className={styles.cardActionBtn}
                      disabled={isArchived || actionBusy}
                      onClick={() => navigate(`/provider/listings/${listing.id}/edit`)}
                    >
                      Edit
                    </button>

                    {!isFlaggedOrRejected &&
                      (isPaused ? (
                        <button
                          type="button"
                          className={styles.cardActionBtn}
                          disabled={categoryInactive || isArchived || actionBusy}
                          onClick={() => handleResumeListing(listing)}
                        >
                          Resume
                        </button>
                      ) : isDraft ? (
                        <button
                          type="button"
                          className={styles.cardActionBtn}
                          disabled={categoryInactive || isArchived || actionBusy}
                          onClick={() => handleResumeListing(listing)}
                        >
                          Publish
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
                          Pause
                        </button>
                      ))}

                    <button
                      type="button"
                      className={styles.cardActionBtn}
                      disabled={isArchived || actionBusy}
                      onClick={() => setArchiveTarget(listing)}
                    >
                      Archive
                    </button>
                  </div>
                </div>
              </article>
            )
          })}
        </div>
      )}

      {/* List / Table View */}
      {!loading && listings.length > 0 && viewMode === 'list' && (
        <div className={styles.tableCard}>
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
                  const badge = STATUS_BADGE_MAP[listing.status] || STATUS_BADGE_MAP.ACTIVE
                  const imageSrc = listing.coverImageUrl || listing.images?.[0] || DEFAULT_PORTFOLIO_THUMBNAILS.plumbing

                  return (
                    <tr key={listing.id}>
                      <td>
                        <div className={styles.tableServiceCell}>
                          <img
                            src={imageSrc}
                            alt={listing.title}
                            className={styles.tableThumb}
                            onError={(event) => {
                              event.currentTarget.src = DEFAULT_PORTFOLIO_THUMBNAILS.plumbing
                            }}
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
                        <strong>{formatLkr(listing.basePrice)}</strong>
                        <div className={styles.fieldHelper}>
                          {listing.pricingModel === 'HOURLY' ? 'Hourly rate' : 'Fixed rate'} · {listing.durationMin || 60} mins
                        </div>
                      </td>
                      <td>
                        <div>★ {Number(listing.ratingAvg || 0).toFixed(1)} ({listing.ratingCount || 0})</div>
                        <div className={styles.fieldHelper}>{listing.activeAddOnCount ?? 0} active add-ons</div>
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
                          <span className={`${styles.statusOverlayBadge} ${styles.statusPaused}`} style={{ position: 'static' }}>
                            🔒 Inactive
                          </span>
                        ) : (
                          <span className={`${styles.statusOverlayBadge} ${badge.className}`} style={{ position: 'static' }}>
                            ● {badge.label}
                          </span>
                        )}
                      </td>
                      <td>
                        <div className={styles.tableActionsCell}>
                          <button
                            type="button"
                            className={styles.iconActionBtn}
                            title="Edit listing"
                            aria-label={`Edit ${listing.title}`}
                            disabled={isArchived || actionBusy}
                            onClick={() => navigate(`/provider/listings/${listing.id}/edit`)}
                          >
                            ✎
                          </button>
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
                            {isPaused || isDraft ? '▶' : '❚❚'}
                          </button>
                          <button
                            type="button"
                            className={styles.iconActionBtn}
                            title="Archive listing"
                            aria-label={`Archive ${listing.title}`}
                            disabled={isArchived || actionBusy}
                            onClick={() => setArchiveTarget(listing)}
                          >
                            🗄
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
      {!loading && totalElements > 0 && (
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
                <span className={styles.modalIconAmber} aria-hidden="true">❚❚</span>
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
                ✕
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
                <span className={styles.modalIconRed} aria-hidden="true">⚠</span>
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
                ✕
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
              <div className={styles.kpiMetaEmerald}>✓ Existing &amp; upcoming booked slots protected &amp; intact</div>
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
