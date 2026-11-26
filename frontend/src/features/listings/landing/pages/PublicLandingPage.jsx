import { useContext, useEffect, useMemo, useState } from 'react'
import { PublicLayout } from '../../../../shared/layouts/public'
import { AuthContext } from '../../../auth/context/authContextValue'
import { ADMIN_ROLES, ROLES } from '../../../auth/constants/roles'
import {
  getFeaturedPublicServices,
  getLiveCustomerReviews,
  getPublicCategoryTree,
  getPublicDistrictLocations,
} from '../services/publicCatalogService'
import { HeroScrollCanvas } from '../components/HeroScrollCanvas'
import { TrustMetricsStrip } from '../components/TrustMetricsStrip'
import { HowMarketWorksSection } from '../components/HowMarketWorksSection'
import { SriLankaCoverageMapSection } from '../components/SriLankaCoverageMapSection'
import { CategoryDirectorySection } from '../components/CategoryDirectorySection'
import { FeaturedServicesSection } from '../components/FeaturedServicesSection'
import { ProviderCommunityBanner } from '../components/ProviderCommunityBanner'
import { VerifiedReviewsMarquee } from '../components/VerifiedReviewsMarquee'
import { LandingFaqSection } from '../components/LandingFaqSection'
import styles from '../styles/publicLanding.module.css'

function resolveAccountNavigation(user, mustChangePassword) {
  if (!user) {
    return { items: [], showBecomeProvider: true }
  }
  if (mustChangePassword || user.mustChangePassword) {
    return {
      items: [{ id: 'change-password', label: 'Change Password', to: '/change-password' }],
      showBecomeProvider: false,
    }
  }
  if (user.role && ADMIN_ROLES.includes(user.role)) {
    return {
      items: [{ id: 'admin-dashboard', label: 'Admin Dashboard', to: '/admin' }],
      showBecomeProvider: false,
    }
  }
  if (user.role === ROLES.PROVIDER) {
    return {
      items: [
        { id: 'provider-profile', label: 'Provider Profile', to: '/provider/profile' },
        { id: 'provider-verification', label: 'Verification', to: '/provider/verification' },
      ],
      showBecomeProvider: false,
    }
  }
  return {
    items: [
      { id: 'profile', label: 'Profile', to: '/customer/account/profile' },
      { id: 'addresses', label: 'Saved Addresses', to: '/customer/account/addresses' },
      { id: 'favorites', label: 'Favorites', to: '/customer/account/favorites' },
      { id: 'payments', label: 'Payments', to: '/customer/account/payments' },
      { id: 'settings', label: 'Settings', to: '/customer/account/settings' },
    ],
    showBecomeProvider: true,
  }
}

export default function PublicLandingPage() {
  const auth = useContext(AuthContext)
  const isAuthenticated = Boolean(auth?.isAuthenticated && auth?.user)
  const signedInUser = isAuthenticated ? auth.user : null
  const { items: accountMenuItems, showBecomeProvider } = useMemo(
    () => resolveAccountNavigation(signedInUser, Boolean(auth?.mustChangePassword)),
    [auth?.mustChangePassword, signedInUser]
  )

  const [selectedCity, setSelectedCity] = useState('Colombo')

  const [categoriesRetryNonce, setCategoriesRetryNonce] = useState(0)
  const [categoriesState, setCategoriesState] = useState({
    resolvedKey: null,
    items: [],
    error: false,
  })

  const [liveDistricts, setLiveDistricts] = useState([])

  const [servicesRetryNonce, setServicesRetryNonce] = useState(0)
  const [servicesState, setServicesState] = useState({
    resolvedKey: null,
    items: [],
    totalCount: null,
    isIslandwideFallback: false,
    error: false,
  })

  const [reviewsRetryNonce, setReviewsRetryNonce] = useState(0)
  const [reviewsState, setReviewsState] = useState({
    resolvedKey: null,
    items: [],
    requiresAuth: false,
    error: false,
  })

  const categoriesKey = `cat-${categoriesRetryNonce}`
  const servicesKey = `svc-${selectedCity}-${servicesRetryNonce}`
  const reviewsKey = `rev-${servicesState.resolvedKey || 'init'}-${isAuthenticated ? 'auth' : 'guest'}-${reviewsRetryNonce}`

  // 1. Live active public category tree (GET /api/v1/categories)
  useEffect(() => {
    const controller = new AbortController()
    let active = true

    getPublicCategoryTree(controller.signal)
      .then((data) => {
        if (!active) return
        setCategoriesState({
          resolvedKey: categoriesKey,
          items: Array.isArray(data) ? data : [],
          error: false,
        })
      })
      .catch((err) => {
        if (!active || err?.name === 'CanceledError' || err?.name === 'AbortError') return
        setCategoriesState({
          resolvedKey: categoriesKey,
          items: [],
          error: true,
        })
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [categoriesKey])

  // 2. Canonical 9-province / 25-district Sri Lanka map coordinates (GET /api/v1/listings/locations/districts)
  useEffect(() => {
    const controller = new AbortController()
    let active = true

    getPublicDistrictLocations(controller.signal)
      .then((districts) => {
        if (!active) return
        if (Array.isArray(districts) && districts.length > 0) {
          setLiveDistricts(districts)
        }
      })
      .catch(() => {
        // Offline: SriLankaCoverageMapSection uses canonical 5 SriLankaDistrictGeoService coordinates
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [])

  // 3. Live active verified services (GET /api/v1/search — zero fake demo cards)
  useEffect(() => {
    const controller = new AbortController()
    let active = true

    const loadServices = async () => {
      try {
        const primary = await getFeaturedPublicServices({
          location: selectedCity,
          size: 6,
          signal: controller.signal,
        })
        if (!active) return

        if (primary.results.length > 0 || selectedCity === 'All Districts') {
          setServicesState({
            resolvedKey: servicesKey,
            items: primary.results,
            totalCount: primary.totalCount,
            isIslandwideFallback: false,
            error: false,
          })
          return
        }

        const islandwide = await getFeaturedPublicServices({
          location: 'All Districts',
          size: 6,
          signal: controller.signal,
        })
        if (!active) return

        setServicesState({
          resolvedKey: servicesKey,
          items: islandwide.results,
          totalCount: primary.totalCount,
          isIslandwideFallback: islandwide.results.length > 0,
          error: false,
        })
      } catch (err) {
        if (!active || err?.name === 'CanceledError' || err?.name === 'AbortError') return
        setServicesState({
          resolvedKey: servicesKey,
          items: [],
          totalCount: null,
          isIslandwideFallback: false,
          error: true,
        })
      }
    }

    loadServices()

    return () => {
      active = false
      controller.abort()
    }
  }, [selectedCity, servicesKey])

  // 4. Live Customer-to-Provider reviews (real reviews when authenticated & present, clean honest empty state otherwise)
  useEffect(() => {
    if (!servicesState.resolvedKey) return undefined
    const controller = new AbortController()
    let active = true

    getLiveCustomerReviews({
      services: servicesState.items,
      signal: controller.signal,
    })
      .then((res) => {
        if (!active) return
        setReviewsState({
          resolvedKey: reviewsKey,
          items: Array.isArray(res?.reviews) ? res.reviews : [],
          requiresAuth: Boolean(res?.requiresAuth),
          error: Boolean(res?.failed),
        })
      })
      .catch((err) => {
        if (!active || err?.name === 'CanceledError' || err?.name === 'AbortError') return
        setReviewsState({
          resolvedKey: reviewsKey,
          items: [],
          requiresAuth: false,
          error: true,
        })
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [reviewsKey, servicesState.items, servicesState.resolvedKey])

  const categoriesLoading = categoriesState.resolvedKey !== categoriesKey
  const servicesLoading = servicesState.resolvedKey !== servicesKey
  const reviewsLoading = Boolean(servicesState.resolvedKey) && reviewsState.resolvedKey !== reviewsKey
  const districtCount = liveDistricts.length > 0 ? liveDistricts.length : 25

  return (
    <PublicLayout
      selectedCity={selectedCity}
      onCityChange={setSelectedCity}
      user={signedInUser}
      accountMenuItems={accountMenuItems}
      showBecomeProvider={showBecomeProvider}
      onLogout={auth?.logout}
    >
      <div className={styles.landingRoot}>
        <HeroScrollCanvas
          selectedCity={selectedCity}
          onCityChange={setSelectedCity}
          liveDistricts={liveDistricts}
          liveListingCount={servicesState.totalCount}
          districtCount={districtCount}
        />
        <TrustMetricsStrip />
        <HowMarketWorksSection />
        <SriLankaCoverageMapSection
          selectedCity={selectedCity}
          onCityChange={setSelectedCity}
          liveDistricts={liveDistricts}
          liveCategories={categoriesState.items}
        />
        <CategoryDirectorySection
          selectedCity={selectedCity}
          liveCategories={categoriesState.items}
          loading={categoriesLoading}
          error={categoriesState.error}
          onRetry={() => setCategoriesRetryNonce((n) => n + 1)}
        />
        <FeaturedServicesSection
          selectedCity={selectedCity}
          liveServices={servicesState.items}
          loading={servicesLoading}
          error={servicesState.error}
          isIslandwideFallback={servicesState.isIslandwideFallback}
          onRetry={() => setServicesRetryNonce((n) => n + 1)}
        />
        <ProviderCommunityBanner />
        <VerifiedReviewsMarquee
          reviews={reviewsState.items}
          loading={reviewsLoading}
          requiresAuth={reviewsState.requiresAuth}
          error={reviewsState.error}
          onRetry={() => setReviewsRetryNonce((n) => n + 1)}
        />
        <LandingFaqSection />
      </div>
    </PublicLayout>
  )
}
