package com.gamagerecruiters.marketplace.modules.operations.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
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
import com.gamagerecruiters.marketplace.modules.operations.dto.RepeatCancellations;
import com.gamagerecruiters.marketplace.modules.operations.port.RepeatCancellationsPort;

/**
 * Builds the Repeat cancellations screen (WP09). The threshold comes from config
 * {@code app.operations.repeat-cancellation.min-count} (default 4) / {@code .days} (default 30), since no Super Admin
 * platform-settings feature exists. No cancellation "rate %" is shown (WP05 does not expose period totals).
 */
@Service
public class RepeatCancellationsService {

    private static final int RECOMMENDATION_LIMIT = 100;

    private final RepeatCancellationsPort repeatPort;
    private final OpsCaseActionStore actionStore;
    private final int minCount;
    private final int days;

    public RepeatCancellationsService(RepeatCancellationsPort repeatPort, OpsCaseActionStore actionStore,
                                      @Value("${app.operations.repeat-cancellation.min-count:4}") int minCount,
                                      @Value("${app.operations.repeat-cancellation.days:30}") int days) {
        this.repeatPort = repeatPort;
        this.actionStore = actionStore;
        this.minCount = minCount < 1 ? 4 : minCount;
        this.days = days < 1 ? 30 : days;
    }

    int thresholdMinCount() {
        return minCount;
    }

    int thresholdDays() {
        return days;
    }

    /** Export rows, distinguishing source unavailability from a valid empty set. */
    record ExportData(List<RepeatCancellations.Row> rows, boolean sourceLimited) {}

    /**
     * All flagged rows (unpaged), used by the CSV export. Throws 503 when the source is unavailable so the
     * export endpoint returns an error instead of a successful header-only CSV.
     */
    ExportData allRows(String search, String role) {
        RepeatCancellationsPort.Result result =
                repeatPort.findRepeatCancellations(minCount, days);
        if (!result.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OPS_SOURCE_UNAVAILABLE", result.reason());
        }
        boolean sourceLimited = result.cancellers().size() >= OperationsRules.REPEAT_FETCH_LIMIT;
        List<String> ids = result.cancellers().stream().map(RepeatCancellationsPort.RepeatCanceller::userId).toList();
        Map<String, OpsCaseAction> latest = actionStore.latestBySubject(OpsCaseType.REPEAT_CANCELLATION, ids);
        List<RepeatCancellations.Row> rows = result.cancellers().stream()
                .map(c -> toRow(c, latest.get(c.userId())))
                .filter(r -> matches(search, r.userId(), r.role()))
                .filter(r -> role == null || role.isBlank() || r.role().equalsIgnoreCase(role.strip()))
                .toList();
        return new ExportData(rows, sourceLimited);
    }

    public OpsSection<RepeatCancellations.Data> screen(String search, String role, int page, int size) {
        RepeatCancellationsPort.Result result = repeatPort.findRepeatCancellations(minCount, days);
        if (!result.available()) {
            return OpsSection.unavailable(result.reason());
        }
        List<String> ids = result.cancellers().stream().map(RepeatCancellationsPort.RepeatCanceller::userId).toList();
        Map<String, OpsCaseAction> latest = actionStore.latestBySubject(OpsCaseType.REPEAT_CANCELLATION, ids);
        List<RepeatCancellations.Row> all = result.cancellers().stream()
                .map(c -> toRow(c, latest.get(c.userId())))
                .toList();

        RepeatCancellations.Summary summary = new RepeatCancellations.Summary(
                all.size(),
                all.stream().filter(r -> "CUSTOMER".equalsIgnoreCase(r.role())).count(),
                all.stream().filter(r -> "PROVIDER".equalsIgnoreCase(r.role())).count());
        List<RepeatCancellations.Row> top5 = all.stream()
                .sorted(Comparator.comparingLong(RepeatCancellations.Row::count).reversed())
                .limit(5)
                .toList();

        List<RepeatCancellations.Row> filtered = all.stream()
                .filter(r -> matches(search, r.userId(), r.role()))
                .filter(r -> role == null || role.isBlank() || r.role().equalsIgnoreCase(role.strip()))
                .toList();
        OpsPage<RepeatCancellations.Row> rows = OpsPage.of(filtered,
                OperationsRules.clampPage(page), OperationsRules.clampSize(size));

        return OpsSection.of(new RepeatCancellations.Data(rows, summary, top5, recommendations(), minCount, days));
    }

    private List<RepeatCancellations.SuspensionRecommendation> recommendations() {
        return actionStore.currentStatus(OpsCaseType.REPEAT_CANCELLATION, OpsCaseStatus.SUSPENSION_RECOMMENDED,
                        RECOMMENDATION_LIMIT).stream()
                .map(a -> new RepeatCancellations.SuspensionRecommendation(a.getSubjectId(), a.getActorId(),
                        a.getNote(), a.getCreatedAt()))
                .toList();
    }

    private RepeatCancellations.Row toRow(RepeatCancellationsPort.RepeatCanceller c, OpsCaseAction latest) {
        OpsCaseStatus status = latest == null ? OpsCaseStatus.OPEN : latest.getCaseStatus();
        return new RepeatCancellations.Row(c.userId(), c.role(), c.count(), c.lastCancelledAt(), status,
                LastActionView.from(latest));
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
