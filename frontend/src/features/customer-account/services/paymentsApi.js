import apiClient from '../../../shared/services/apiClient';

/**
 * Retrieves paginated payments for the authenticated customer.
 *
 * @param {number} page zero-based page number
 * @param {number} size page size (default 10)
 * @returns {Promise<{content: Array, page: number, size: number, totalElements: number, totalPages: number}>}
 */
export async function getMyPayments(page = 0, size = 10) {
  const response = await apiClient.get(`/payments/me?page=${page}&size=${size}`);
  return response.data;
}
