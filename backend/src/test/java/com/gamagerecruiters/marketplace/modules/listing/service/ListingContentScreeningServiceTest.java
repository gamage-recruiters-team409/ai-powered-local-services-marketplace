package com.gamagerecruiters.marketplace.modules.listing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.gamagerecruiters.marketplace.modules.listing.service.ListingContentScreeningService.ScreeningResult;

class ListingContentScreeningServiceTest {

    private ListingContentScreeningService screeningService;

    @BeforeEach
    void setUp() {
        screeningService = new ListingContentScreeningService();
    }

    @Test
    @DisplayName("Clean professional listing content with pricing and location prose passes unflagged")
    void cleanContentPasses() {
        ScreeningResult result = screeningService.screen(
                "Professional AC Servicing & Gas Refill in Colombo",
                "15 years experience in Colombo District at residential homes and small offices. Rates start from LKR 3500 in 2026.",
                List.of("Outdoor condenser pressure wash"));

        assertThat(result.flagged()).isFalse();
        assertThat(result.flagReasons()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Call me directly on 0771234567 for a discount.",
            "Reach out at +94-71-234-5678 anytime.",
            "Direct office number: 0 7 5 1 2 3 4 5 6 7.",
            "Call 0\u200B7\u200B7 987 6543 today.",
            "Workshop desk landline 011-2345678 available.",
            "Fullwidth digits: ０７７１２３４５６７."
    })
    @DisplayName("Detects direct and obfuscated Sri Lankan phone numbers without echoing digits")
    void detectsSriLankanPhoneNumbers(String description) {
        ScreeningResult result = screeningService.screen("House Wiring Service", description, List.of());

        assertThat(result.flagged()).isTrue();
        assertThat(result.flagReasons()).anyMatch(r -> r.startsWith("PHONE_NUMBER_DETECTED"));
        assertThat(result.flagReasons()).noneMatch(r -> r.contains("4567") || r.contains("6543"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Send photos to electrician.colombo@gmail.com before booking.",
            "Message john [at] yahoo [dot] com for custom rates.",
            "Reach us at test at gmail dot com directly."
    })
    @DisplayName("Detects direct and obfuscated email addresses without echoing PII")
    void detectsEmails(String description) {
        ScreeningResult result = screeningService.screen("Plumbing Maintenance", description, List.of());

        assertThat(result.flagged()).isTrue();
        assertThat(result.flagReasons()).anyMatch(r -> r.startsWith("EMAIL_ADDRESS_DETECTED"));
        assertThat(result.flagReasons()).noneMatch(r -> r.contains("@"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Chat directly via https://wa.me/94771234567",
            "Join https://chat.whatsapp.com/AbCdEf123",
            "See our catalog on www.myplumbing.lk",
            "Message t.me/providerhandle for instant booking",
            "Visit facebook.com/colomboservices for deals"
    })
    @DisplayName("Detects messaging invites and external URLs")
    void detectsExternalLinks(String description) {
        ScreeningResult result = screeningService.screen("Tile & Masonry", description, List.of());

        assertThat(result.flagged()).isTrue();
        assertThat(result.flagReasons()).anyMatch(r -> r.startsWith("EXTERNAL_LINK_DETECTED"));
    }

    @Test
    @DisplayName("Detects contact leaks hidden inside add-on titles")
    void detectsContactLeakInAddOnTitle() {
        ScreeningResult result = screeningService.screen(
                "Solar Panel Cleaning Service",
                "Thorough roof solar cleaning with deionized water and full safety harness.",
                List.of("Call 077 999 8888 for rush service"));

        assertThat(result.flagged()).isTrue();
        assertThat(result.flagReasons()).anyMatch(r -> r.startsWith("PHONE_NUMBER_DETECTED"));
    }

    @Test
    @DisplayName("Detects contact leaks hidden inside add-on descriptions")
    void detectsContactLeakInAddOnDescription() {
        ScreeningResult result = screeningService.screen(
                "Emergency Pipe Leak Repair",
                "Thorough home plumbing diagnostic and pipe replacement across Colombo.",
                List.of("Emergency Night Callout"),
                List.of("WhatsApp +94 77 123 4567 for faster booking"));

        assertThat(result.flagged()).isTrue();
        assertThat(result.flagReasons()).anyMatch(r -> r.startsWith("PHONE_NUMBER_DETECTED"));
    }
}
