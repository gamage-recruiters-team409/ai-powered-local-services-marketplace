package com.gamagerecruiters.marketplace.modules.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import org.junit.jupiter.api.Test;

class ContactMaskerTest {

    @Test
    void keepsTextWithParenthesesUnchanged() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(ContactMasker.mask("Hello (how are you)")).isEqualTo("Hello (how are you)");
            assertThat(ContactMasker.mask("( ok")).isEqualTo("( ok");
            assertThat(ContactMasker.mask("(")).isEqualTo("(");
            assertThat(ContactMasker.mask("text (")).isEqualTo("text (");
            assertThat(ContactMasker.mask("((")).isEqualTo("((");
            assertThat(ContactMasker.mask("()")).isEqualTo("()");
            assertThat(ContactMasker.mask("Thanks :)")).isEqualTo("Thanks :)");
            assertThat(ContactMasker.mask("see (note) 2026-11-20 at 10"))
                    .isEqualTo("see (note) 2026-11-20 at 10");
        });
    }

    @Test
    void masksPhoneInsideParentheses() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(ContactMasker.mask("(077) 123 4567")).isEqualTo("[contact hidden]");
            assertThat(ContactMasker.mask("call (077)1234567"))
                    .isEqualTo("call [contact hidden]");
        });
    }

    @Test
    void masksHyphenatedPhoneThatLooksLikeDate() {
        assertThat(ContactMasker.mask("077-1234-56-78")).isEqualTo("[contact hidden]");
        assertThat(ContactMasker.mask("077-2012-05-06")).isEqualTo("[contact hidden]");
        assertThat(ContactMasker.mask("0771-2026-11-20")).isEqualTo("[contact hidden]");
    }

    @Test
    void masksPhoneNextToDateWithSpace() {
        assertThat(ContactMasker.mask("2026-11-20 0771234567"))
                .isEqualTo("2026-11-20 [contact hidden]");
        assertThat(ContactMasker.mask("0771234567 2026-11-20"))
                .isEqualTo("[contact hidden] 2026-11-20");
    }

    @Test
    void keepsPlausibleDateAndTimeUnchanged() {
        assertThat(ContactMasker.mask("see you 2026-11-20 10:30"))
                .isEqualTo("see you 2026-11-20 10:30");
        assertThat(ContactMasker.mask("on 2026-11-20 at 10"))
                .isEqualTo("on 2026-11-20 at 10");
    }

    @Test
    void longTextWithManyBracketsAndDigitsFinishesQuickly() {
        String input = "(123456789 ".repeat(200).substring(0, 2000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThat(ContactMasker.mask(input)).isNotNull());
    }

    @Test
    void masksPhoneNumberDirectlyAfterIsoDate() {
        assertThat(ContactMasker.mask("2026-11-20 0771234567"))
                .isEqualTo("2026-11-20 [contact hidden]");
    }

    @Test
    void masksPhoneNumberDirectlyBeforeIsoDate() {
        assertThat(ContactMasker.mask("0771234567 2026-11-20"))
                .isEqualTo("[contact hidden] 2026-11-20");
    }

    @Test
    void masksPhoneWithDoubleSpaces() {
        assertThat(ContactMasker.mask("077  123  4567"))
                .isEqualTo("[contact hidden]");
    }

    @Test
    void masksPhoneWithMixedSeparators() {
        assertThat(ContactMasker.mask("(077) 123.456-7"))
                .isEqualTo("[contact hidden]");
    }

    @Test
    void masksTwoPhoneNumbersJoinedBySpace() {
        assertThat(ContactMasker.mask("0771234567 0779876543"))
                .isEqualTo("[contact hidden] [contact hidden]");
    }

    @Test
    void keepsIsoDateAndTimeUnchanged() {
        assertThat(ContactMasker.mask("see you 2026-11-20 10:30"))
                .isEqualTo("see you 2026-11-20 10:30");
        assertThat(ContactMasker.mask("on 2026-11-20 at 10"))
                .isEqualTo("on 2026-11-20 at 10");
    }

    @Test
    void keepsShortNumbersAndPricesUnchanged() {
        assertThat(ContactMasker.mask("Rs 15000")).isEqualTo("Rs 15000");
        assertThat(ContactMasker.mask("at 10:30")).isEqualTo("at 10:30");
        assertThat(ContactMasker.mask("I need 3 people at 10 11 12"))
                .isEqualTo("I need 3 people at 10 11 12");
    }

    @Test
    void longDigitInputFinishesQuickly() {
        String input = "1".repeat(2000) + " ".repeat(20);
        assertThat(ContactMasker.mask(input)).startsWith("[contact hidden]");
    }

    @Test
    void masksEmailsAndPhoneFormats() {
        assertThat(ContactMasker.mask("Email a.person@example.com or call 077-123-4567 / +94 77 123 4567"))
                .isEqualTo("Email [contact hidden] or call [contact hidden] / [contact hidden]");
        assertThat(ContactMasker.mask("(077) 123 4567")).isEqualTo("[contact hidden]");
    }

    @Test
    void leavesShortNumbersPricesDatesAndTimesAlone() {
        assertThat(ContactMasker.mask("see you 2026-11-20 10:30")).isEqualTo("see you 2026-11-20 10:30");
        assertThat(ContactMasker.mask("on 2026-11-20 at 10")).isEqualTo("on 2026-11-20 at 10");
        assertThat(ContactMasker.mask("Rs 15000")).isEqualTo("Rs 15000");
        assertThat(ContactMasker.mask("I need 3 people at 10 11 12"))
                .isEqualTo("I need 3 people at 10 11 12");
    }

    @Test
    void isNullAndEmptySafeAndMasksMultipleContacts() {
        assertThat(ContactMasker.mask(null)).isNull();
        assertThat(ContactMasker.mask("")).isEmpty();
        assertThat(ContactMasker.mask("a@b.example and b@c.example")).isEqualTo(
                "[contact hidden] and [contact hidden]");
    }

    @Test
    void masksEmailAndPhoneTogetherAndAtTextBoundaries() {
        assertThat(ContactMasker.mask("Email a.person@example.com or call 077.123.4567"))
                .isEqualTo("Email [contact hidden] or call [contact hidden]");
        assertThat(ContactMasker.mask("0771234567 at the start")).isEqualTo("[contact hidden] at the start");
        assertThat(ContactMasker.mask("at the end 0771234567")).isEqualTo("at the end [contact hidden]");
    }
}
