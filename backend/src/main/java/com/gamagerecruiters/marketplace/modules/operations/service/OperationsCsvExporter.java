package com.gamagerecruiters.marketplace.modules.operations.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.operations.domain.OperationsRules;
import com.gamagerecruiters.marketplace.modules.operations.dto.LastActionView;
import com.gamagerecruiters.marketplace.modules.operations.dto.LateCases;
import com.gamagerecruiters.marketplace.modules.operations.dto.OverdueBookings;
import com.gamagerecruiters.marketplace.modules.operations.dto.RepeatCancellations;

/**
 * CSV exports for the operations screens (WP09). Formula-injection safe (same cell rule as the Admin Users exporter),
 * capped at {@link OperationsRules#CSV_MAX_ROWS} rows, and carrying ids / refs and already-shown fields only — never
 * emails or phone numbers. Logs ids and counts only.
 */
@Service
public class OperationsCsvExporter {

    /** A leading one of these makes a spreadsheet treat the cell as a formula; we prefix a quote to neutralise it. */
    private static final String FORMULA_START = "=+-@\t\r";

    private static final Logger log = LoggerFactory.getLogger(OperationsCsvExporter.class);

    private final OverdueBookingsService overdueService;
    private final LateCasesService lateCasesService;
    private final RepeatCancellationsService repeatService;

    public OperationsCsvExporter(OverdueBookingsService overdueService, LateCasesService lateCasesService,
                                 RepeatCancellationsService repeatService) {
        this.overdueService = overdueService;
        this.lateCasesService = lateCasesService;
        this.repeatService = repeatService;
    }

    public record Export(byte[] content, String filename, int rows, boolean truncated) {
    }

    public Export overdue(String actorId, String search) {
        List<OverdueBookings.Row> all = overdueService.allRows(search);
        return build(actorId, "overdue-bookings.csv",
                "bookingRef,service,customerId,providerId,serviceDate,daysOverdue,caseStatus,lastAction",
                all, r -> String.join(",",
                        cell(r.ref()), cell(r.serviceTitle()), cell(r.customerId()), cell(r.providerId()),
                        cell(iso(r.serviceDate())), cell(String.valueOf(r.daysOverdue())),
                        cell(String.valueOf(r.caseStatus())), cell(lastAction(r.lastAction()))));
    }

    public Export lateCases(String actorId, String search) {
        List<LateCases.NoShowReportRow> all = lateCasesService.allNoShowRows(search);
        return build(actorId, "late-cases-no-show-reports.csv",
                "disputeId,bookingId,customerId,providerId,disputeStatus,createdAt",
                all, r -> String.join(",",
                        cell(r.disputeId()), cell(r.bookingId()), cell(r.customerId()), cell(r.providerId()),
                        cell(r.status()), cell(iso(r.createdAt()))));
    }

    public Export repeatCancellations(String actorId, String search, String role) {
        List<RepeatCancellations.Row> all = repeatService.allRows(search, role);
        return build(actorId, "repeat-cancellations.csv",
                "userId,role,cancellations,lastCancelled,caseStatus,lastAction",
                all, r -> String.join(",",
                        cell(r.userId()), cell(r.role()), cell(String.valueOf(r.count())),
                        cell(iso(r.lastCancelledAt())), cell(String.valueOf(r.caseStatus())),
                        cell(lastAction(r.lastAction()))));
    }

    private <T> Export build(String actorId, String filename, String header, List<T> all, Function<T, String> toLine) {
        boolean truncated = all.size() > OperationsRules.CSV_MAX_ROWS;
        List<T> rows = truncated ? all.subList(0, OperationsRules.CSV_MAX_ROWS) : all;
        StringBuilder csv = new StringBuilder(header).append("\r\n");
        for (T row : rows) {
            csv.append(toLine.apply(row)).append("\r\n");
        }
        log.info("event=OPS_EXPORTED actorId={} file={} rows={} truncated={}", actorId, filename, rows.size(),
                truncated);
        return new Export(csv.toString().getBytes(StandardCharsets.UTF_8), filename, rows.size(), truncated);
    }

    private static String lastAction(LastActionView lastAction) {
        return lastAction == null ? "" : String.valueOf(lastAction.action());
    }

    private static String iso(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    /** One CSV cell, safe against formula injection (RFC 4180 quoting). */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = FORMULA_START.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
