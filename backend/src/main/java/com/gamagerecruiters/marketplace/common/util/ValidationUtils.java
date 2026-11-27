package com.gamagerecruiters.marketplace.common.util;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class ValidationUtils {

    private static final Pattern FULL_NAME_PATTERN = Pattern.compile(ValidationConstants.FULL_NAME_REGEX);
    private static final Pattern UNICODE_WHITESPACE_PATTERN = Pattern.compile("[\\s\\p{Z}\\uFEFF]+");
    private static final Pattern SRI_LANKAN_PHONE_PATTERN = Pattern.compile(ValidationConstants.SRI_LANKAN_PHONE_REGEX);
    private static final Pattern CLEAN_PHONE_PATTERN = Pattern.compile("[\\s\\-\\(\\)]");

    private ValidationUtils() {
    }

    /**
     * Strips leading and trailing Unicode space characters (Character.isSpaceChar / \p{Z}) without
     * removing control characters (such as \u0007, \t, \n, \r, \u007F).
     *
     * @param str input string
     * @return string with leading and trailing spaces removed, or null if input was null
     */
    public static String stripSpaces(String str) {
        if (str == null) {
            return null;
        }
        int len = str.length();
        int start = 0;
        while (start < len && Character.isSpaceChar(str.charAt(start))) {
            start++;
        }
        int end = len;
        while (end > start && Character.isSpaceChar(str.charAt(end - 1))) {
            end--;
        }
        return str.substring(start, end);
    }

    /**
     * Trims and collapses multiple consecutive whitespace characters (including Unicode spaces, NBSP, BOM) to a single space.
     * Uses stripSpaces rather than trim() so that control characters at the edges are not silently removed.
     *
     * @param name input name
     * @return normalized name or null if input was null
     */
    public static String normalizeFullName(String name) {
        if (name == null) {
            return null;
        }
        return stripSpaces(UNICODE_WHITESPACE_PATTERN.matcher(name).replaceAll(" "));
    }

    /**
     * Checks if the string contains at least 2 Unicode letters (\\p{L}).
     *
     * @param str input string
     * @return true if string has 2 or more Unicode letters
     */
    public static boolean hasAtLeastTwoLetters(String str) {
        if (str == null) {
            return false;
        }
        int count = 0;
        for (int i = 0; i < str.length(); ) {
            int cp = str.codePointAt(i);
            if (Character.isLetter(cp)) {
                count++;
                if (count >= 2) {
                    return true;
                }
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Validates full name against the complete WP01 rule:
     * - not null
     * - length 2 to 100 characters after trimming
     * - matches regex (letters, combining marks, single spaces, apostrophes, hyphens, dots; no leading/trailing punctuation)
     * - contains at least 2 Unicode letters
     *
     * @param name input name
     * @return true if valid
     */
    public static boolean isValidFullName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = normalizeFullName(name);
        if (normalized.length() < 2 || normalized.length() > 100) {
            return false;
        }
        if (!FULL_NAME_PATTERN.matcher(normalized).matches()) {
            return false;
        }
        return hasAtLeastTwoLetters(normalized);
    }

    /**
     * Validates password UTF-8 byte length (BCrypt max 72 bytes).
     *
     * @param password input password
     * @return true if password length in UTF-8 bytes does not exceed 72 bytes
     */
    public static boolean isValidPasswordLength(String password) {
        if (password == null) {
            return false;
        }
        return password.getBytes(StandardCharsets.UTF_8).length <= ValidationConstants.MAX_PASSWORD_BYTES;
    }

    /**
     * Strips whitespace, hyphens, and parentheses from a raw phone string.
     *
     * @param phone raw phone
     * @return cleaned digits and optional leading plus
     */
    public static String cleanPhone(String phone) {
        if (phone == null) {
            return null;
        }
        return CLEAN_PHONE_PATTERN.matcher(stripSpaces(phone)).replaceAll("");
    }

    /**
     * Validates whether the raw phone represents a valid Sri Lankan mobile phone number.
     *
     * @param phone raw phone
     * @return true if valid format
     */
    public static boolean isValidSriLankanPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        String cleaned = cleanPhone(phone);
        return SRI_LANKAN_PHONE_PATTERN.matcher(cleaned).matches();
    }
}
