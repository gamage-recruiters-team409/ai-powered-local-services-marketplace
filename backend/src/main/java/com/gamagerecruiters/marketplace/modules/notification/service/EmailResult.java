package com.gamagerecruiters.marketplace.modules.notification.service;

/**
 * Describes whether an email request was processed successfully.
 *
 * @param success whether processing succeeded
 * @param messageId provider or implementation message identifier on success
 * @param errorMessage safe failure description on failure
 */
public record EmailResult(boolean success, String messageId, String errorMessage) {

    /**
     * Creates a successful result.
     *
     * @param messageId the generated or provider message identifier
     * @return a successful email result
     */
    public static EmailResult success(String messageId) {
        return new EmailResult(true, messageId, null);
    }

    /**
     * Creates a failed result.
     *
     * @param errorMessage a safe error description
     * @return a failed email result
     */
    public static EmailResult failure(String errorMessage) {
        return new EmailResult(false, null, errorMessage);
    }
}
