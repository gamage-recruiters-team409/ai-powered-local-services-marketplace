package com.gamagerecruiters.marketplace.modules.review.moderation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.review.domain.ModerationFlag;

/**
 * Automated checks before a review is published (FR-REV-003).
 *
 * <p>Rule-based and explainable: contact details (phone numbers, e-mail addresses), links, and blocked
 * words/phrases. A flagged review is held as AWAITING_MODERATION for a moderator; nothing is removed
 * automatically (BR-016). Negative wording alone is never a reason to hold a review.
 *
 * <p>Blocked words: a small default list of off-platform contact phrases, plus anything configured in
 * {@code app.review.moderation.blocked-words} (comma-separated, matched as whole words, case-insensitive).
 * AI-004 (moderation score, WP09) can be added later as another check without changing this class.
 */
@Component
public class ReviewContentChecker {

    /** Off-platform contact phrases (the platform keeps communication in-app). */
    static final List<String> DEFAULT_BLOCKED_WORDS = List.of("whatsapp", "telegram", "viber", "dm me");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** A run of digits with optional spaces, dots, dashes or brackets; checked for 9+ digits below. */
    private static final Pattern PHONE_CANDIDATE = Pattern.compile("\\+?\\d[\\d\\s().-]{7,}\\d");
    private static final int MIN_PHONE_DIGITS = 9;

    private static final Pattern LINK = Pattern.compile(
            "(?i)(https?://\\S+|www\\.\\S+|\\b[a-z0-9-]+\\.(com|lk|net|org|io|info|biz)\\b)");

    private final List<Pattern> blockedPatterns;

    public ReviewContentChecker(@Value("${app.review.moderation.blocked-words:}") String configuredBlockedWords) {
        Set<String> words = new LinkedHashSet<>(DEFAULT_BLOCKED_WORDS);
        if (configuredBlockedWords != null) {
            Arrays.stream(configuredBlockedWords.split(","))
                    .map(String::trim)
                    .filter(word -> !word.isEmpty())
                    .map(word -> word.toLowerCase(Locale.ROOT))
                    .forEach(words::add);
        }
        List<Pattern> patterns = new ArrayList<>();
        for (String word : words) {
            patterns.add(Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])"));
        }
        this.blockedPatterns = List.copyOf(patterns);
    }

    /** Flags found in the review text, in a fixed order; empty when the text is clean or blank. */
    public List<ModerationFlag> check(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ModerationFlag> flags = new ArrayList<>();
        if (EMAIL.matcher(text).find() || containsPhoneNumber(text)) {
            flags.add(ModerationFlag.CONTACT_DETAILS);
        }
        // An e-mail address also matches the domain pattern; only count links outside e-mail addresses
        if (LINK.matcher(EMAIL.matcher(text).replaceAll(" ")).find()) {
            flags.add(ModerationFlag.LINK);
        }
        if (blockedPatterns.stream().anyMatch(pattern -> pattern.matcher(text).find())) {
            flags.add(ModerationFlag.BLOCKED_WORDS);
        }
        return List.copyOf(flags);
    }

    private static boolean containsPhoneNumber(String text) {
        Matcher matcher = PHONE_CANDIDATE.matcher(text);
        while (matcher.find()) {
            long digits = matcher.group().chars().filter(Character::isDigit).count();
            if (digits >= MIN_PHONE_DIGITS) {
                return true;
            }
        }
        return false;
    }
}
