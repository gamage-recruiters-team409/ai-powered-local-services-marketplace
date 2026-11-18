export const PROFILE_TABS = ['overview', 'services', 'reviews', 'availability'];

const PROFILE_TAB_ALIASES = {
  packages: 'services',
  slots: 'availability',
};

const SERVICE_TYPE_DETAILS = {
  standard: {
    label: 'Standard home clean',
    description: 'Kitchen, bathrooms, floors and surface dusting.',
  },
  deep: {
    label: 'Deep clean',
    description: 'A detailed reset for baseboards, fixtures and hard-to-reach areas.',
  },
  moveout: {
    label: 'Move-out clean',
    description: 'Empty-home cleaning with cabinet interiors and appliance surfaces.',
  },
  recurring: {
    label: 'Recurring clean',
    description: 'Regular cleans with a preferred time slot and consistent care.',
  },
};

function toTitleCase(value = '') {
  return value
    .replace(/[-_]+/g, ' ')
    .replace(/\b\w/g, (character) => character.toUpperCase())
    .trim();
}

export function normalizeProfileTab(value) {
  if (!value) {
    return 'overview';
  }

  const normalized = String(value).trim().toLowerCase();
  const tab = PROFILE_TAB_ALIASES[normalized] ?? normalized;

  return PROFILE_TABS.includes(tab) ? tab : 'overview';
}

export function getProviderServiceOptions(provider = {}) {
  const serviceTypes = Array.isArray(provider.serviceTypes)
    ? provider.serviceTypes.filter((type) => typeof type === 'string' && type.trim())
    : [];

  if (serviceTypes.length === 0) {
    const fallbackLabel = provider.category?.trim() || 'Home service';

    return [
      {
        key: 'category',
        label: fallbackLabel,
        description:
          provider.description?.trim() || 'Service details are not provided.',
      },
    ];
  }

  const seen = new Set();

  return serviceTypes.reduce((services, type) => {
    const key = type.trim().toLowerCase();

    if (seen.has(key)) {
      return services;
    }

    seen.add(key);

    const details = SERVICE_TYPE_DETAILS[key];

    services.push({
      key,
      label: details?.label ?? toTitleCase(key),
      description:
        details?.description ||
        provider.description?.trim() ||
        'Service details are not provided.',
    });

    return services;
  }, []);
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
        'Individual review content is not available in this demo. The aggregate rating is shown above.';
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
