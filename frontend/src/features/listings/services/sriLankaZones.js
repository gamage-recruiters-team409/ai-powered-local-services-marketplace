export const MAX_SERVICE_AREAS = 20

/**
 * Canonical Sri Lanka coordinate bounding box matching backend SriLankaDistrictGeoService.
 */
export const SRI_LANKA_BOUNDS = Object.freeze({
  minLat: 5.85,
  maxLat: 9.9,
  minLng: 79.5,
  maxLng: 81.95,
})

/**
 * Curated local geo-catalog of Sri Lankan cities and suburbs across all 25 districts.
 * Used to compute Covered Cities & Zones reactively in < 1ms without external OSM API calls.
 */
export const SRI_LANKA_ZONES = Object.freeze([
  // Colombo District
  { name: 'Colombo 01 (Fort)', district: 'Colombo', latitude: 6.9344, longitude: 79.8428 },
  { name: 'Colombo 03 (Kollupitiya)', district: 'Colombo', latitude: 6.912, longitude: 79.851 },
  { name: 'Colombo 04 (Bambalapitiya)', district: 'Colombo', latitude: 6.8935, longitude: 79.8555 },
  { name: 'Colombo 05 (Havelock Town)', district: 'Colombo', latitude: 6.8857, longitude: 79.8732 },
  { name: 'Colombo 06 (Wellawatte)', district: 'Colombo', latitude: 6.8742, longitude: 79.8612 },
  { name: 'Colombo 07 (Cinnamon Gardens)', district: 'Colombo', latitude: 6.9114, longitude: 79.8647 },
  { name: 'Colombo 08 (Borella)', district: 'Colombo', latitude: 6.9147, longitude: 79.8778 },
  { name: 'Colombo 10 (Maradana)', district: 'Colombo', latitude: 6.9292, longitude: 79.8664 },
  { name: 'Rajagiriya', district: 'Colombo', latitude: 6.9086, longitude: 79.895 },
  { name: 'Nawala', district: 'Colombo', latitude: 6.8906, longitude: 79.8886 },
  { name: 'Nugegoda', district: 'Colombo', latitude: 6.8649, longitude: 79.8997 },
  { name: 'Dehiwala', district: 'Colombo', latitude: 6.8511, longitude: 79.8659 },
  { name: 'Mount Lavinia', district: 'Colombo', latitude: 6.839, longitude: 79.8654 },
  { name: 'Ratmalana', district: 'Colombo', latitude: 6.8197, longitude: 79.8803 },
  { name: 'Moratuwa', district: 'Colombo', latitude: 6.773, longitude: 79.8816 },
  { name: 'Battaramulla', district: 'Colombo', latitude: 6.9001, longitude: 79.9182 },
  { name: 'Sri Jayawardenepura Kotte', district: 'Colombo', latitude: 6.8887, longitude: 79.9187 },
  { name: 'Maharagama', district: 'Colombo', latitude: 6.848, longitude: 79.9265 },
  { name: 'Pannipitiya', district: 'Colombo', latitude: 6.8468, longitude: 79.9469 },
  { name: 'Kottawa', district: 'Colombo', latitude: 6.8414, longitude: 79.9652 },
  { name: 'Malabe', district: 'Colombo', latitude: 6.9061, longitude: 79.9609 },
  { name: 'Kaduwela', district: 'Colombo', latitude: 6.9357, longitude: 79.9843 },
  { name: 'Thalawathugoda', district: 'Colombo', latitude: 6.8738, longitude: 79.9351 },
  { name: 'Piliyandala', district: 'Colombo', latitude: 6.8018, longitude: 79.9227 },
  { name: 'Kesbewa', district: 'Colombo', latitude: 6.7955, longitude: 79.9389 },
  { name: 'Homagama', district: 'Colombo', latitude: 6.844, longitude: 80.0024 },
  { name: 'Kolonnawa', district: 'Colombo', latitude: 6.9317, longitude: 79.8895 },
  { name: 'Wellampitiya', district: 'Colombo', latitude: 6.9378, longitude: 79.8986 },
  { name: 'Padukka', district: 'Colombo', latitude: 6.8472, longitude: 80.0914 },
  { name: 'Avissawella', district: 'Colombo', latitude: 6.9543, longitude: 80.2046 },

  // Gampaha District
  { name: 'Wattala', district: 'Gampaha', latitude: 6.9892, longitude: 79.8919 },
  { name: 'Kelaniya', district: 'Gampaha', latitude: 6.9553, longitude: 79.922 },
  { name: 'Peliyagoda', district: 'Gampaha', latitude: 6.9606, longitude: 79.8834 },
  { name: 'Kiribathgoda', district: 'Gampaha', latitude: 6.9794, longitude: 79.9293 },
  { name: 'Kadawatha', district: 'Gampaha', latitude: 7.0017, longitude: 79.9519 },
  { name: 'Ragama', district: 'Gampaha', latitude: 7.0281, longitude: 79.9219 },
  { name: 'Kandana', district: 'Gampaha', latitude: 7.048, longitude: 79.8962 },
  { name: 'Ja-Ela', district: 'Gampaha', latitude: 7.0744, longitude: 79.8919 },
  { name: 'Seeduwa', district: 'Gampaha', latitude: 7.1292, longitude: 79.8837 },
  { name: 'Katunayake', district: 'Gampaha', latitude: 7.1699, longitude: 79.8884 },
  { name: 'Negombo', district: 'Gampaha', latitude: 7.2008, longitude: 79.8737 },
  { name: 'Gampaha', district: 'Gampaha', latitude: 7.0873, longitude: 80.0144 },
  { name: 'Ganemulla', district: 'Gampaha', latitude: 7.0644, longitude: 79.9597 },
  { name: 'Yakkala', district: 'Gampaha', latitude: 7.0886, longitude: 80.0361 },
  { name: 'Nittambuwa', district: 'Gampaha', latitude: 7.1436, longitude: 80.0956 },
  { name: 'Veyangoda', district: 'Gampaha', latitude: 7.1575, longitude: 80.0564 },
  { name: 'Minuwangoda', district: 'Gampaha', latitude: 7.1663, longitude: 79.9533 },
  { name: 'Biyagama', district: 'Gampaha', latitude: 6.9617, longitude: 79.9742 },
  { name: 'Delgoda', district: 'Gampaha', latitude: 6.9936, longitude: 80.0075 },
  { name: 'Mirigama', district: 'Gampaha', latitude: 7.245, longitude: 80.1286 },

  // Kalutara District
  { name: 'Panadura', district: 'Kalutara', latitude: 6.7132, longitude: 79.9026 },
  { name: 'Bandaragama', district: 'Kalutara', latitude: 6.7145, longitude: 79.9889 },
  { name: 'Horana', district: 'Kalutara', latitude: 6.7159, longitude: 80.0626 },
  { name: 'Wadduwa', district: 'Kalutara', latitude: 6.6634, longitude: 79.9289 },
  { name: 'Kalutara', district: 'Kalutara', latitude: 6.5854, longitude: 79.9607 },
  { name: 'Beruwala', district: 'Kalutara', latitude: 6.4788, longitude: 79.9828 },
  { name: 'Aluthgama', district: 'Kalutara', latitude: 6.4342, longitude: 79.9989 },
  { name: 'Mathugama', district: 'Kalutara', latitude: 6.5219, longitude: 80.1144 },

  // Kandy District
  { name: 'Kandy', district: 'Kandy', latitude: 7.2906, longitude: 80.6337 },
  { name: 'Peradeniya', district: 'Kandy', latitude: 7.2591, longitude: 80.5985 },
  { name: 'Katugastota', district: 'Kandy', latitude: 7.3292, longitude: 80.6258 },
  { name: 'Kundasale', district: 'Kandy', latitude: 7.2833, longitude: 80.6833 },
  { name: 'Digana', district: 'Kandy', latitude: 7.2975, longitude: 80.7347 },
  { name: 'Pilimathalawa', district: 'Kandy', latitude: 7.2692, longitude: 80.5625 },
  { name: 'Kadugannawa', district: 'Kandy', latitude: 7.2547, longitude: 80.5242 },
  { name: 'Gampola', district: 'Kandy', latitude: 7.1643, longitude: 80.5696 },
  { name: 'Nawalapitiya', district: 'Kandy', latitude: 7.0531, longitude: 80.5333 },
  { name: 'Akurana', district: 'Kandy', latitude: 7.3653, longitude: 80.6175 },
  { name: 'Wattegama', district: 'Kandy', latitude: 7.3506, longitude: 80.6808 },
  { name: 'Teldeniya', district: 'Kandy', latitude: 7.3025, longitude: 80.7689 },

  // Matale District
  { name: 'Matale', district: 'Matale', latitude: 7.4675, longitude: 80.6234 },
  { name: 'Dambulla', district: 'Matale', latitude: 7.8742, longitude: 80.6511 },
  { name: 'Ukuwela', district: 'Matale', latitude: 7.42, longitude: 80.6261 },
  { name: 'Galewela', district: 'Matale', latitude: 7.7622, longitude: 80.5681 },
  { name: 'Sigiriya', district: 'Matale', latitude: 7.957, longitude: 80.7603 },

  // Nuwara Eliya District
  { name: 'Nuwara Eliya', district: 'Nuwara Eliya', latitude: 6.9497, longitude: 80.7891 },
  { name: 'Hatton', district: 'Nuwara Eliya', latitude: 6.8916, longitude: 80.5955 },
  { name: 'Talawakele', district: 'Nuwara Eliya', latitude: 6.9372, longitude: 80.6581 },
  { name: 'Ragala', district: 'Nuwara Eliya', latitude: 7.0075, longitude: 80.8469 },
  { name: 'Ginigathena', district: 'Nuwara Eliya', latitude: 6.9886, longitude: 80.4919 },

  // Galle District
  { name: 'Galle', district: 'Galle', latitude: 6.0535, longitude: 80.221 },
  { name: 'Unawatuna', district: 'Galle', latitude: 6.0174, longitude: 80.2489 },
  { name: 'Hikkaduwa', district: 'Galle', latitude: 6.1395, longitude: 80.1063 },
  { name: 'Ambalangoda', district: 'Galle', latitude: 6.2355, longitude: 80.0538 },
  { name: 'Karapitiya', district: 'Galle', latitude: 6.0681, longitude: 80.2256 },
  { name: 'Baddegama', district: 'Galle', latitude: 6.1731, longitude: 80.1931 },
  { name: 'Ahangama', district: 'Galle', latitude: 5.9739, longitude: 80.3656 },
  { name: 'Koggala', district: 'Galle', latitude: 5.9917, longitude: 80.3261 },
  { name: 'Elpitiya', district: 'Galle', latitude: 6.2922, longitude: 80.1611 },

  // Matara District
  { name: 'Matara', district: 'Matara', latitude: 5.9549, longitude: 80.555 },
  { name: 'Weligama', district: 'Matara', latitude: 5.975, longitude: 80.4297 },
  { name: 'Mirissa', district: 'Matara', latitude: 5.9483, longitude: 80.4716 },
  { name: 'Dikwella', district: 'Matara', latitude: 5.9667, longitude: 80.6928 },
  { name: 'Akuressa', district: 'Matara', latitude: 6.0983, longitude: 80.4767 },
  { name: 'Kamburupitiya', district: 'Matara', latitude: 6.0772, longitude: 80.5683 },
  { name: 'Hakmana', district: 'Matara', latitude: 6.0825, longitude: 80.6461 },

  // Hambantota District
  { name: 'Hambantota', district: 'Hambantota', latitude: 6.1241, longitude: 81.1185 },
  { name: 'Tangalle', district: 'Hambantota', latitude: 6.0244, longitude: 80.7941 },
  { name: 'Tissamaharama', district: 'Hambantota', latitude: 6.28, longitude: 81.2878 },
  { name: 'Ambalantota', district: 'Hambantota', latitude: 6.1233, longitude: 81.0236 },
  { name: 'Beliatta', district: 'Hambantota', latitude: 6.0389, longitude: 80.7422 },

  // Northern Province
  { name: 'Jaffna', district: 'Jaffna', latitude: 9.6615, longitude: 80.0255 },
  { name: 'Nallur', district: 'Jaffna', latitude: 9.6744, longitude: 80.0294 },
  { name: 'Chavakachcheri', district: 'Jaffna', latitude: 9.6631, longitude: 80.1656 },
  { name: 'Point Pedro', district: 'Jaffna', latitude: 9.8167, longitude: 80.2333 },
  { name: 'Chunnakam', district: 'Jaffna', latitude: 9.7408, longitude: 80.0322 },
  { name: 'Kopay', district: 'Jaffna', latitude: 9.6967, longitude: 80.0642 },
  { name: 'Manipay', district: 'Jaffna', latitude: 9.7267, longitude: 80.0022 },
  { name: 'Kilinochchi', district: 'Kilinochchi', latitude: 9.3803, longitude: 80.377 },
  { name: 'Paranthan', district: 'Kilinochchi', latitude: 9.4333, longitude: 80.4 },
  { name: 'Mannar', district: 'Mannar', latitude: 8.981, longitude: 79.9044 },
  { name: 'Talaimannar', district: 'Mannar', latitude: 9.1067, longitude: 79.7267 },
  { name: 'Vavuniya', district: 'Vavuniya', latitude: 8.7514, longitude: 80.4971 },
  { name: 'Cheddikulam', district: 'Vavuniya', latitude: 8.6631, longitude: 80.3111 },
  { name: 'Mullaitivu', district: 'Mullaitivu', latitude: 9.2671, longitude: 80.8142 },
  { name: 'Puthukkudiyiruppu', district: 'Mullaitivu', latitude: 9.3167, longitude: 80.7 },

  // Eastern Province
  { name: 'Batticaloa', district: 'Batticaloa', latitude: 7.731, longitude: 81.6747 },
  { name: 'Kattankudy', district: 'Batticaloa', latitude: 7.6844, longitude: 81.7317 },
  { name: 'Eravur', district: 'Batticaloa', latitude: 7.7753, longitude: 81.605 },
  { name: 'Valaichchenai', district: 'Batticaloa', latitude: 7.9219, longitude: 81.5319 },
  { name: 'Ampara', district: 'Ampara', latitude: 7.2975, longitude: 81.682 },
  { name: 'Kalmunai', district: 'Ampara', latitude: 7.4167, longitude: 81.8167 },
  { name: 'Akkaraipattu', district: 'Ampara', latitude: 7.2167, longitude: 81.85 },
  { name: 'Sammanthurai', district: 'Ampara', latitude: 7.3667, longitude: 81.8 },
  { name: 'Trincomalee', district: 'Trincomalee', latitude: 8.5874, longitude: 81.2152 },
  { name: 'Kinniya', district: 'Trincomalee', latitude: 8.4981, longitude: 81.1833 },
  { name: 'Kantale', district: 'Trincomalee', latitude: 8.3667, longitude: 81.0 },
  { name: 'Nilaveli', district: 'Trincomalee', latitude: 8.6989, longitude: 81.1894 },

  // North Western Province
  { name: 'Kurunegala', district: 'Kurunegala', latitude: 7.4863, longitude: 80.3623 },
  { name: 'Kuliyapitiya', district: 'Kurunegala', latitude: 7.4688, longitude: 80.0401 },
  { name: 'Narammala', district: 'Kurunegala', latitude: 7.4342, longitude: 80.2144 },
  { name: 'Mawathagama', district: 'Kurunegala', latitude: 7.4317, longitude: 80.4481 },
  { name: 'Polgahawela', district: 'Kurunegala', latitude: 7.3347, longitude: 80.2994 },
  { name: 'Pannala', district: 'Kurunegala', latitude: 7.3319, longitude: 80.0231 },
  { name: 'Wariyapola', district: 'Kurunegala', latitude: 7.6267, longitude: 80.2442 },
  { name: 'Ibbagamuwa', district: 'Kurunegala', latitude: 7.5614, longitude: 80.4539 },
  { name: 'Puttalam', district: 'Puttalam', latitude: 8.0362, longitude: 79.8283 },
  { name: 'Chilaw', district: 'Puttalam', latitude: 7.5758, longitude: 79.7953 },
  { name: 'Wennappuwa', district: 'Puttalam', latitude: 7.35, longitude: 79.8442 },
  { name: 'Marawila', district: 'Puttalam', latitude: 7.4178, longitude: 79.8294 },
  { name: 'Dankotuwa', district: 'Puttalam', latitude: 7.2947, longitude: 79.8831 },

  // North Central Province
  { name: 'Anuradhapura', district: 'Anuradhapura', latitude: 8.3114, longitude: 80.4037 },
  { name: 'Mihintale', district: 'Anuradhapura', latitude: 8.3592, longitude: 80.5106 },
  { name: 'Kekirawa', district: 'Anuradhapura', latitude: 8.0406, longitude: 80.5972 },
  { name: 'Thambuttegama', district: 'Anuradhapura', latitude: 8.1511, longitude: 80.295 },
  { name: 'Medawachchiya', district: 'Anuradhapura', latitude: 8.5403, longitude: 80.4936 },
  { name: 'Polonnaruwa', district: 'Polonnaruwa', latitude: 7.9403, longitude: 81.0188 },
  { name: 'Kaduruwela', district: 'Polonnaruwa', latitude: 7.9344, longitude: 81.0319 },
  { name: 'Hingurakgoda', district: 'Polonnaruwa', latitude: 8.0422, longitude: 80.9553 },
  { name: 'Medirigiriya', district: 'Polonnaruwa', latitude: 8.1489, longitude: 80.9617 },

  // Uva Province
  { name: 'Badulla', district: 'Badulla', latitude: 6.9934, longitude: 81.055 },
  { name: 'Bandarawela', district: 'Badulla', latitude: 6.8329, longitude: 80.9855 },
  { name: 'Ella', district: 'Badulla', latitude: 6.8667, longitude: 81.0466 },
  { name: 'Haputale', district: 'Badulla', latitude: 6.7689, longitude: 80.9597 },
  { name: 'Welimada', district: 'Badulla', latitude: 6.9014, longitude: 80.9094 },
  { name: 'Mahiyanganaya', district: 'Badulla', latitude: 7.3333, longitude: 81.0 },
  { name: 'Monaragala', district: 'Monaragala', latitude: 6.8728, longitude: 81.3507 },
  { name: 'Wellawaya', district: 'Monaragala', latitude: 6.7372, longitude: 81.1028 },
  { name: 'Bibile', district: 'Monaragala', latitude: 7.1636, longitude: 81.2244 },
  { name: 'Buttala', district: 'Monaragala', latitude: 6.7528, longitude: 81.24 },

  // Sabaragamuwa Province
  { name: 'Ratnapura', district: 'Ratnapura', latitude: 6.6828, longitude: 80.3992 },
  { name: 'Pelmadulla', district: 'Ratnapura', latitude: 6.6203, longitude: 80.5356 },
  { name: 'Balangoda', district: 'Ratnapura', latitude: 6.6472, longitude: 80.6989 },
  { name: 'Eheliyagoda', district: 'Ratnapura', latitude: 6.8467, longitude: 80.2647 },
  { name: 'Embilipitiya', district: 'Ratnapura', latitude: 6.3422, longitude: 80.8489 },
  { name: 'Kuruwita', district: 'Ratnapura', latitude: 6.7747, longitude: 80.3694 },
  { name: 'Kegalle', district: 'Kegalle', latitude: 7.2513, longitude: 80.3464 },
  { name: 'Mawanella', district: 'Kegalle', latitude: 7.2533, longitude: 80.4458 },
  { name: 'Warakapola', district: 'Kegalle', latitude: 7.2275, longitude: 80.1972 },
  { name: 'Rambukkana', district: 'Kegalle', latitude: 7.3264, longitude: 80.3919 },
  { name: 'Ruwanwella', district: 'Kegalle', latitude: 7.0439, longitude: 80.2528 },
])

/**
 * Great-circle distance in kilometers between two WGS84 points using Earth radius 6371 km.
 */
export function computeHaversineKm(lat1, lng1, lat2, lng2) {
  const toRad = (deg) => (deg * Math.PI) / 180
  const dLat = toRad(lat2 - lat1)
  const dLng = toRad(lng2 - lng1)
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

function deduplicateZones(zones, maxZones = MAX_SERVICE_AREAS) {
  const seen = new Set()
  const result = []
  for (const raw of zones) {
    const cleaned = String(raw || '').trim()
    if (!cleaned) continue
    const key = cleaned.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    result.push(cleaned)
    if (result.length >= maxZones) break
  }
  return result
}

/**
 * Returns nearest Sri Lankan cities/suburbs within `radiusKm` of `(latitude, longitude)`,
 * sorted nearest-first and capped to backend `@Size(max = 20)` limit.
 */
export function getZonesWithinRadius(
  latitude,
  longitude,
  radiusKm,
  { maxZones = MAX_SERVICE_AREAS, fallbackLabel = '' } = {},
) {
  const cap = Math.max(1, Number(maxZones) || MAX_SERVICE_AREAS)
  const numericRadius = Number(radiusKm)
  const trimmedFallback = String(fallbackLabel || '').trim()

  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    return trimmedFallback ? [trimmedFallback] : [SRI_LANKA_ZONES[0].name]
  }

  const sorted = SRI_LANKA_ZONES.map((zone) => ({
    name: zone.name,
    distanceKm: computeHaversineKm(latitude, longitude, zone.latitude, zone.longitude),
  })).sort((a, b) => a.distanceKm - b.distanceKm)

  if (!Number.isFinite(numericRadius) || numericRadius <= 0) {
    return trimmedFallback ? [trimmedFallback] : [sorted[0].name]
  }

  const matchedNames = sorted
    .filter((item) => item.distanceKm <= numericRadius)
    .map((item) => item.name)

  if (matchedNames.length === 0) {
    return [sorted[0].name]
  }

  return deduplicateZones(matchedNames, cap)
}

/**
 * Merges auto-matched radius zones with manually added zones while honoring
 * provider-excluded auto zones and preserving manual entries inside the max-20 cap.
 */
export function mergeRadiusZones({
  autoZones = [],
  manualZones = [],
  excludedAutoZones = [],
  maxZones = MAX_SERVICE_AREAS,
} = {}) {
  const cap = Math.max(1, Number(maxZones) || MAX_SERVICE_AREAS)
  const cleanManual = deduplicateZones(manualZones, cap)
  const manualSet = new Set(cleanManual.map((z) => z.toLowerCase()))
  const excludedSet = new Set(
    (excludedAutoZones || []).map((z) => String(z || '').trim().toLowerCase()).filter(Boolean),
  )

  const eligibleAuto = deduplicateZones(autoZones, autoZones.length || cap).filter((zone) => {
    const key = zone.toLowerCase()
    return !manualSet.has(key) && !excludedSet.has(key)
  })

  const autoCapacity = Math.max(0, cap - cleanManual.length)
  const includedAuto = eligibleAuto.slice(0, autoCapacity)

  return {
    serviceAreas: [...includedAuto, ...cleanManual],
    includedAutoCount: includedAuto.length,
    manualCount: cleanManual.length,
    truncatedCount: Math.max(0, eligibleAuto.length - includedAuto.length),
  }
}

/**
 * Checks whether coordinates lie within Sri Lanka bounds (prevents desktop VPN/IP drift outside LK).
 */
export function isWithinSriLankaBounds(latitude, longitude) {
  return (
    Number.isFinite(latitude) &&
    Number.isFinite(longitude) &&
    latitude >= SRI_LANKA_BOUNDS.minLat &&
    latitude <= SRI_LANKA_BOUNDS.maxLat &&
    longitude >= SRI_LANKA_BOUNDS.minLng &&
    longitude <= SRI_LANKA_BOUNDS.maxLng
  )
}

/**
 * Returns the nearest Sri Lankan town/suburb entry with its Haversine distance in km.
 */
export function findNearestSriLankaZone(latitude, longitude) {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    return null
  }
  let bestZone = null
  let bestDistance = Number.POSITIVE_INFINITY
  for (const zone of SRI_LANKA_ZONES) {
    const distanceKm = computeHaversineKm(latitude, longitude, zone.latitude, zone.longitude)
    if (distanceKm < bestDistance) {
      bestDistance = distanceKm
      bestZone = { ...zone, distanceKm }
    }
  }
  return bestZone
}
