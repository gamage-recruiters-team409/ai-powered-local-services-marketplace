package com.gamagerecruiters.marketplace.modules.customer.util;

import java.util.regex.Pattern;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public final class SriLankanPhoneUtil {

    private static final Pattern CLEAN_PATTERN = Pattern.compile("[\\s\\-\\(\\)]");
    private static final Pattern SRI_LANKAN_PHONE_PATTERN = Pattern.compile("^(\\+94|0|94)[0-9]{9}$");

    private SriLankanPhoneUtil() {
    }

    /**
     * Validates and normalizes Sri Lankan phone numbers to standard format {@code +94XXXXXXXXX}.
     * Accepts:
     * <ul>
     *   <li>+94XXXXXXXXX (12 chars starting with +94)</li>
     *   <li>07XXXXXXXX / 0XXXXXXXXX (10 chars starting with 0)</li>
     *   <li>94XXXXXXXXX (11 chars starting with 94)</li>
     * </ul>
     *
     * @param rawPhone raw phone input
     * @return normalized phone in {@code +94XXXXXXXXX} format
     * @throws ApiException with 400 Bad Request if format is invalid
     */
    public static String normalize(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE", "Phone number is required");
        }

        String cleaned = CLEAN_PATTERN.matcher(rawPhone.trim()).replaceAll("");

        if (!SRI_LANKAN_PHONE_PATTERN.matcher(cleaned).matches()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PHONE_FORMAT",
                    "Invalid Sri Lankan phone number format. Expected +94XXXXXXXXX or 07XXXXXXXX"
            );
        }

        if (cleaned.startsWith("+94")) {
            return cleaned;
        } else if (cleaned.startsWith("0")) {
            return "+94" + cleaned.substring(1);
        } else if (cleaned.startsWith("94")) {
            return "+" + cleaned;
        }

        return cleaned;
    }
}
