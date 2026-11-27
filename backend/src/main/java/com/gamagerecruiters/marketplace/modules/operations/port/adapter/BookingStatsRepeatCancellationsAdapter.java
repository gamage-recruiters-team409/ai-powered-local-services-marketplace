package com.gamagerecruiters.marketplace.modules.operations.port.adapter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.booking.dto.stats.RepeatCancellation;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingStatsFacade;
import com.gamagerecruiters.marketplace.modules.operations.port.RepeatCancellationsPort;

/** Real repeat-cancellations source backed by the WP05 {@link BookingStatsFacade#findRepeatCancellations(int, int)}. */
@Component
public class BookingStatsRepeatCancellationsAdapter implements RepeatCancellationsPort {

    private static final Logger log = LoggerFactory.getLogger(BookingStatsRepeatCancellationsAdapter.class);

    private final BookingStatsFacade bookingStats;

    public BookingStatsRepeatCancellationsAdapter(BookingStatsFacade bookingStats) {
        this.bookingStats = bookingStats;
    }

    @Override
    public Result findRepeatCancellations(int minCount, int days) {
        try {
            List<RepeatCanceller> rows = bookingStats.findRepeatCancellations(minCount, days).stream()
                    .map(BookingStatsRepeatCancellationsAdapter::toRow)
                    .toList();
            return Result.available(rows);
        } catch (RuntimeException e) {
            log.warn("event=OPS_REPEAT_CANCELLATION_SOURCE_FAILED cause={}", e.getClass().getSimpleName());
            return Result.unavailable("Repeat-cancellation source failed for this request (WP05 "
                    + "BookingStatsFacade.findRepeatCancellations).");
        }
    }

    private static RepeatCanceller toRow(RepeatCancellation c) {
        return new RepeatCanceller(c.userId(), c.role(), c.count(), c.lastCancelledAt());
    }
}
