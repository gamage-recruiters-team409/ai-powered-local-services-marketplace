package com.gamagerecruiters.marketplace.modules.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class NotificationDataSanitizerTest {
    private final NotificationDataSanitizer sanitizer = new NotificationDataSanitizer();

    @Test
    void dropsForbiddenAndInvalidKeysAndNullValues() {
        Map<String, String> data = new LinkedHashMap<>();
        for (String key : List.of("token", "resetLink", "password", "address", "email", "phone", "apiKey", "not-valid!")) {
            data.put(key, "x");
        }
        data.put("district", null);

        assertTrue(sanitizer.sanitize(data, key -> { }).isEmpty());
    }

    @Test
    void trimsLongValuesAndLimitsEntries() {
        Map<String, String> data = new LinkedHashMap<>();
        for (int index = 0; index < 12; index++) {
            data.put("reference", "BK-" + index);
        }
        data.put("reason", "x".repeat(201));

        Map<String, String> result = sanitizer.sanitize(data, key -> { });

        assertEquals(Map.of("reference", "BK-11"), result);
    }

    @Test
    void handlesNullMap() {
        assertTrue(sanitizer.sanitize(null, key -> { }).isEmpty());
    }

    @Test
    void legitimateAllowedValuesPassValidation() {
        Map<String, String> data = Map.of(
                "district", " Nugegoda ",
                "reference", " BK-2026-0001 ",
                "slotStart", " 2026-11-30T09:00:00Z ",
                "bookingStatus", " CONFIRMED ",
                "reason", " Provider is unavailable ",
                "conversationId", "conversation_1-2");

        Map<String, String> result = sanitizer.sanitize(data, key -> { });

        assertEquals("Nugegoda", result.get("district"));
        assertEquals("BK-2026-0001", result.get("reference"));
        assertEquals("2026-11-30T09:00:00Z", result.get("slotStart"));
        assertEquals("CONFIRMED", result.get("bookingStatus"));
        assertEquals("Provider is unavailable", result.get("reason"));
        assertEquals("conversation_1-2", result.get("conversationId"));
    }

    @Test
    void sensitiveReasonValuesAndInvalidAllowedValuesAreDropped() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("reason", "Visit 12 Main Street");
        data.put("district", "Colombo 03");
        data.put("slotStart", "tomorrow morning");
        data.put("bookingStatus", "confirmed");
        data.put("harmlessUnknown", "safe");
        data.put("token", "secret");

        Map<String, String> result = sanitizer.sanitize(data, key -> { });

        assertTrue(result.isEmpty());
        for (String reason : List.of("Call +94 77 123 4567", "Contact person@example.com",
                "See https://example.com", "See www.example.com", "See example.com", "Reference 1234567")) {
            assertTrue(sanitizer.sanitize(Map.of("reason", reason), key -> { }).isEmpty());
        }
    }
}
