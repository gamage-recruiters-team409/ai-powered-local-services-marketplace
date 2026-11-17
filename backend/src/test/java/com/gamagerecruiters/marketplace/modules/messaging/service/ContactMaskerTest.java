package com.gamagerecruiters.marketplace.modules.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContactMaskerTest {

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
