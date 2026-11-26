package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IcsWriterTest {

    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");

    @Test
    @DisplayName("Valid VCALENDAR with CRLF lines, UTC times and one VEVENT per booking")
    void structure() {
        String ics = IcsWriter.write("LocalServe bookings", List.of(new IcsWriter.Event("b-1@localserve",
                Instant.parse("2026-12-01T03:30:00Z"), Instant.parse("2026-12-01T05:30:00Z"), "AC Repair (BK-1)",
                "Booking BK-1\nStatus: CONFIRMED", "Kandy", true)), NOW);

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n").endsWith("END:VCALENDAR\r\n");
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
        assertThat(ics).contains("UID:b-1@localserve\r\n", "DTSTART:20261201T033000Z\r\n",
                "DTEND:20261201T053000Z\r\n", "DTSTAMP:20261120T040000Z\r\n", "STATUS:CONFIRMED\r\n",
                "LOCATION:Kandy\r\n", "DESCRIPTION:Booking BK-1\\nStatus: CONFIRMED\r\n");
    }

    @Test
    @DisplayName("Text escaping: backslash, semicolon, comma and newline")
    void escaping() {
        assertThat(IcsWriter.escape("a\\b;c,d\ne")).isEqualTo("a\\\\b\\;c\\,d\\ne");
        assertThat(IcsWriter.escape(null)).isEmpty();
    }

    @Test
    @DisplayName("Long lines are folded at 75 octets (also with multi-byte characters) and unfold back exactly")
    void folding() {
        String summary = "SUMMARY:" + "Deep House Cleaning – ගෙදර පිරිසිදු කිරීම ".repeat(6);
        StringBuilder out = new StringBuilder();
        IcsWriter.line(out, summary);

        for (String physical : out.toString().split("\r\n")) {
            assertThat(physical.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        assertThat(out.toString().replace("\r\n ", "")).isEqualTo(summary + "\r\n");
    }

    @Test
    @DisplayName("Awaiting payment → TENTATIVE")
    void tentative() {
        String ics = IcsWriter.write("x", List.of(new IcsWriter.Event("b-2@localserve", NOW, NOW.plusSeconds(3600),
                "Booking", null, null, false)), NOW);
        assertThat(ics).contains("STATUS:TENTATIVE\r\n").doesNotContain("LOCATION:");
    }
}
