package com.gamagerecruiters.marketplace.modules.operations.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseAction;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsUrgency;
import com.gamagerecruiters.marketplace.modules.operations.domain.OperationsRules;
import com.gamagerecruiters.marketplace.modules.operations.dto.DayCount;
import com.gamagerecruiters.marketplace.modules.operations.dto.LastActionView;
import com.gamagerecruiters.marketplace.modules.operations.dto.LateCases;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsPage;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsSection;
import com.gamagerecruiters.marketplace.modules.operations.port.LateStartsPort;
import com.gamagerecruiters.marketplace.modules.operations.port.NoShowDisputesPort;

/**
 * Builds the Late cases screen (WP09). Two independent sections: late starts (a WP05 stub, normally unavailable — no
 * rows, counts, urgency or per-day chart) and No-show reports (NO_SHOW disputes with their real status).
 */
@Service
public class LateCasesService {

    private static final int PER_DAY_DAYS = 14;
    private static final int NO_SHOW_FETCH_LIMIT = 200;

    private final LateStartsPort lateStartsPort;
    private final NoShowDisputesPort noShowPort;
    private final OpsCaseActionStore actionStore;
    private final Clock clock;

    @Autowired
    public LateCasesService(LateStartsPort lateStartsPort, NoShowDisputesPort noShowPort,
                            OpsCaseActionStore actionStore) {
        this(lateStartsPort, noShowPort, actionStore, Clock.systemUTC());
    }

    LateCasesService(LateStartsPort lateStartsPort, NoShowDisputesPort noShowPort, OpsCaseActionStore actionStore,
                     Clock clock) {
        this.lateStartsPort = lateStartsPort;
        this.noShowPort = noShowPort;
        this.actionStore = actionStore;
        this.clock = clock;
    }

    public LateCases.Response screen(String search, String urgency, int page, int size) {
        return new LateCases.Response(lateStartsSection(search, urgency, page, size), noShowSection(search, page, size));
    }

    OpsSection<LateCases.LateStartsData> lateStartsSection(String search, String urgency, int page, int size) {
        LateStartsPort.Result result = lateStartsPort.findLateStarts(OperationsRules.OVERDUE_FETCH_LIMIT);
        if (!result.available()) {
            return OpsSection.unavailable(result.reason());
        }
        Instant now = clock.instant();
        List<String> ids = result.lateStarts().stream().map(LateStartsPort.LateStart::bookingId).toList();
        Map<String, OpsCaseAction> latest = actionStore.latestBySubject(OpsCaseType.LATE_CASE, ids);

        List<LateCases.LateStartRow> all = result.lateStarts().stream()
                .map(l -> toRow(l, latest.get(l.bookingId())))
                .toList();
        LateCases.LateSummary summary = new LateCases.LateSummary(
                all.stream().filter(r -> r.caseStatus() != OpsCaseStatus.RESOLVED).count(),
                all.stream().filter(r -> r.urgency() == OpsUrgency.CRITICAL).count(),
                actionStore.countResolvedToday(OpsCaseType.LATE_CASE,
                        OperationsTime.todayStart(now), OperationsTime.tomorrowStart(now)));

        List<LateCases.LateStartRow> filtered = all.stream()
                .filter(r -> matches(search, r.ref(), r.serviceTitle(), r.bookingId(), r.customerId(), r.providerId()))
                .filter(r -> urgency == null || urgency.isBlank()
                        || r.urgency().name().equalsIgnoreCase(urgency.strip()))
                .toList();
        OpsPage<LateCases.LateStartRow> rows = OpsPage.of(filtered,
                OperationsRules.clampPage(page), OperationsRules.clampSize(size));
        return OpsSection.of(new LateCases.LateStartsData(rows, summary, perDay(all, now)));
    }

    /** All No-show report rows (unpaged), used by the CSV export. Empty when the source is unavailable. */
    List<LateCases.NoShowReportRow> allNoShowRows(String search) {
        NoShowDisputesPort.Result result = noShowPort.findNoShowDisputes(NO_SHOW_FETCH_LIMIT);
        if (!result.available()) {
            return List.of();
        }
        return result.disputes().stream()
                .map(d -> new LateCases.NoShowReportRow(d.disputeId(), d.bookingId(), d.customerId(), d.providerId(),
                        d.status(), d.createdAt()))
                .filter(r -> matches(search, r.disputeId(), r.bookingId(), r.customerId(), r.providerId(), r.status()))
                .toList();
    }

    OpsSection<LateCases.NoShowReportsData> noShowSection(String search, int page, int size) {
        NoShowDisputesPort.Result result = noShowPort.findNoShowDisputes(NO_SHOW_FETCH_LIMIT);
        if (!result.available()) {
            return OpsSection.unavailable(result.reason());
        }
        List<LateCases.NoShowReportRow> all = result.disputes().stream()
                .map(d -> new LateCases.NoShowReportRow(d.disputeId(), d.bookingId(), d.customerId(), d.providerId(),
                        d.status(), d.createdAt()))
                .toList();
        Map<String, Long> byStatus = all.stream()
                .collect(Collectors.groupingBy(r -> r.status() == null ? "UNKNOWN" : r.status(),
                        Collectors.counting()));
        LateCases.NoShowCounts counts = new LateCases.NoShowCounts(all.size(), new TreeMap<>(byStatus));

        List<LateCases.NoShowReportRow> filtered = all.stream()
                .filter(r -> matches(search, r.disputeId(), r.bookingId(), r.customerId(), r.providerId(), r.status()))
                .toList();
        OpsPage<LateCases.NoShowReportRow> rows = OpsPage.of(filtered,
                OperationsRules.clampPage(page), OperationsRules.clampSize(size));
        return OpsSection.of(new LateCases.NoShowReportsData(rows, counts));
    }

    private LateCases.LateStartRow toRow(LateStartsPort.LateStart l, OpsCaseAction latest) {
        OpsCaseStatus status = latest == null ? OpsCaseStatus.OPEN : latest.getCaseStatus();
        return new LateCases.LateStartRow(l.bookingId(), l.ref(), l.serviceTitle(), l.customerId(), l.providerId(),
                l.scheduledStart(), l.minutesLate(), OpsUrgency.of(l.minutesLate()), status, LastActionView.from(latest));
    }

    private List<DayCount> perDay(List<LateCases.LateStartRow> rows, Instant now) {
        LocalDate today = OperationsTime.today(now);
        Map<LocalDate, Long> counts = rows.stream()
                .filter(r -> r.scheduledStart() != null)
                .collect(Collectors.groupingBy(
                        r -> r.scheduledStart().atZone(OperationsRules.ZONE).toLocalDate(), Collectors.counting()));
        List<DayCount> perDay = new java.util.ArrayList<>(PER_DAY_DAYS);
        for (int i = PER_DAY_DAYS - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            perDay.add(new DayCount(day, counts.getOrDefault(day, 0L)));
        }
        return perDay;
    }

    private static boolean matches(String search, String... fields) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String q = search.strip().toLowerCase(Locale.ROOT);
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }
}
