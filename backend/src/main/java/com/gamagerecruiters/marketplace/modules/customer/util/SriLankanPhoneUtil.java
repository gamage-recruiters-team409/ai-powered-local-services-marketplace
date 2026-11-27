package com.gamagerecruiters.marketplace.modules.customer.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
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

    /**
     * Returns the set of equivalent historical and canonical forms for a Sri Lankan phone number:
     * {@code {+94XXXXXXXXX, 0XXXXXXXXX, 94XXXXXXXXX}}.
     *
     * @param phone raw or normalized phone
     * @return unmodifiable set containing all three equivalent string representations
     */
    public static Set<String> equivalentForms(String phone) {
        String canonical = normalize(phone);
        String digits9 = canonical.substring(3);
        Set<String> forms = new LinkedHashSet<>();
        forms.add(canonical);
        forms.add("0" + digits9);
        forms.add("94" + digits9);
        return Collections.unmodifiableSet(forms);
    }
}
