package com.gamagerecruiters.marketplace.modules.customer.util;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.gamagerecruiters.marketplace.common.exception.ApiException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SriLankanPhoneUtil Unit Tests")
class SriLankanPhoneUtilTest {

    @Test
    @DisplayName("Should generate exact 3 equivalent forms for canonical +94 number")
    void shouldGenerateEquivalentFormsFromCanonical() {
        Set<String> forms = SriLankanPhoneUtil.equivalentForms("+94771234567");
        assertEquals(3, forms.size());
        assertTrue(forms.contains("+94771234567"));
        assertTrue(forms.contains("0771234567"));
        assertTrue(forms.contains("94771234567"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0771234567", "94771234567", "+94 77 123 4567", "077-123-4567"})
    @DisplayName("Should generate equivalent forms regardless of input format")
    void shouldGenerateEquivalentFormsFromVariedInputs(String input) {
        Set<String> forms = SriLankanPhoneUtil.equivalentForms(input);
        assertEquals(3, forms.size());
        assertTrue(forms.contains("+94771234567"));
        assertTrue(forms.contains("0771234567"));
        assertTrue(forms.contains("94771234567"));
    }

    @Test
    @DisplayName("Should throw ApiException when generating equivalent forms for invalid phone")
    void shouldThrowOnInvalidPhoneForEquivalentForms() {
        assertThrows(ApiException.class, () -> SriLankanPhoneUtil.equivalentForms(null));
        assertThrows(ApiException.class, () -> SriLankanPhoneUtil.equivalentForms("12345"));
        assertThrows(ApiException.class, () -> SriLankanPhoneUtil.equivalentForms("+12345678901"));
    }
}
