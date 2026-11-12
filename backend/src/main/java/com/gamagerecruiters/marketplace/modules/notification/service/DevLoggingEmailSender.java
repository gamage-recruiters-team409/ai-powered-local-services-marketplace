package com.gamagerecruiters.marketplace.modules.notification.service;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Default development email implementation.
 *
 * This implementation sends nothing and logs only a masked recipient. It is
 * selected when {@code app.email.provider=dev} or when the property is missing.
 */
@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "dev", matchIfMissing = true)
public class DevLoggingEmailSender implements EmailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(DevLoggingEmailSender.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    @Override
    public EmailResult sendEmail(EmailType type, String recipientEmail, Map<String, Object> data) {
        try {
            String validationError = validate(type, recipientEmail, data);
            if (validationError != null) {
                return EmailResult.failure(validationError);
            }

            LOGGER.info("email type={}, recipient={}, dev mode, not sent", type, maskRecipient(recipientEmail));
            return EmailResult.success("dev-" + UUID.randomUUID());
        } catch (RuntimeException exception) {
            return EmailResult.failure("Email could not be processed");
        }
    }

    private String validate(EmailType type, String recipientEmail, Map<String, Object> data) {
        if (type == null) {
            return "Email type is required";
        }
        if (recipientEmail == null || recipientEmail.isBlank()) {
            return "Recipient email is required";
        }
        if (!EMAIL_PATTERN.matcher(recipientEmail).matches()) {
            return "Recipient email is invalid";
        }
        if (data == null) {
            return "Email data is required";
        }

        String[] requiredKeys = type == EmailType.ACCOUNT_VERIFICATION
                ? new String[]{"name", "verificationLink", "expiresInMinutes"}
                : new String[]{"name", "resetLink", "expiresInMinutes"};

        for (String key : requiredKeys) {
            if (!data.containsKey(key)) {
                return "Missing required data: " + key;
            }
            Object value = data.get(key);
            if (value == null || (value instanceof String stringValue && stringValue.isBlank())) {
                return "Required data is blank: " + key;
            }
            if ("expiresInMinutes".equals(key) && !isNumeric(value)) {
                return "expiresInMinutes must be numeric";
            }
        }
        return null;
    }

    private boolean isNumeric(Object value) {
        if (value instanceof Number number) {
            return Double.isFinite(number.doubleValue());
        }
        if (value instanceof String stringValue) {
            try {
                return Double.isFinite(Double.parseDouble(stringValue.trim()));
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return false;
    }

    private String maskRecipient(String recipientEmail) {
        int atIndex = recipientEmail.indexOf('@');
        if (atIndex == 1) {
            return "***" + recipientEmail.substring(atIndex);
        }
        return recipientEmail.charAt(0) + "***" + recipientEmail.substring(atIndex);
    }
}
