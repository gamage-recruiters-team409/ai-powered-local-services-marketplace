package com.gamagerecruiters.marketplace.modules.booking.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes an iCalendar (RFC 5545) file: CRLF line endings, text escaping, lines folded at 75 octets, times in UTC.
 * Kept minimal on purpose: one VEVENT per booking, no address (only the district) so a shared link or file leaks
 * no home address.
 */
final class IcsWriter {

    static final String CONTENT_TYPE = "text/calendar; charset=utf-8";

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    record Event(String uid, Instant start, Instant end, String summary, String description, String location,
                 boolean confirmed) {
    }

    private IcsWriter() {
    }

    static String write(String calendarName, List<Event> events, Instant now) {
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//LocalServe//Bookings//EN");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:PUBLISH");
        line(out, "X-WR-CALNAME:" + escape(calendarName));
        line(out, "X-WR-TIMEZONE:Asia/Colombo");
        for (Event e : events) {
            line(out, "BEGIN:VEVENT");
            line(out, "UID:" + escape(e.uid()));
            line(out, "DTSTAMP:" + UTC.format(now));
            line(out, "DTSTART:" + UTC.format(e.start()));
            line(out, "DTEND:" + UTC.format(e.end()));
            line(out, "SUMMARY:" + escape(e.summary()));
            if (e.description() != null) {
                line(out, "DESCRIPTION:" + escape(e.description()));
            }
            if (e.location() != null) {
                line(out, "LOCATION:" + escape(e.location()));
            }
            line(out, "STATUS:" + (e.confirmed() ? "CONFIRMED" : "TENTATIVE"));
            line(out, "END:VEVENT");
        }
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    /** RFC 5545 3.3.11: backslash, semicolon, comma and newlines are escaped. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    /** Adds one content line, folded so no physical line is longer than 75 octets (UTF-8 safe). */
    static void line(StringBuilder out, String content) {
        int octets = 0;
        int lineLimit = 75;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int size = ch.getBytes(StandardCharsets.UTF_8).length;
            if (octets + size > lineLimit) {
                out.append("\r\n ");
                octets = 1;
                lineLimit = 75;
            }
            out.append(ch);
            octets += size;
            i += Character.charCount(cp);
        }
        out.append("\r\n");
    }
}
