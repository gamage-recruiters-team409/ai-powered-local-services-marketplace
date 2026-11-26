// Pure provider-profile form helpers. Kept separate from the React page for Fast Refresh.

export function formFromProfile(profile) {
  return {
    providerType: profile?.providerType ?? 'INDIVIDUAL',
    displayName: profile?.displayName ?? '',
    bio: profile?.bio ?? '',
    district: profile?.district ?? '',
    city: profile?.city ?? '',
    experienceYears: profile?.experienceYears == null ? '' : String(profile.experienceYears),
    categoryIds: [...(profile?.categoryIds ?? [])],
    serviceAreaDistricts: [...(profile?.serviceAreaDistricts ?? [])],
  }
}


export function validateProfile(form, districts, onboarding, existingProfile = null) {
  const errors = {}
  if (!['INDIVIDUAL', 'BUSINESS'].includes(form.providerType)) errors.providerType = 'Select a provider type.'
  if (!form.displayName.trim()) errors.displayName = 'Enter your display name.'
  else if (form.displayName.trim().length > 100) errors.displayName = 'Use 100 characters or fewer.'
  if (form.bio.length > 1000) errors.bio = 'Use 1,000 characters or fewer.'
  if (onboarding && !form.bio.trim()) errors.bio = 'Add a short description of your services.'
  // Location is required when creating a profile. The Edit Profile dialog
  // does not expose location fields, so existing location data must not block edits.
  if (onboarding) {
    if (!districts.includes(form.district)) errors.district = 'Select a valid district.'
    if (!form.city.trim()) errors.city = 'Enter your city.'
    else if (form.city.trim().length > 100) errors.city = 'Use 100 characters or fewer.'
  }
  if (!onboarding && existingProfile?.experienceYears != null && form.experienceYears === '') {
    errors.experienceYears = 'An existing experience value cannot be cleared. Enter a whole number from 0 to 80.'
  }
  if (form.experienceYears !== '') {
    const years = Number(form.experienceYears)
    if (!Number.isInteger(years) || years < 0 || years > 80) {
      errors.experienceYears = 'Enter a whole number from 0 to 80.'
    }
  }
  if (onboarding && !form.categoryIds.length) errors.categoryIds = 'Select at least one service category.'
  if (onboarding && !form.serviceAreaDistricts.length) {
    errors.serviceAreaDistricts = 'Select at least one service-area district.'
  } else if (onboarding && form.serviceAreaDistricts.some((district) => !districts.includes(district))) {
    errors.serviceAreaDistricts = 'Select districts from the available list.'
  }
  return errors
}


/**
 * Edit Profile updates only fields shown in the edit dialog. Omitted fields
 * (district, city, categories, and service-area districts) remain unchanged
 * because the existing backend profile update endpoint supports partial updates.
 * Service categories and service-area districts are edited in Manage Services.
 */
// Existing provider classification is immutable; only onboarding may choose it.
export function toProfileEditPayload(form) {
  return {
    displayName: form.displayName.trim(),
    bio: form.bio.trim(),
    ...(form.experienceYears === '' ? {} : { experienceYears: Number(form.experienceYears) }),
  }
}
