package com.gamagerecruiters.marketplace.modules.notification.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class NotificationDataSanitizer {
    private static final Pattern CONVERSATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern DISTRICT = Pattern.compile("[A-Za-z '-]{1,60}");
    private static final Pattern BOOKING_STATUS = Pattern.compile("[A-Z_]{1,40}");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern URL_OR_DOMAIN = Pattern.compile("(?i)(https?://|www\\.|\\b[A-Za-z0-9-]+\\.[A-Za-z]{2,}\\b)");
    private static final Pattern PHONE_LIKE = Pattern.compile("(?:\\d[\\s.\\-+()\\[\\]]*){7,}");
    private static final Pattern STREET_ADDRESS = Pattern.compile(
            "(?i)\\b\\d+\\b(?:\\s+[A-Za-z]+){0,4}\\s+(street|st\\.?|road|rd\\.?|lane|ln\\.?|avenue|ave\\.?|mawatha|mw\\.?|place|drive|floor|apartment|apt\\.?|no\\.?)\\b");
    private static final String[] FORBIDDEN = {"token", "link", "url", "password", "secret", "address", "email", "phone", "key"};

    public Map<String, String> sanitize(Map<String, String> source, Consumer<String> droppedKeyLogger) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (source == null) return safe;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (safe.size() == 10) break;
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            key = key.trim();
            if (!allowedKey(key) || forbidden(key)) {
                if (key != null) droppedKeyLogger.accept(key);
                continue;
            }
            if (entry.getValue() == null) continue;
            String value = entry.getValue().trim();
            if (value.length() > 200 || !validValue(key, value)) {
                droppedKeyLogger.accept(key);
                continue;
            }
            safe.put(key, value);
        }
        return safe;
    }

    private boolean forbidden(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String word : FORBIDDEN) if (lower.contains(word)) return true;
        return false;
    }

    private boolean allowedKey(String key) {
        return switch (key) {
            case "conversationId", "reference", "district", "slotStart", "bookingStatus", "reason" -> true;
            default -> false;
        };
    }

    private boolean validValue(String key, String value) {
        return switch (key) {
            case "conversationId" -> CONVERSATION_ID.matcher(value).matches();
            case "reference" -> REFERENCE.matcher(value).matches();
            case "district" -> DISTRICT.matcher(value).matches();
            case "slotStart" -> validSlotStart(value);
            case "bookingStatus" -> BOOKING_STATUS.matcher(value).matches();
            case "reason" -> validReason(value);
            default -> false;
        };
    }

    private boolean validSlotStart(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (RuntimeException ignored) {
            try {
                OffsetDateTime.parse(value);
                return true;
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }

    private boolean validReason(String value) {
        if (value.isBlank() || value.length() > 120 || EMAIL_ADDRESS.matcher(value).find()
                || URL_OR_DOMAIN.matcher(value).find() || PHONE_LIKE.matcher(value).find()
                || STREET_ADDRESS.matcher(value).find()) {
            return false;
        }
        return value.chars().noneMatch(character -> Character.isISOControl(character)
                || character == '<' || character == '>');
    }
}
