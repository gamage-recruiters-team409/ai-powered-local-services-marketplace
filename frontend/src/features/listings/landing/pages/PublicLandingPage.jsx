import { useEffect, useState } from 'react'
import { PublicLayout } from '../../../../shared/layouts/public'
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

export default function PublicLandingPage() {
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

  const [reviewsState, setReviewsState] = useState({
    resolvedKey: null,
    items: [],
    requiresAuth: false,
  })

  const categoriesKey = `cat-${categoriesRetryNonce}`
  const servicesKey = `svc-${selectedCity}-${servicesRetryNonce}`
  const reviewsKey = `rev-${servicesState.resolvedKey || 'init'}`

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
        })
      })
      .catch((err) => {
        if (!active || err?.name === 'CanceledError' || err?.name === 'AbortError') return
        setReviewsState({
          resolvedKey: reviewsKey,
          items: [],
          requiresAuth: false,
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
    <PublicLayout selectedCity={selectedCity} onCityChange={setSelectedCity}>
      <div className={styles.landingRoot}>
        <HeroScrollCanvas
          selectedCity={selectedCity}
          onCityChange={setSelectedCity}
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
        />
        <LandingFaqSection />
      </div>
    </PublicLayout>
  )
}
