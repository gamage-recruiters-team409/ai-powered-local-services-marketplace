package com.gamagerecruiters.marketplace.modules.review.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.gamagerecruiters.marketplace.modules.review.domain.ModerationFlag;

class ReviewContentCheckerTest {

    private final ReviewContentChecker checker = new ReviewContentChecker("scam, rip off");

    @Test
    @DisplayName("Clean or empty text has no flags (a negative review is not flagged)")
    void cleanText() {
        assertTrue(checker.check(null).isEmpty());
        assertTrue(checker.check("   ").isEmpty());
        assertTrue(checker.check("Terrible service, arrived 2 hours late and left the kitchen dirty. 1/5.").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Call me on 0771234567",
            "Phone +94 77 123 4567 for cheaper rates",
            "contact 077-123-4567",
            "Email me at kamal.services@gmail.com"
    })
    @DisplayName("Phone numbers and e-mail addresses -> CONTACT_DETAILS")
    void contactDetails(String text) {
        assertEquals(List.of(ModerationFlag.CONTACT_DETAILS), checker.check(text));
    }

    @Test
    @DisplayName("Short numbers (prices, times, room counts) are not phone numbers")
    void shortNumbersAreFine() {
        assertTrue(checker.check("Paid LKR 6,500 for 3 rooms, finished by 12:30 on 18/11/2026").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Book at https://cheap-clean.lk", "See www.example.com", "Visit cleanpro.com today"})
    @DisplayName("Links and domain names -> LINK")
    void links(String text) {
        assertEquals(List.of(ModerationFlag.LINK), checker.check(text));
    }

    @Test
    @DisplayName("Default and configured blocked words, whole words only, any case -> BLOCKED_WORDS")
    void blockedWords() {
        assertEquals(List.of(ModerationFlag.BLOCKED_WORDS), checker.check("Message me on WhatsApp"));
        assertEquals(List.of(ModerationFlag.BLOCKED_WORDS), checker.check("This was a total SCAM"));
        assertEquals(List.of(ModerationFlag.BLOCKED_WORDS), checker.check("What a rip off"));
        assertTrue(checker.check("The scampi was not part of the service").isEmpty());
    }

    @Test
    @DisplayName("Several problems -> all flags, in a fixed order; an e-mail is not also counted as a link")
    void severalFlags() {
        assertEquals(List.of(ModerationFlag.CONTACT_DETAILS, ModerationFlag.BLOCKED_WORDS),
                checker.check("scam! write to a@b.com"));
        assertEquals(List.of(ModerationFlag.CONTACT_DETAILS, ModerationFlag.LINK),
                checker.check("0771234567 or www.cleanpro.lk"));
    }
}
