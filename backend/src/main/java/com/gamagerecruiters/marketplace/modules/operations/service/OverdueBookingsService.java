package com.gamagerecruiters.marketplace.modules.operations.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseAction;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OperationsRules;
import com.gamagerecruiters.marketplace.modules.operations.dto.LastActionView;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsPage;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsSection;
import com.gamagerecruiters.marketplace.modules.operations.dto.OverdueBookings;
import com.gamagerecruiters.marketplace.modules.operations.port.OverdueBookingsPort;

/** Builds the Overdue bookings screen and its CSV from the real WP05 source plus the local action log. */
@Service
public class OverdueBookingsService {

    private final OverdueBookingsPort overduePort;
    private final OpsCaseActionStore actionStore;
    private final Clock clock;

    @Autowired
    public OverdueBookingsService(OverdueBookingsPort overduePort, OpsCaseActionStore actionStore) {
        this(overduePort, actionStore, Clock.systemUTC());
    }

    OverdueBookingsService(OverdueBookingsPort overduePort, OpsCaseActionStore actionStore, Clock clock) {
        this.overduePort = overduePort;
        this.actionStore = actionStore;
        this.clock = clock;
    }

    /** Export rows, distinguishing source unavailability from a valid empty set. */
    record ExportData(List<OverdueBookings.Row> rows, boolean sourceLimited) {}

    /**
     * All overdue rows (unpaged), used by the CSV export. Throws 503 when the source is unavailable so the
     * export endpoint returns an error instead of a header-only CSV. Applies search and delayBucket filters and
     * reports whether the source reached its fetch limit (indicating possible omissions).
     */
    ExportData allRows(String search, String delayBucket) {
        OverdueBookingsPort.Result result = overduePort.findOverdue(OperationsRules.OVERDUE_FETCH_LIMIT);
        if (!result.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OPS_SOURCE_UNAVAILABLE", result.reason());
        }
        boolean sourceLimited = result.bookings().size() >= OperationsRules.OVERDUE_FETCH_LIMIT;
        Instant now = clock.instant();
        List<String> ids = result.bookings().stream().map(OverdueBookingsPort.OverdueBooking::bookingId).toList();
        Map<String, OpsCaseAction> latest = actionStore.latestBySubject(OpsCaseType.OVERDUE_BOOKING, ids);
        List<OverdueBookings.Row> rows = result.bookings().stream()
                .filter(b -> matches(b, search))
                .map(b -> toRow(b, now, latest.get(b.bookingId())))
                .filter(r -> bucketMatches(r.daysOverdue(), delayBucket))
                .toList();
        return new ExportData(rows, sourceLimited);
    }

    public OpsSection<OverdueBookings.Data> screen(String search, String delayBucket, int page, int size) {
        OverdueBookingsPort.Result result = overduePort.findOverdue(OperationsRules.OVERDUE_FETCH_LIMIT);
        if (!result.available()) {
            return OpsSection.unavailable(result.reason());
        }
        Instant now = clock.instant();
        List<String> ids = result.bookings().stream().map(OverdueBookingsPort.OverdueBooking::bookingId).toList();
        Map<String, OpsCaseAction> latest = actionStore.latestBySubject(OpsCaseType.OVERDUE_BOOKING, ids);

        List<OverdueBookings.Row> all = result.bookings().stream()
                .map(b -> toRow(b, now, latest.get(b.bookingId())))
                .toList();

        // Summary and distribution are over the whole overdue set (stable regardless of search / bucket filters).
        OverdueBookings.Summary summary = new OverdueBookings.Summary(
                all.size(),
                all.stream().filter(r -> r.daysOverdue() >= 1).count(),
                actionStore.countResolvedToday(OpsCaseType.OVERDUE_BOOKING,
                        OperationsTime.todayStart(now), OperationsTime.tomorrowStart(now)));
        OverdueBookings.Distribution distribution = distribution(all);

        List<OverdueBookings.Row> filtered = all.stream()
                .filter(r -> matchesRow(r, search))
                .filter(r -> bucketMatches(r.daysOverdue(), delayBucket))
                .toList();
        OpsPage<OverdueBookings.Row> rows = OpsPage.of(filtered,
                OperationsRules.clampPage(page), OperationsRules.clampSize(size));
        return OpsSection.of(new OverdueBookings.Data(rows, summary, distribution));
    }

    private OverdueBookings.Row toRow(OverdueBookingsPort.OverdueBooking b, Instant now, OpsCaseAction latest) {
        long days = Math.max(0, Duration.between(b.slotEnd(), now).toDays());
        OpsCaseStatus status = latest == null ? OpsCaseStatus.OPEN : latest.getCaseStatus();
        return new OverdueBookings.Row(b.bookingId(), b.ref(), b.serviceTitle(), b.customerId(), b.providerId(),
                b.slotStart(), days, status, LastActionView.from(latest));
    }

    private static OverdueBookings.Distribution distribution(List<OverdueBookings.Row> rows) {
        long one = rows.stream().filter(r -> r.daysOverdue() <= 1).count();
        long two = rows.stream().filter(r -> r.daysOverdue() == 2).count();
        long three = rows.stream().filter(r -> r.daysOverdue() == 3).count();
        long four = rows.stream().filter(r -> r.daysOverdue() >= 4).count();
        return new OverdueBookings.Distribution(one, two, three, four);
    }

    private static boolean bucketMatches(long days, String delayBucket) {
        if (delayBucket == null || delayBucket.isBlank()) {
            return true;
        }
        return switch (delayBucket.strip()) {
            case "1" -> days <= 1;
            case "2" -> days == 2;
            case "3" -> days == 3;
            case "4+", "4" -> days >= 4;
            default -> true;
        };
    }

    private static boolean matches(OverdueBookingsPort.OverdueBooking b, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String q = search.strip().toLowerCase(Locale.ROOT);
        return contains(b.ref(), q) || contains(b.serviceTitle(), q) || contains(b.bookingId(), q)
                || contains(b.customerId(), q) || contains(b.providerId(), q);
    }

    private static boolean matchesRow(OverdueBookings.Row r, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String q = search.strip().toLowerCase(Locale.ROOT);
        return contains(r.ref(), q) || contains(r.serviceTitle(), q) || contains(r.bookingId(), q)
                || contains(r.customerId(), q) || contains(r.providerId(), q);
    }

    private static boolean contains(String value, String lowerQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }
}
