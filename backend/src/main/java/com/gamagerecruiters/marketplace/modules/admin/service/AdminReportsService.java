package com.gamagerecruiters.marketplace.modules.admin.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminReportResponse;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;

/**
 * Super Admin Reports service (SRS 13.3 / 13.5).
 * Generates aggregated report data and safe CSV exports.
 */
@Service
public class AdminReportsService {

    private static final Logger log = LoggerFactory.getLogger(AdminReportsService.class);

    private static final String FORMULA_START = "=+-@\t\r";
    private static final String BOM = "\uFEFF";

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    @Autowired
    public AdminReportsService(MongoTemplate mongoTemplate) {
        this(mongoTemplate, Clock.systemUTC());
    }

    public AdminReportsService(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    public record CsvExport(byte[] bytes, String filename, int rows) {}

    public AdminReportResponse generateReport(String type, LocalDate from, LocalDate to, int page, int size) {
        String normalizedType = normalizeType(type);

        if ("disputes".equals(normalizedType)) {
            return generateDisputesReport(from, to, page, size);
        }

        return getUnavailableReportResponse(normalizedType);
    }

    public CsvExport exportReportCsv(String actorId, String type, LocalDate from, LocalDate to) {
        String normalizedType = normalizeType(type);

        if (!"disputes".equals(normalizedType)) {
            AdminReportResponse unavail = getUnavailableReportResponse(normalizedType);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REPORT_SOURCE_UNAVAILABLE",
                    unavail.reason() != null ? unavail.reason() : "Report source is not available yet");
        }

        return exportDisputesCsv(actorId, from, to);
    }

    private AdminReportResponse generateDisputesReport(LocalDate from, LocalDate to, int page, int size) {
        LocalDate effectiveTo = to != null ? to : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(30);

        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "from date must not be after to date");
        }

        List<Dispute> disputes = fetchVisibleDisputes(effectiveFrom, effectiveTo);

        // Group by Date (YYYY-MM-DD)
        Map<String, DayAggregator> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (Dispute d : disputes) {
            String dateStr = DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDate.ofInstant(d.getCreatedAt(), ZoneOffset.UTC));
            byDate.computeIfAbsent(dateStr, DayAggregator::new).add(d);
        }

        List<Map<String, Object>> allRows = new ArrayList<>();
        long totalOpened = 0;
        long totalResolved = 0;
        long totalUnderReview = 0;
        long totalFullRefund = 0;
        long totalPartialRefund = 0;
        long totalNoRefund = 0;
        double sumResolutionHours = 0;
        long countWithResolutionHours = 0;

        for (DayAggregator agg : byDate.values()) {
            Map<String, Object> row = agg.toMap();
            allRows.add(row);

            totalOpened += agg.opened;
            totalResolved += agg.resolved;
            totalUnderReview += agg.underReview;
            totalFullRefund += agg.fullRefund;
            totalPartialRefund += agg.partialRefund;
            totalNoRefund += agg.noRefund;
            if (agg.resolvedWithDurationCount > 0) {
                sumResolutionHours += agg.totalResolutionHours;
                countWithResolutionHours += agg.resolvedWithDurationCount;
            }
        }

        double overallAvgResolutionHours = countWithResolutionHours > 0
                ? Math.round((sumResolutionHours / countWithResolutionHours) * 10.0) / 10.0
                : 0.0;

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("totalOpened", totalOpened);
        totals.put("totalResolved", totalResolved);
        totals.put("totalUnderReview", totalUnderReview);
        totals.put("totalFullRefund", totalFullRefund);
        totals.put("totalPartialRefund", totalPartialRefund);
        totals.put("totalNoRefund", totalNoRefund);
        totals.put("avgResolutionHours", overallAvgResolutionHours);

        int validPage = Math.max(0, page);
        int validSize = size > 0 ? size : 20;
        int totalElements = allRows.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalElements / validSize));
        int fromIndex = Math.min(validPage * validSize, totalElements);
        int toIndex = Math.min(fromIndex + validSize, totalElements);

        List<Map<String, Object>> pageRows = allRows.subList(fromIndex, toIndex);

        return AdminReportResponse.available(
                "disputes",
                "DisputeService",
                pageRows,
                totals,
                validPage,
                validSize,
                totalElements,
                totalPages
        );
    }

    private CsvExport exportDisputesCsv(String actorId, LocalDate from, LocalDate to) {
        AdminReportResponse report = generateDisputesReport(from, to, 0, 10000);

        StringBuilder sb = new StringBuilder(BOM);
        sb.append("Date,Opened,Resolved,Under Review,Full Refund,Partial Refund,No Refund,Avg Resolution Time (Hours)\r\n");

        for (Map<String, Object> r : report.rows()) {
            sb.append(cell(String.valueOf(r.get("date")))).append(',')
                    .append(cell(String.valueOf(r.get("opened")))).append(',')
                    .append(cell(String.valueOf(r.get("resolved")))).append(',')
                    .append(cell(String.valueOf(r.get("underReview")))).append(',')
                    .append(cell(String.valueOf(r.get("fullRefund")))).append(',')
                    .append(cell(String.valueOf(r.get("partialRefund")))).append(',')
                    .append(cell(String.valueOf(r.get("noRefund")))).append(',')
                    .append(cell(String.valueOf(r.get("avgResolutionHours"))))
                    .append("\r\n");
        }

        Map<String, Object> totals = report.totals();
        sb.append("Total,")
                .append(cell(String.valueOf(totals.get("totalOpened")))).append(',')
                .append(cell(String.valueOf(totals.get("totalResolved")))).append(',')
                .append(cell(String.valueOf(totals.get("totalUnderReview")))).append(',')
                .append(cell(String.valueOf(totals.get("totalFullRefund")))).append(',')
                .append(cell(String.valueOf(totals.get("totalPartialRefund")))).append(',')
                .append(cell(String.valueOf(totals.get("totalNoRefund")))).append(',')
                .append(cell(String.valueOf(totals.get("avgResolutionHours"))))
                .append("\r\n");

        String filename = "disputes-report-" + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE) + ".csv";

        log.info("event=DISPUTES_REPORT_EXPORTED actorId={} rows={}", actorId, report.rows().size());
        return new CsvExport(sb.toString().getBytes(StandardCharsets.UTF_8), filename, report.rows().size());
    }

    private List<Dispute> fetchVisibleDisputes(LocalDate from, LocalDate to) {
        Criteria criteria = Criteria.where("bookingSyncState").ne(BookingSyncState.PENDING);
        Criteria dateCrit = Criteria.where("createdAt");
        if (from != null) {
            dateCrit = dateCrit.gte(from.atStartOfDay().toInstant(ZoneOffset.UTC));
        }
        if (to != null) {
            dateCrit = dateCrit.lt(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        }

        Query query = Query.query(new Criteria().andOperator(criteria, dateCrit));
        return mongoTemplate.find(query, Dispute.class);
    }

    private static AdminReportResponse getUnavailableReportResponse(String type) {
        return switch (type) {
            case "bookings-summary" -> AdminReportResponse.unavailable(
                    "bookings-summary", "WP05 Booking System", "Bookings summary report connects once WP05 Booking integration is available");
            case "transactions" -> AdminReportResponse.unavailable(
                    "transactions", "WP07 Payment Gateway", "Transactions report connects once WP07 Payments integration is available");
            case "payouts" -> AdminReportResponse.unavailable(
                    "payouts", "WP07 Payment Gateway", "Payouts report connects once WP07 Payments integration is available");
            case "categories" -> AdminReportResponse.unavailable(
                    "categories", "WP03 Catalog Module", "Category analytics report connects once WP03 Catalog integration is available");
            case "provider-performance" -> AdminReportResponse.unavailable(
                    "provider-performance", "WP05 Booking System", "Provider performance report connects once WP05 Booking integration is available");
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_REPORT_TYPE", "Unknown report type: " + type);
        };
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REPORT_TYPE_REQUIRED", "Report type is required");
        }
        return type.strip().toLowerCase(Locale.ROOT);
    }

    /** One CSV cell, safe against formula injection (RFC 4180). */
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

    private static class DayAggregator {
        final String date;
        long opened = 0;
        long resolved = 0;
        long underReview = 0;
        long fullRefund = 0;
        long partialRefund = 0;
        long noRefund = 0;
        double totalResolutionHours = 0;
        long resolvedWithDurationCount = 0;

        DayAggregator(String date) {
            this.date = date;
        }

        void add(Dispute d) {
            opened++;
            DisputeStatus status = d.getStatus();
            if (status == DisputeStatus.RESOLVED || status == DisputeStatus.RESOLVED_FINAL || status == DisputeStatus.CLOSED) {
                resolved++;
                DisputeDecision dec = d.getFinalDecision() != null ? d.getFinalDecision() : d.getDecision();
                if (dec != null && dec.getAction() != null) {
                    ResolutionAction action = dec.getAction();
                    if (action == ResolutionAction.REFUND_FULL) fullRefund++;
                    else if (action == ResolutionAction.REFUND_PARTIAL) partialRefund++;
                    else if (action == ResolutionAction.RELEASE_TO_PROVIDER) noRefund++;
                }

                Instant end = d.getFinalizedAt() != null ? d.getFinalizedAt()
                        : (dec != null && dec.getDecidedAt() != null ? dec.getDecidedAt() : null);
                if (end != null && d.getCreatedAt() != null && !end.isBefore(d.getCreatedAt())) {
                    double hours = Duration.between(d.getCreatedAt(), end).toMinutes() / 60.0;
                    totalResolutionHours += hours;
                    resolvedWithDurationCount++;
                }
            } else {
                underReview++;
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", date);
            map.put("opened", opened);
            map.put("resolved", resolved);
            map.put("underReview", underReview);
            map.put("fullRefund", fullRefund);
            map.put("partialRefund", partialRefund);
            map.put("noRefund", noRefund);
            double avgHours = resolvedWithDurationCount > 0
                    ? Math.round((totalResolutionHours / resolvedWithDurationCount) * 10.0) / 10.0
                    : 0.0;
            map.put("avgResolutionHours", avgHours);
            return map;
        }
    }
}
