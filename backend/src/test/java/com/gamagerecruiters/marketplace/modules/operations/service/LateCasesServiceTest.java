package com.gamagerecruiters.marketplace.modules.operations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsUrgency;
import com.gamagerecruiters.marketplace.modules.operations.dto.LateCases;
import com.gamagerecruiters.marketplace.modules.operations.port.LateStartsPort;
import com.gamagerecruiters.marketplace.modules.operations.port.NoShowDisputesPort;

@ExtendWith(MockitoExtension.class)
class LateCasesServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    @Mock
    private LateStartsPort lateStartsPort;
    @Mock
    private NoShowDisputesPort noShowPort;
    @Mock
    private OpsCaseActionStore store;

    private LateCasesService service;

    @BeforeEach
    void setUp() {
        service = new LateCasesService(lateStartsPort, noShowPort, store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("late starts stub: unavailable section with no rows, counts, urgency or per-day chart")
    void lateStartsUnavailable() {
        when(lateStartsPort.findLateStarts(anyInt()))
                .thenReturn(LateStartsPort.Result.unavailable("Late-start tracking is not available yet (WP05)"));
        when(noShowPort.findNoShowDisputes(anyInt())).thenReturn(NoShowDisputesPort.Result.available(List.of()));

        LateCases.Response response = service.screen(null, null, 0, 20);

        assertThat(response.lateStarts().available()).isFalse();
        assertThat(response.lateStarts().reason()).contains("WP05");
        assertThat(response.lateStarts().data()).isNull();
        assertThat(response.noShowReports().available()).isTrue();
    }

    @Test
    @DisplayName("no-show reports list NO_SHOW disputes with real status and own counts")
    void noShowCounts() {
        when(lateStartsPort.findLateStarts(anyInt())).thenReturn(LateStartsPort.Result.unavailable("stub"));
        when(noShowPort.findNoShowDisputes(anyInt())).thenReturn(NoShowDisputesPort.Result.available(List.of(
                new NoShowDisputesPort.NoShowDispute("d1", "b1", "c1", "p1", "UNDER_REVIEW", NOW),
                new NoShowDisputesPort.NoShowDispute("d2", "b2", "c2", "p2", "UNDER_REVIEW", NOW),
                new NoShowDisputesPort.NoShowDispute("d3", "b3", "c3", "p3", "RESOLVED", NOW))));

        LateCases.NoShowReportsData data = service.screen(null, null, 0, 20).noShowReports().data();

        assertThat(data.counts().total()).isEqualTo(3);
        assertThat(data.counts().byStatus()).containsEntry("UNDER_REVIEW", 2L).containsEntry("RESOLVED", 1L);
        assertThat(data.rows().content()).extracting(LateCases.NoShowReportRow::status)
                .containsExactly("UNDER_REVIEW", "UNDER_REVIEW", "RESOLVED");
    }

    @Test
    @DisplayName("when late starts are available, urgency buckets and a 14-day chart are produced")
    void lateStartsAvailableComputesUrgencyAndPerDay() {
        when(lateStartsPort.findLateStarts(anyInt())).thenReturn(LateStartsPort.Result.available(List.of(
                lateStart("b1", 30),    // MEDIUM (<2h)
                lateStart("b2", 150),   // HIGH (2-4h)
                lateStart("b3", 300))));   // CRITICAL (>4h)
        when(noShowPort.findNoShowDisputes(anyInt())).thenReturn(NoShowDisputesPort.Result.available(List.of()));
        when(store.latestBySubject(any(), any())).thenReturn(Map.of());
        when(store.countResolvedToday(any(), any(), any())).thenReturn(0L);

        LateCases.LateStartsData data = service.screen(null, null, 0, 20).lateStarts().data();

        assertThat(data.rows().content()).extracting(LateCases.LateStartRow::urgency)
                .containsExactly(OpsUrgency.MEDIUM, OpsUrgency.HIGH, OpsUrgency.CRITICAL);
        assertThat(data.summary().critical()).isEqualTo(1);
        assertThat(data.perDay()).hasSize(14);
    }

    private static LateStartsPort.LateStart lateStart(String id, long minutesLate) {
        return new LateStartsPort.LateStart(id, "REF-" + id, "Service " + id, "c-" + id, "p-" + id,
                NOW.minusSeconds(minutesLate * 60), minutesLate);
    }
}
