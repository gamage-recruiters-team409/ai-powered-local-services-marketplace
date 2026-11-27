package com.gamagerecruiters.marketplace.modules.customer.service;

/**
 * Unchecked exception thrown when a profile image cleanup task cannot be durably scheduled in MongoDB.
 * Ensures the photo workflow fails safely without proceeding in an untracked state.
 */
public class ProfileImageCleanupScheduleException extends RuntimeException {

    public ProfileImageCleanupScheduleException(String message) {
        super(message);
    }

    public ProfileImageCleanupScheduleException(String message, Throwable cause) {
        super(message, cause);
    }
}
