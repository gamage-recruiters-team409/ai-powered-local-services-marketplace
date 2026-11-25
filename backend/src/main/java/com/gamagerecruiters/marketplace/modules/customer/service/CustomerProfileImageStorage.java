package com.gamagerecruiters.marketplace.modules.customer.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Storage port for customer profile photos.
 */
public interface CustomerProfileImageStorage {

    /**
     * Uploads a customer profile image to remote storage.
     *
     * @param customerId ID of customer uploading photo
     * @param file       multipart image file (JPEG or PNG, max 5 MB)
     * @return stored image reference with publicId and secureUrl
     */
    StoredProfileImage upload(String customerId, MultipartFile file);

    /**
     * Deletes a customer profile image from remote storage.
     *
     * @param publicId Cloudinary public_id of the image to delete
     * @return true if deletion succeeded or image was already absent, false otherwise
     */
    boolean delete(String publicId);
}
