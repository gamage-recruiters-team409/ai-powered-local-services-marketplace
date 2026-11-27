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
            "Silva K",
            "José",
            "Zoë",
            "Kalana    Dinuja"
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
            "Kalana #1",
            "e\u0301",
            "\u0301\u0301",
            "a\u0301 "
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
    @DisplayName("Should accept name that is 100 characters after normalization but longer before")
    void shouldAccept100CharsAfterNormalization() {
        String nameWithSpaces = "A" + "   ".repeat(5) + "B".repeat(98) + " ";
        assertTrue(nameWithSpaces.length() > 100);
        assertEquals(100, ValidationUtils.normalizeFullName(nameWithSpaces).length());
        assertTrue(ValidationUtils.isValidFullName(nameWithSpaces));

        String invalid101 = "A " + "B".repeat(99);
        assertEquals(101, ValidationUtils.normalizeFullName(invalid101).length());
        assertFalse(ValidationUtils.isValidFullName(invalid101));
    }

    @Test
    @DisplayName("Should normalize full name by trimming and collapsing multiple whitespace including NBSP and BOM")
    void shouldNormalizeFullName() {
        assertNull(ValidationUtils.normalizeFullName(null));
        assertEquals("", ValidationUtils.normalizeFullName("   "));
        assertEquals("Kalana Dinuja", ValidationUtils.normalizeFullName("  Kalana    Dinuja  "));
        assertEquals("Anne Marie", ValidationUtils.normalizeFullName("Anne\t\tMarie\n"));
        assertEquals("Kalana Dinuja", ValidationUtils.normalizeFullName("\u00A0Kalana\u00A0\u00A0Dinuja\u00A0"));
        assertEquals("Kalana Dinuja", ValidationUtils.normalizeFullName("Kalana\u202FDinuja"));
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

        // Exactly 72 bytes
        String exact72Bytes = "Aa1!" + "€".repeat(22) + "ab"; // 4 + 66 + 2 = 72 bytes
        assertEquals(72, exact72Bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(ValidationUtils.isValidPasswordLength(exact72Bytes));

        // 73 bytes (only 27 characters)
        String pw73Bytes = "Aa1!" + "€".repeat(23); // 4 + 69 = 73 bytes
        assertEquals(27, pw73Bytes.length());
        assertEquals(73, pw73Bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertFalse(ValidationUtils.isValidPasswordLength(pw73Bytes));

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

    @Test
    @DisplayName("Should strip only Unicode space separators and preserve control characters")
    void shouldStripOnlySpacesAndPreserveControlCharacters() {
        assertNull(ValidationUtils.stripSpaces(null));
        assertEquals("", ValidationUtils.stripSpaces(""));
        assertEquals("", ValidationUtils.stripSpaces("   "));
        assertEquals("Colombo 03", ValidationUtils.stripSpaces("  Colombo 03  "));
        assertEquals("Colombo 03", ValidationUtils.stripSpaces("\u00A0Colombo 03\u00A0"));
        assertEquals("Home\u0007", ValidationUtils.stripSpaces("  Home\u0007  "));
        assertEquals("\u0007Home", ValidationUtils.stripSpaces("  \u0007Home  "));
        assertEquals("\u007FHome\u007F", ValidationUtils.stripSpaces("\u007FHome\u007F"));
    }

    @Test
    @DisplayName("Should reject full names containing control characters at start or end")
    void shouldRejectFullNamesWithControlCharactersAtEdge() {
        assertFalse(ValidationUtils.isValidFullName("\u0007Kalana Dinuja"));
        assertFalse(ValidationUtils.isValidFullName("Kalana Dinuja\u0007"));
        assertFalse(ValidationUtils.isValidFullName("\u007FKalana Dinuja"));
        assertFalse(ValidationUtils.isValidFullName("Kalana Dinuja\u007F"));
    }
}
