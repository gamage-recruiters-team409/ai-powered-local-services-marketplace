package com.gamagerecruiters.marketplace.common.util;

public final class ValidationConstants {

    /**
     * Full name pattern:
     * - Starts with a Unicode letter or combining mark (no leading punctuation or whitespace)
     * - Contains only Unicode letters, combining marks, single spaces, apostrophe (' or ’), hyphen (-), dot (.)
     * - Ends with a Unicode letter or combining mark (no trailing punctuation or whitespace)
     * - Minimum length of 2 characters implicitly enforced by start + end tokens
     */
    public static final String FULL_NAME_REGEX = "^[\\p{L}\\p{M}][\\p{L}\\p{M} .'’-]*[\\p{L}\\p{M}]$";
    public static final String FULL_NAME_MESSAGE = "Full name can only contain letters, spaces, apostrophes, hyphens and dots.";

    /**
     * Sri Lankan phone pattern:
     * Accepts:
     * - +94XXXXXXXXX (12 chars starting with +94 followed by 9 digits)
     * - 0XXXXXXXXX (10 chars starting with 0 followed by 9 digits)
     * - 94XXXXXXXXX (11 chars starting with 94 followed by 9 digits)
     */
    public static final String SRI_LANKAN_PHONE_REGEX = "^(\\+94|0|94)[0-9]{9}$";
    public static final String SRI_LANKAN_PHONE_MESSAGE = "Invalid Sri Lankan phone number format. Expected +94XXXXXXXXX or 07XXXXXXXX";

    public static final String NO_CONTROL_CHARS_REGEX = "^[^\\p{Cntrl}]+$";
    public static final String NO_CONTROL_CHARS_MESSAGE = "Must not contain control characters";

    public static final String CITY_REGEX = "^(?!^\\d+$)[^\\p{Cntrl}]+$";
    public static final String CITY_MESSAGE = "City must not be digits only and must not contain control characters";

    public static final int MAX_PASSWORD_BYTES = 72;
    public static final String PASSWORD_MAX_MESSAGE = "Password must not exceed 72 characters";

    private ValidationConstants() {
    }
}
