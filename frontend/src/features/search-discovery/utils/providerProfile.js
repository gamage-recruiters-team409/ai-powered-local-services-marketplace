export const PROFILE_TABS = ['overview', 'services', 'reviews', 'availability'];

const PROFILE_TAB_ALIASES = {
  packages: 'services',
  slots: 'availability',
};

const SERVICE_TYPE_LABELS = {
  standard: 'Standard home clean',
  deep: 'Deep clean',
  moveout: 'Move-out clean',
  recurring: 'Recurring clean',
};

function getTrimmedString(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function toTitleCase(value = '') {
  return value
    .replace(/[-_]+/g, ' ')
    .replace(/\b\w/g, (character) => character.toUpperCase())
    .trim();
}

function normalizeServiceEntry(entry) {
  if (typeof entry === 'string') {
    const key = entry.trim().toLowerCase();

    if (!key) {
      return null;
    }

    return {
      key,
      label: SERVICE_TYPE_LABELS[key] ?? toTitleCase(key),
      description: null,
      pricing: null,
      inclusions: null,
      isFallback: false,
    };
  }

  if (!entry || typeof entry !== 'object') {
    return null;
  }

  const rawKey =
    entry.type ?? entry.key ?? entry.id ?? entry.slug ?? entry.name ?? entry.label;
  const key = String(rawKey ?? '').trim().toLowerCase();

  if (!key) {
    return null;
  }

  const label =
    getTrimmedString(entry.label) ||
    getTrimmedString(entry.name) ||
    SERVICE_TYPE_LABELS[key] ||
    toTitleCase(key);

  return {
    key,
    label: label || 'Service',
    description: getTrimmedString(entry.description) || null,
    pricing:
      entry.pricing && typeof entry.pricing === 'object' ? entry.pricing : null,
    inclusions: Array.isArray(entry.inclusions)
      ? entry.inclusions.filter(Boolean)
      : null,
    isFallback: false,
  };
}

export function getRatingStarStates(rating) {
  if (typeof rating !== 'number' || !Number.isFinite(rating)) {
    return [];
  }

  const normalized = Math.min(5, Math.max(0, rating));

  return Array.from({ length: 5 }, (_, index) => {
    const fillPercent = Math.round(
      Math.min(1, Math.max(0, normalized - index)) * 100,
    );

    let state = 'empty';

    if (fillPercent === 100) {
      state = 'full';
    } else if (fillPercent > 0) {
      state = 'partial';
    }

    return {
      state,
      fillPercent,
    };
  });
}

export function normalizeProfileTab(value) {
  if (!value) {
    return 'overview';
  }

  const normalized = String(value).trim().toLowerCase();
  const tab = PROFILE_TAB_ALIASES[normalized] ?? normalized;

  return PROFILE_TABS.includes(tab) ? tab : 'overview';
}

export function getProviderServiceOptions(provider = {}, { allowFallback = true } = {}) {
  const serviceTypes = Array.isArray(provider.serviceTypes)
    ? provider.serviceTypes
    : [];
  const seen = new Set();
  const services = [];

  serviceTypes.forEach((entry) => {
    const service = normalizeServiceEntry(entry);

    if (!service || seen.has(service.key)) {
      return;
    }

    seen.add(service.key);
    services.push(service);
  });

  if (services.length > 0) {
    return services;
  }

  if (!allowFallback) {
    return [];
  }

  return [
    {
      key: 'category',
      label: getTrimmedString(provider.category) || 'Home service',
      description: null,
      pricing: null,
      inclusions: null,
      isFallback: true,
    },
  ];
}

export function getProviderListingServiceOptions(provider = {}) {
  const listings = Array.isArray(provider.listings) ? provider.listings : [];
  const seen = new Set();
  const services = [];

  listings.forEach((listing) => {
    if (!listing || typeof listing !== 'object') {
      return;
    }

    const listingId = getTrimmedString(listing.id);
    if (!listingId || seen.has(listingId)) {
      return;
    }

    seen.add(listingId);
    services.push({
      key: listingId,
      listingId,
      label:
        getTrimmedString(listing.title) ||
        getTrimmedString(listing.categoryName) ||
        'Service',
      description: getTrimmedString(listing.description) || null,
      pricing:
        listing.pricing && typeof listing.pricing === 'object'
          ? listing.pricing
          : null,
      inclusions: null,
      isFallback: false,
    });
  });

  return services;
}

export function getProviderReviewState(provider = {}) {
  const reviews = Array.isArray(provider.reviews)
    ? provider.reviews.filter((review) => review && typeof review === 'object')
    : [];
  const reviewCount =
    typeof provider.reviewCount === 'number' ? provider.reviewCount : null;
  const rating = typeof provider.rating === 'number' ? provider.rating : null;
  const status = provider.reviewContentStatus === 'UNAVAILABLE'
    ? 'unavailable'
    : 'available';
  const page = typeof provider.reviewPage === 'number' ? provider.reviewPage : 0;
  const totalElements = typeof provider.reviewTotalElements === 'number'
    ? provider.reviewTotalElements
    : null;
  const totalPages = typeof provider.reviewTotalPages === 'number'
    ? provider.reviewTotalPages
    : null;
  const hasMore = totalPages !== null && page + 1 < totalPages;

  let message = null;

  if (status === 'unavailable') {
    message = 'Reviews are temporarily unavailable. The rest of this profile is still available.';
  } else if (reviews.length === 0) {
    if (totalElements === 0 || (totalElements === null && reviewCount === 0)) {
      message = 'This provider has no reviews yet.';
    } else if (page > 0 && totalPages !== null && page + 1 >= totalPages) {
      message = 'There are no more reviews on this page.';
    } else if (reviewCount === null && totalElements === null) {
      message = 'Review details are not provided for this provider.';
    } else {
      message =
        'Individual review content is not available. The aggregate rating is shown above.';
    }
  }

  return {
    rating,
    reviewCount,
    reviews,
    status,
    page,
    totalElements,
    totalPages,
    hasMore,
    hasReviewContent: reviews.length > 0,
    message,
  };
}

export function getProviderAvailabilityState(provider = {}) {
  const slots = Array.isArray(provider.availableSlots)
    ? provider.availableSlots.filter(Boolean)
    : [];
  const nextAvailable = provider.nextAvailable?.trim() || null;

  return {
    nextAvailable,
    slots,
    hasSlotPreview: slots.length > 0,
    message: slots.length
      ? null
      : 'Full slot preview is not available yet. Live availability will appear here once the booking module is connected.',
  };
}
