import apiClient from '../../../shared/services/apiClient';

/**
 * Retrieves paginated favourites for the customer by type.
 *
 * @param {'PROVIDER' | 'LISTING'} type
 * @param {number} page
 * @param {number} size
 * @returns {Promise<{content: Array, page: number, size: number, totalElements: number, totalPages: number}>}
 */
export async function getFavourites(type, page = 0, size = 6) {
  const response = await apiClient.get(`/customers/me/favorites?type=${type}&page=${page}&size=${size}`);
  return response.data;
}

/**
 * Idempotently adds a target to favourites.
 *
 * @param {'PROVIDER' | 'LISTING'} type
 * @param {string} targetId
 */
export async function addFavourite(type, targetId) {
  await apiClient.put(`/customers/me/favorites/${type}/${targetId}`);
}

/**
 * Idempotently removes a target from favourites.
 *
 * @param {'PROVIDER' | 'LISTING'} type
 * @param {string} targetId
 */
export async function removeFavourite(type, targetId) {
  await apiClient.delete(`/customers/me/favorites/${type}/${targetId}`);
}
