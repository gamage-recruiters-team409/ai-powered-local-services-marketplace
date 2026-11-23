package com.gamagerecruiters.marketplace.modules.notification.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class NotificationDataSanitizer {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_]{1,40}");
    private static final String[] FORBIDDEN = {"token", "link", "url", "password", "secret", "address", "email", "phone", "key"};

    public Map<String, String> sanitize(Map<String, String> source, Consumer<String> droppedKeyLogger) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (source == null) return safe;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (safe.size() == 10) break;
            String key = entry.getKey();
            if (key == null || !KEY.matcher(key).matches() || forbidden(key)) {
                if (key != null) droppedKeyLogger.accept(key);
                continue;
            }
            if (entry.getValue() == null) continue;
            String value = entry.getValue().trim();
            safe.put(key, value.length() > 200 ? value.substring(0, 200) : value);
        }
        return safe;
    }

    private boolean forbidden(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String word : FORBIDDEN) if (lower.contains(word)) return true;
        return false;
    }
}
