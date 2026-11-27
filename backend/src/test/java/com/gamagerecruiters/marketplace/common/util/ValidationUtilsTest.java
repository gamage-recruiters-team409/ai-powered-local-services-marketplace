package com.gamagerecruiters.marketplace.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValidationUtils Unit Tests")
class ValidationUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Kalana Dinuja",
            "Anne-Marie",
            "O'Brien",
            "D’Angelo",
            "A. B. Perera",
            "කමල් පෙරේරා",
            "கமல் பெரேரா",
            "Li",
            "Silva K"
    })
    @DisplayName("Should accept valid full names")
    void shouldAcceptValidFullNames(String validName) {
        assertTrue(ValidationUtils.isValidFullName(validName),
                () -> "Expected valid: " + validName);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "kalana test 14444",
            "John_Doe",
            "A",
            "A.",
            ".John",
            "John.",
            "-John",
            "John-",
            "'John",
            "John'",
            "’John",
            "John’",
            "😀 Kalana",
            "Kalana @ Dinuja",
            "Kalana #1"
    })
    @DisplayName("Should reject invalid full names")
    void shouldRejectInvalidFullNames(String invalidName) {
        assertFalse(ValidationUtils.isValidFullName(invalidName),
                () -> "Expected invalid: " + invalidName);
    }

    @Test
    @DisplayName("Should reject null full name")
    void shouldRejectNullFullName() {
        assertFalse(ValidationUtils.isValidFullName(null));
    }

    @Test
    @DisplayName("Should reject full names exceeding 100 characters")
    void shouldRejectLongFullName() {
        String longName = "A".repeat(101);
        assertFalse(ValidationUtils.isValidFullName(longName));
    }

    @Test
    @DisplayName("Should normalize full name by trimming and collapsing multiple whitespace")
    void shouldNormalizeFullName() {
        assertNull(ValidationUtils.normalizeFullName(null));
        assertEquals("", ValidationUtils.normalizeFullName("   "));
        assertEquals("Kalana Dinuja", ValidationUtils.normalizeFullName("  Kalana    Dinuja  "));
        assertEquals("Anne Marie", ValidationUtils.normalizeFullName("Anne\t\tMarie\n"));
    }

    @Test
    @DisplayName("Should validate minimum 2 letters requirement")
    void shouldValidateMinimumLetters() {
        assertFalse(ValidationUtils.hasAtLeastTwoLetters(null));
        assertFalse(ValidationUtils.hasAtLeastTwoLetters(""));
        assertFalse(ValidationUtils.hasAtLeastTwoLetters("A"));
        assertFalse(ValidationUtils.hasAtLeastTwoLetters("A."));
        assertTrue(ValidationUtils.hasAtLeastTwoLetters("Li"));
        assertTrue(ValidationUtils.hasAtLeastTwoLetters("කම"));
    }

    @Test
    @DisplayName("Should validate password length within 72 bytes")
    void shouldValidatePasswordLength() {
        assertTrue(ValidationUtils.isValidPasswordLength("Password123"));
        assertTrue(ValidationUtils.isValidPasswordLength("a".repeat(72)));
        assertFalse(ValidationUtils.isValidPasswordLength("a".repeat(73)));
        assertFalse(ValidationUtils.isValidPasswordLength(null));
        // Multi-byte characters: 25 characters of 3 bytes each = 75 bytes > 72 bytes
        String multiBytePassword = "€".repeat(25);
        assertFalse(ValidationUtils.isValidPasswordLength(multiBytePassword));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+94771234567",
            "0771234567",
            "94771234567"
    })
    @DisplayName("Should accept valid Sri Lankan phone numbers")
    void shouldAcceptValidSriLankanPhone(String phone) {
        assertTrue(ValidationUtils.isValidSriLankanPhone(phone));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+12345678901",
            "077123456",
            "07712345678",
            "invalid-phone",
            ""
    })
    @DisplayName("Should reject invalid Sri Lankan phone numbers")
    void shouldRejectInvalidSriLankanPhone(String phone) {
        assertFalse(ValidationUtils.isValidSriLankanPhone(phone));
    }

    @Test
    @DisplayName("Should clean phone characters")
    void shouldCleanPhone() {
        assertEquals("+94771234567", ValidationUtils.cleanPhone("+94 77 123-4567"));
        assertNull(ValidationUtils.cleanPhone(null));
    }
}
