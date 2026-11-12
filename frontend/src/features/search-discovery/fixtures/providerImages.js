/**
 * Cloudinary-hosted provider images for the WP04 search fixtures.
 *
 * Provider photographs are intentionally NOT stored in this repository. They are
 * uploaded to Cloudinary and referenced here by URL so the repository stays free
 * of binary image assets.
 *
 * To replace a photo, upload it to Cloudinary under `localserve/providers/` with
 * the matching public ID. Only the public cloud name is referenced here; API
 * keys/secrets must never be committed.
 */
const CLOUD_NAME = 'iockctkk';
const PROVIDER_FOLDER = 'localserve/providers';
// f_auto/q_auto serve optimised formats; c_fill keeps the square card crop.
const TRANSFORMATION = 'f_auto,q_auto,w_400,h_400,c_fill';

function cloudinaryUrl(publicId) {
  return `https://res.cloudinary.com/${CLOUD_NAME}/image/upload/${TRANSFORMATION}/${PROVIDER_FOLDER}/${publicId}.jpg`;
}

export const PROVIDER_IMAGES = {
  maya: cloudinaryUrl('maya-home-care'),
  brightnest: cloudinaryUrl('brightnest-cleaning'),
  neatLocal: cloudinaryUrl('neat-and-local'),
  capitalCity: cloudinaryUrl('capital-city-cleaners'),
};

/**
 * Inline SVG placeholder used when a provider image fails to load, so cards
 * always render a graceful fallback instead of a broken-image icon.
 */
export const FALLBACK_PROVIDER_IMAGE = `data:image/svg+xml;utf8,${encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400">' +
    '<rect width="400" height="400" fill="#e5e7eb"/>' +
    '<g fill="none" stroke="#9ca3af" stroke-width="14" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M120 210 200 145 280 210"/>' +
    '<path d="M145 200v70h110v-70"/>' +
    '</g>' +
    '<text x="200" y="325" text-anchor="middle" font-family="sans-serif" font-size="20" fill="#6b7280">Image unavailable</text>' +
    '</svg>',
)}`;
