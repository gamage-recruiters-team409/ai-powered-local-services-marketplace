package com.gamagerecruiters.marketplace.modules.operations.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;

/** DTOs for the Overdue bookings screen (WP09). */
public final class OverdueBookings {

    private OverdueBookings() {
    }

    /** One overdue booking row. Ids and ref only — no customer / provider names (WP05 does not expose them). */
    public record Row(String bookingId, String ref, String serviceTitle, String customerId, String providerId,
                      Instant serviceDate, long daysOverdue, OpsCaseStatus caseStatus, LastActionView lastAction) {
    }

    /** Count cards. {@code over24h} = bookings overdue by more than 24 hours. */
    public record Summary(long overdueNow, long over24h, long resolvedToday) {
    }

    /** Delay distribution buckets. */
    public record Distribution(long oneDay, long twoDays, long threeDays, long fourPlusDays) {
    }

    /** The full screen payload. */
    public record Data(OpsPage<Row> rows, Summary summary, Distribution distribution) {
    }
}
