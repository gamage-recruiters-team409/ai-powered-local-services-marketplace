package com.gamagerecruiters.marketplace.modules.notification.service;

import java.util.Map;

/**
 * Sends notification emails without exposing provider-specific details.
 *
 * Implementations must never throw from {@link #sendEmail(EmailType, String, Map)}.
 * Send failures and invalid input must be returned as an {@link EmailResult} with
 * {@code success=false}, so an email failure cannot block the authentication flow.
 * Implementations must never log links, tokens, or full email addresses.
 */
public interface EmailSender {

    /**
     * Processes an email request and returns a result instead of throwing.
     *
     * {@code ACCOUNT_VERIFICATION} requires {@code name}, {@code verificationLink},
     * and {@code expiresInMinutes}. {@code PASSWORD_RESET} requires {@code name},
     * {@code resetLink}, and {@code expiresInMinutes}.
     *
     * @param type the type of email to process
     * @param recipientEmail the recipient email address
     * @param data the template data required for the selected email type
     * @return a success or failure result; never throws
     */
    EmailResult sendEmail(EmailType type, String recipientEmail, Map<String, Object> data);
}
