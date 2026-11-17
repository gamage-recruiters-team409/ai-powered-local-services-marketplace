/**
 * Presentation helpers for the WP04 provider comparison view.
 *
 * Every helper accepts the provider shape used by the search fixtures/live
 * adapter and degrades to "Not provided" instead of inventing data when an
 * optional comparison field is missing.
 */

export const NOT_PROVIDED = 'Not provided';
export const LOCATION_NOT_PROVIDED = 'Location not provided';

export function formatPrice(pricing) {
  if (!pricing || typeof pricing.hourlyRate !== 'number') {
    return NOT_PROVIDED;
  }

  const currency =
    typeof pricing.currency === 'string' ? pricing.currency.trim() : '';
  const unit = typeof pricing.unit === 'string' ? pricing.unit.trim() : '';

  // Never assume USD or hourly billing when the live payload omits either part.
  if (!currency || !unit) {
    return NOT_PROVIDED;
  }

  const base = `From ${currency}${pricing.hourlyRate}/${unit}`;

  if (typeof pricing.minimumHours === 'number') {
    return `${base} · ${pricing.minimumHours}-hour minimum`;
  }

  return base;
}

export function formatRating(rating) {
  return typeof rating === 'number' ? `${rating.toFixed(1)}/5` : NOT_PROVIDED;
}

export function formatReviewCount(reviewCount) {
  if (typeof reviewCount !== 'number') {
    return NOT_PROVIDED;
  }

  if (reviewCount === 0) {
    return 'No reviews yet';
  }

  return `${reviewCount} ${reviewCount === 1 ? 'review' : 'reviews'}`;
}

export function formatVerification({ isVerified, backgroundChecked } = {}) {
  if (isVerified && backgroundChecked) {
    return 'Identity + background checked';
  }

  if (isVerified) {
    return 'Identity verified';
  }

  if (isVerified === false || backgroundChecked === false) {
    return 'Not verified';
  }

  return NOT_PROVIDED;
}

export function formatResponseTime(responseTimeText) {
  if (!responseTimeText) {
    return NOT_PROVIDED;
  }

  return responseTimeText
    .replace(/^Replies within\s+/i, 'Within ')
    .replace(/\bmin\b/gi, 'minutes');
}

export function formatNextAvailable(nextAvailable) {
  if (!nextAvailable) {
    return NOT_PROVIDED;
  }

  return nextAvailable.replace(/,\s*/, ' · ');
}

export function getLocationCity(location) {
  if (!location) {
    return '';
  }

  return location.split(',')[0].trim();
}

export function formatProviderLocation(location) {
  return getLocationCity(location) || LOCATION_NOT_PROVIDED;
}

export function formatDistanceArea(provider = {}) {
  const distance = provider.distanceText?.replace(/\s+away$/i, '').trim();
  const city = getLocationCity(provider.location);
  const radius = provider.serviceRadiusMiles;

  if (!distance && !city && radius == null) {
    return NOT_PROVIDED;
  }

  const parts = [];

  if (distance) {
    parts.push(distance);
  }

  if (city && radius != null) {
    parts.push(`${city}, ${radius}-mile radius`);
  } else if (city) {
    parts.push(city);
  } else if (radius != null) {
    parts.push(`${radius}-mile radius`);
  }

  return parts.join(' · ') || NOT_PROVIDED;
}

export function formatInclusions(inclusions) {
  if (!Array.isArray(inclusions) || inclusions.length === 0) {
    return NOT_PROVIDED;
  }

  return inclusions.filter(Boolean).join(', ') || NOT_PROVIDED;
}

export function formatCancellationPolicy(cancellationPolicy) {
  return cancellationPolicy?.trim() ? cancellationPolicy.trim() : NOT_PROVIDED;
}

export function getProviderShortName(name = '') {
  const firstWord = name.trim().split(/\s+/)[0] || '';
  return firstWord.replace(/'s$/i, '');
}

export function getCommonCategory(providers = []) {
  if (providers.length === 0) {
    return 'Selected services';
  }

  const firstCategory = providers[0]?.category;

  if (!firstCategory) {
    return 'Selected services';
  }

  return providers.every((provider) => provider.category === firstCategory)
    ? firstCategory
    : 'Mixed services';
}

export function getDistanceChip(filters = {}) {
  const distance = Number(filters.distance);

  if (Number.isFinite(distance) && distance < 25) {
    return `Within ${distance} miles`;
  }

  return 'All distances';
}
