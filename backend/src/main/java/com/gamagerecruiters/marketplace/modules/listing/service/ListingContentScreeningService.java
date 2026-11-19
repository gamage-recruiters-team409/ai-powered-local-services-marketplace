package com.gamagerecruiters.marketplace.modules.listing.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * Automated anti-circumvention scanner for listing text fields (FR-LIST-006, BR-011).
 * Detects direct and obfuscated Sri Lankan phone numbers, emails, and messaging/external URLs
 * without echoing raw leaked PII in the returned flag descriptions.
 */
@Service
public class ListingContentScreeningService {

    private static final Pattern ZERO_WIDTH_PATTERN = Pattern.compile("[\\u200B-\\u200D\\uFEFF\\u00AD]");

    /**
     * Strips formatting separators placed between digits or immediately after +94,
     * turning "0 7 7 - 1 2 3 - 4 5 6 7" into "0771234567" without joining adjacent words.
     */
    private static final Pattern DIGIT_SEPARATOR_PATTERN =
            Pattern.compile("(?<=\\d)[\\s.\\-_()/]+(?=\\d)|(?<=\\+94)[\\s.\\-_()/]+(?=\\d)");

    private static final Pattern SRI_LANKAN_PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)(?:(?:\\+|00)?94|0)(?:7[01245678]|11|2[134567]|3[1-8]|4[157]|5[12457]|6[3567]|81|91)\\d{7}(?!\\d)"
    );

    private static final Pattern OBFUSCATED_AT_PATTERN =
            Pattern.compile("(?i)(?:\\s*\\[\\s*at\\s*\\]\\s*|\\s*\\(\\s*at\\s*\\)\\s*|\\s*\\{\\s*at\\s*\\}\\s*|\\s+at\\s+)");

    private static final Pattern OBFUSCATED_DOT_PATTERN =
            Pattern.compile("(?i)(?:\\s*\\[\\s*dot\\s*\\]\\s*|\\s*\\(\\s*dot\\s*\\)\\s*|\\s*\\{\\s*dot\\s*\\}\\s*|\\s+dot\\s+)");

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("(?i)[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}");

    private static final Pattern EXTERNAL_LINK_OR_MESSAGING_PATTERN = Pattern.compile(
            "(?i)(?:https?://|www\\.|wa\\.me/|chat\\.whatsapp\\.com|whatsapp\\.com/|t\\.me/|telegram\\.me/|facebook\\.com/|fb\\.com/|instagram\\.com/|linkedin\\.com/|whatsapp\\s*(?:me|us|at|on|:|-)\\s*[+\\d])"
    );

    public ScreeningResult screen(String title, String description, List<String> addOnTitles) {
        return screen(title, description, addOnTitles, List.of());
    }

    public ScreeningResult screen(
            String title,
            String description,
            List<String> addOnTitles,
            List<String> addOnDescriptions
    ) {
        String combined = buildCombinedText(title, description, addOnTitles, addOnDescriptions);
        if (combined.isBlank()) {
            return new ScreeningResult(false, List.of());
        }

        String normalized = normalizeUnicode(combined);
        List<String> reasons = new ArrayList<>();

        String phoneCandidateText = DIGIT_SEPARATOR_PATTERN.matcher(normalized).replaceAll("");
        if (SRI_LANKAN_PHONE_PATTERN.matcher(phoneCandidateText).find()) {
            reasons.add("PHONE_NUMBER_DETECTED: Listing content appears to include a phone number");
        }

        String emailCandidateText = OBFUSCATED_DOT_PATTERN.matcher(
                OBFUSCATED_AT_PATTERN.matcher(normalized).replaceAll("@")
        ).replaceAll(".");
        if (EMAIL_PATTERN.matcher(emailCandidateText).find()) {
            reasons.add("EMAIL_ADDRESS_DETECTED: Listing content appears to include an email address");
        }

        if (EXTERNAL_LINK_OR_MESSAGING_PATTERN.matcher(normalized).find()) {
            reasons.add("EXTERNAL_LINK_DETECTED: Listing content appears to include an external URL or messaging link");
        }

        return new ScreeningResult(!reasons.isEmpty(), List.copyOf(reasons));
    }

    private String buildCombinedText(
            String title,
            String description,
            List<String> addOnTitles,
            List<String> addOnDescriptions
    ) {
        StringBuilder builder = new StringBuilder();
        appendIfPresent(builder, title);
        appendIfPresent(builder, description);
        appendAllIfPresent(builder, addOnTitles);
        appendAllIfPresent(builder, addOnDescriptions);
        return builder.toString();
    }

    private void appendAllIfPresent(StringBuilder builder, List<String> values) {
        if (values != null) {
            for (String value : values) {
                appendIfPresent(builder, value);
            }
        }
    }

    private void appendIfPresent(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(value.trim());
        }
    }

    private String normalizeUnicode(String text) {
        String withoutZeroWidth = ZERO_WIDTH_PATTERN.matcher(text).replaceAll("");
        return Normalizer.normalize(withoutZeroWidth, Normalizer.Form.NFKC);
    }

    public record ScreeningResult(boolean flagged, List<String> flagReasons) {
    }
}
