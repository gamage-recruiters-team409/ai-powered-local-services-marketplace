import apiClient from '../../../../shared/services/apiClient'
import tokenStore from '../../../../shared/services/tokenStore'

/**
 * Fetches the active public category tree (`GET /api/v1/categories`) for guest/public landing display.
 * Uses `_skipAuth: true` so anonymous visitors never send stale tokens or trigger session-expired flows.
 */
export async function getPublicCategoryTree(signal) {
  const response = await apiClient.get('/categories', {
    signal,
    _skipAuth: true,
  })
  return Array.isArray(response?.data) ? response.data : []
}

/**
 * Fetches the canonical 9-province / 25-district Sri Lanka geographic catalog with Leaflet coordinates
 * from the public WP03 endpoint (`GET /api/v1/listings/locations/districts`).
 */
export async function getPublicDistrictLocations(signal) {
  const response = await apiClient.get('/listings/locations/districts', {
    signal,
    _skipAuth: true,
  })
  const provinces = Array.isArray(response?.data) ? response.data : []
  const flattened = []

  provinces.forEach((prov) => {
    const provinceName = prov?.province || prov?.name || ''
    const districts = Array.isArray(prov?.districts) ? prov.districts : []
    districts.forEach((d) => {
      const districtName = d?.name || d?.district
      if (!districtName) return
      const lat = typeof d.latitude === 'number' ? d.latitude : d?.center?.latitude
      const lng = typeof d.longitude === 'number' ? d.longitude : d?.center?.longitude
      if (typeof lat !== 'number' || typeof lng !== 'number') return
      const resolvedProvince = d?.province || provinceName
      flattened.push({
        id: districtName.toLowerCase().replace(/\s+/g, '-'),
        city: districtName,
        province: resolvedProvince ? `${resolvedProvince} Province` : 'Sri Lanka',
        lat,
        lng,
        zoom: typeof d.defaultZoom === 'number' ? d.defaultZoom : 11,
      })
    })
  })

  return flattened
}

/**
 * Fetches live verified public listings (`GET /api/v1/search`) for the Featured Services showcase
 * and honest real listing counts. Uses `_skipAuth: true` and never fabricates demo records.
 */
export async function getFeaturedPublicServices({ location, size = 6, signal } = {}) {
  const params = {
    sort: 'recommended',
    page: 0,
    size,
  }
  if (location && location !== 'All Districts') {
    params.location = location
  }

  const response = await apiClient.get('/search', {
    params,
    signal,
    _skipAuth: true,
  })

  const data = response?.data || {}
  const rawList = Array.isArray(data.results) ? data.results : []
  const totalCount =
    Number.isFinite(Number(data.totalCount)) && data.totalCount != null
      ? Number(data.totalCount)
      : rawList.length

  const results = rawList.map((item, idx) => ({
    id: item.listingId ?? item.providerId ?? `live-service-${idx}`,
    providerId: item.providerId ?? null,
    profileId: item.profileId ?? null,
    title: item.title || item.name || 'Verified Local Service',
    categoryName: item.category || 'Local Service',
    providerName: item.providerName || item.name || 'Verified Provider',
    location: item.location || location || 'Sri Lanka',
    imageUrl: item.image || null,
    priceAmount:
      typeof item.pricing?.amount === 'number' && Number.isFinite(item.pricing.amount)
        ? item.pricing.amount
        : null,
    pricingModel: item.pricing?.model || 'FIXED',
    rating: typeof item.rating === 'number' ? item.rating : null,
    reviewCount: typeof item.reviewCount === 'number' ? item.reviewCount : null,
    verified: Boolean(item.verified),
    badge: item.verified ? 'Admin-Verified Pro' : 'Active Listing',
  }))

  return { results, totalCount }
}

function getInitials(name) {
  const parts = String(name || 'VC')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
  if (parts.length >= 2) {
    return `${parts[0][0]}${parts[1][0]}`.toUpperCase()
  }
  return (parts[0] || 'VC').slice(0, 2).toUpperCase()
}

function formatReviewDate(isoDate) {
  if (!isoDate) return 'Verified Completed Booking'
  try {
    const formatted = new Date(isoDate).toLocaleDateString('en-LK', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    })
    return `Verified Booking · ${formatted}`
  } catch {
    return 'Verified Completed Booking'
  }
}

/**
 * Fetches real published Customer-to-Provider reviews (`GET /api/v1/providers/{providerId}/reviews`).
 * Because `ProviderReviewController` currently enforces `@PreAuthorize("isAuthenticated()")`,
 * anonymous guests receive `{ reviews: [], requiresAuth: true }` cleanly with zero 401 console spam.
 */
export async function getLiveCustomerReviews({ services = [], signal } = {}) {
  if (!tokenStore.hasAccessToken()) {
    return { reviews: [], requiresAuth: true }
  }

  const providerById = new Map()
  services.forEach((svc) => {
    if (svc?.providerId && !providerById.has(svc.providerId)) {
      providerById.set(svc.providerId, svc)
    }
  })

  const uniqueProviders = Array.from(providerById.entries()).slice(0, 4)
  if (uniqueProviders.length === 0) {
    return { reviews: [], requiresAuth: false }
  }

  const settled = await Promise.allSettled(
    uniqueProviders.map(([providerId]) =>
      apiClient.get(`/providers/${encodeURIComponent(providerId)}/reviews`, {
        params: { page: 0, size: 3 },
        signal,
      })
    )
  )

  const collected = []
  settled.forEach((entry, idx) => {
    if (entry.status !== 'fulfilled') return
    const [, serviceMeta] = uniqueProviders[idx]
    const content = Array.isArray(entry.value?.data?.content)
      ? entry.value.data.content
      : []

    content.forEach((rev) => {
      if (!rev || (rev.status && rev.status !== 'PUBLISHED')) return
      const customerName = rev.customerName?.trim() || 'Verified Customer'
      collected.push({
        id: rev.id,
        initials: getInitials(customerName),
        customerName,
        providerName: serviceMeta.providerName || 'Verified Provider',
        location: serviceMeta.location || 'Sri Lanka',
        serviceTag: serviceMeta.categoryName || 'Verified Service',
        rating: typeof rev.rating === 'number' ? rev.rating : 5,
        quote: rev.comment?.trim() || 'Completed service rated through LocalServe.',
        meta: formatReviewDate(rev.createdAt),
      })
    })
  })

  return { reviews: collected, requiresAuth: false }
}
