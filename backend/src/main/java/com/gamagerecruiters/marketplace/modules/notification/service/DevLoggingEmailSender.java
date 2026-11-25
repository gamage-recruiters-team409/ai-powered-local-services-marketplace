package com.gamagerecruiters.marketplace.modules.notification.service;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Default development email implementation.
 *
 * This implementation is available only in the approved development profiles,
 * sends nothing, and logs only a masked recipient. It is selected when
 * {@code app.email.provider=dev} or when the property is missing. In any other
 * environment, no {@link EmailSender} bean exists on purpose, so a missing
 * real provider fails at startup instead of faking success.
 */
@Service
@Profile({"local", "dev", "test", "integration-test"})
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

        String[] requiredKeys = switch (type) {
            case ACCOUNT_VERIFICATION -> new String[]{"name", "verificationLink", "expiresInMinutes"};
            case PASSWORD_RESET -> new String[]{"name", "resetLink", "expiresInMinutes"};
            case NOTIFICATION -> new String[]{"name", "title", "message"};
        };

        for (String key : requiredKeys) {
            if (!data.containsKey(key)) {
                return "Missing required data: " + key;
            }
            Object value = data.get(key);
            if ("expiresInMinutes".equals(key) && !isPositiveWholeNumber(value)) {
                return "expiresInMinutes must be a positive whole number";
            }
            if (value == null || (value instanceof String stringValue && stringValue.isBlank())) {
                return "Required data is blank: " + key;
            }
        }
        return null;
    }

    private boolean isPositiveWholeNumber(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue() > 0;
        }
        if (value instanceof String stringValue) {
            return stringValue.matches("\\d+")
                    && parsesToPositiveLong(stringValue);
        }
        return false;
    }

    private boolean parsesToPositiveLong(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private String maskRecipient(String recipientEmail) {
        int atIndex = recipientEmail.indexOf('@');
        if (atIndex == 1) {
            return "***" + recipientEmail.substring(atIndex);
        }
        return recipientEmail.charAt(0) + "***" + recipientEmail.substring(atIndex);
    }
}
