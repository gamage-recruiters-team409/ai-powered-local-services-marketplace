import { useEffect, useRef } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

import { SRI_LANKA_BOUNDS } from '../services/sriLankaZones'
import styles from '../styles/providerListings.module.css'

function getZoomForRadius(radiusKm) {
  if (radiusKm <= 5) return 13
  if (radiusKm <= 15) return 11
  if (radiusKm <= 30) return 10
  return 9
}

function clampSriLankaCoords(lat, lng) {
  return {
    latitude: Number(Math.min(SRI_LANKA_BOUNDS.maxLat, Math.max(SRI_LANKA_BOUNDS.minLat, lat)).toFixed(4)),
    longitude: Number(Math.min(SRI_LANKA_BOUNDS.maxLng, Math.max(SRI_LANKA_BOUNDS.minLng, lng)).toFixed(4)),
  }
}

/**
 * Interactive OpenStreetMap / Leaflet.js map displaying the selected dispatch base coordinates
 * and dynamic kilometer coverage radius circle (synced with SriLankaDistrictGeoService coordinates).
 */
export function CoverageLeafletMap({
  latitude = 6.9271,
  longitude = 79.8612,
  radiusKm = 15,
  districtLabel = 'Colombo',
  onCoordinateChange,
}) {
  const containerRef = useRef(null)
  const mapRef = useRef(null)
  const markerRef = useRef(null)
  const circleRef = useRef(null)
  const callbackRef = useRef(onCoordinateChange)

  useEffect(() => {
    callbackRef.current = onCoordinateChange
  }, [onCoordinateChange])

  useEffect(() => {
    const container = containerRef.current
    if (!container || mapRef.current) return undefined

    try {
      const mapBounds = L.latLngBounds(
        [SRI_LANKA_BOUNDS.minLat - 0.35, SRI_LANKA_BOUNDS.minLng - 0.35],
        [SRI_LANKA_BOUNDS.maxLat + 0.35, SRI_LANKA_BOUNDS.maxLng + 0.35],
      )
      const mapInstance = L.map(container, {
        center: [latitude, longitude],
        zoom: getZoomForRadius(radiusKm),
        minZoom: 7,
        maxBounds: mapBounds,
        maxBoundsViscosity: 0.75,
        scrollWheelZoom: false,
        zoomControl: true,
      })

      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap contributors',
        maxZoom: 18,
      }).addTo(mapInstance)

      const pinIcon = L.divIcon({
        className: 'wp03-leaflet-pin-wrapper',
        html: '<span style="display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:999px;background:rgba(15,118,110,0.22);border:2px solid #0f766e;"><span style="width:10px;height:10px;border-radius:999px;background:#0f766e;border:2px solid #ffffff;"></span></span>',
        iconSize: [24, 24],
        iconAnchor: [12, 12],
      })

      const circle = L.circle([latitude, longitude], {
        radius: Math.max(1, radiusKm) * 1000,
        color: '#0f766e',
        weight: 2,
        opacity: 0.85,
        fillColor: '#0f766e',
        fillOpacity: 0.16,
      }).addTo(mapInstance)

      const marker = L.marker([latitude, longitude], { icon: pinIcon }).addTo(mapInstance)

      mapInstance.on('click', (event) => {
        if (!event?.latlng || typeof callbackRef.current !== 'function') return
        const clamped = clampSriLankaCoords(event.latlng.lat, event.latlng.lng)
        callbackRef.current(clamped)
      })

      mapRef.current = mapInstance
      markerRef.current = marker
      circleRef.current = circle
    } catch {
      // Graceful fallback for non-layout test environments (e.g. JSDOM)
    }

    return () => {
      if (mapRef.current) {
        try {
          mapRef.current.remove()
        } catch {
          // Ignore teardown errors
        }
        mapRef.current = null
        markerRef.current = null
        circleRef.current = null
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const marker = markerRef.current
    const circle = circleRef.current
    if (!map || !marker || !circle) return

    try {
      const nextLatLng = [latitude, longitude]
      marker.setLatLng(nextLatLng)
      circle.setLatLng(nextLatLng)
      circle.setRadius(Math.max(1, Number(radiusKm || 1)) * 1000)

      if (radiusKm > 0 && typeof circle.getBounds === 'function') {
        const bounds = circle.getBounds()
        if (bounds?.isValid?.()) {
          map.fitBounds(bounds, { padding: [26, 26], maxZoom: 13 })
        } else {
          map.setView(nextLatLng, getZoomForRadius(radiusKm))
        }
      } else {
        map.setView(nextLatLng, 13)
      }
    } catch {
      // Ignore viewport update errors in headless DOM
    }
  }, [latitude, longitude, radiusKm])

  const estHouseholds = Math.max(12, Math.round(Number(radiusKm || 0) * 9.5))

  return (
    <div
      className={styles.leafletMapFrame}
      data-testid="leaflet-coverage-map"
      data-lat={latitude}
      data-lng={longitude}
      data-radius-km={radiusKm}
    >
      <div
        ref={containerRef}
        className={styles.leafletMapCanvas}
        aria-label={`Leaflet coverage map centered on ${districtLabel} with ${radiusKm} km radius`}
      />

      <div className={styles.leafletTopChip}>
        <span>📍 {districtLabel}</span>
        <span>·</span>
        <span>
          {Number(latitude).toFixed(4)}° N, {Number(longitude).toFixed(4)}° E
        </span>
      </div>

      <div className={styles.leafletBottomOverlay}>
        <span className={styles.coverageStatPill}>
          ⚡ Avg. Emergency Response: <strong>32 mins</strong>
        </span>
        <span className={styles.coverageStatPill}>
          👥 Est. Reach: <strong>{estHouseholds}k households</strong>
        </span>
      </div>
    </div>
  )
}
