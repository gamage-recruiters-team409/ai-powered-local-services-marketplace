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

export function getProviderReviewState(provider = {}) {
  const reviews = Array.isArray(provider.reviews)
    ? provider.reviews.filter((review) => review && typeof review === 'object')
    : [];
  const reviewCount =
    typeof provider.reviewCount === 'number' ? provider.reviewCount : null;
  const rating = typeof provider.rating === 'number' ? provider.rating : null;

  let message = null;

  if (reviews.length === 0) {
    if (reviewCount === null) {
      message = 'Review details are not provided for this provider.';
    } else if (reviewCount === 0) {
      message = 'This provider has no reviews yet.';
    } else {
      message =
        'Individual review content is not available. The aggregate rating is shown above.';
    }
  }

  return {
    rating,
    reviewCount,
    reviews,
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
