import { useEffect, useState } from 'react'
import { PublicLayout } from '../../../../shared/layouts/public'
import { getPublicCategoryTree } from '../services/publicCatalogService'
import { HeroScrollCanvas } from '../components/HeroScrollCanvas'
import { TrustMetricsStrip } from '../components/TrustMetricsStrip'
import { HowMarketWorksSection } from '../components/HowMarketWorksSection'
import { CategoryDirectorySection } from '../components/CategoryDirectorySection'
import { VerifiedReviewsMarquee } from '../components/VerifiedReviewsMarquee'
import styles from '../styles/publicLanding.module.css'

export default function PublicLandingPage() {
  const [selectedCity, setSelectedCity] = useState('Colombo')
  const [liveCategories, setLiveCategories] = useState(null)

  useEffect(() => {
    const controller = new AbortController()
    getPublicCategoryTree(controller.signal)
      .then((data) => {
        if (Array.isArray(data) && data.length > 0) {
          setLiveCategories(data)
        }
      })
      .catch(() => {
        // Offline or unseeded backend: CategoryDirectorySection gracefully renders curated showcase
      })
    return () => controller.abort()
  }, [])

  return (
    <PublicLayout selectedCity={selectedCity} onCityChange={setSelectedCity}>
      <div className={styles.landingRoot}>
        <HeroScrollCanvas
          selectedCity={selectedCity}
          onCityChange={setSelectedCity}
        />
        <TrustMetricsStrip />
        <HowMarketWorksSection />
        <CategoryDirectorySection
          selectedCity={selectedCity}
          liveCategories={liveCategories}
        />
        <VerifiedReviewsMarquee />
      </div>
    </PublicLayout>
  )
}
