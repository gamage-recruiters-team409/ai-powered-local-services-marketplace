package com.gamagerecruiters.marketplace.modules.operations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActionType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActorArea;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseAction;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsSection;
import com.gamagerecruiters.marketplace.modules.operations.dto.RepeatCancellations;
import com.gamagerecruiters.marketplace.modules.operations.port.RepeatCancellationsPort;

@ExtendWith(MockitoExtension.class)
class RepeatCancellationsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    @Mock
    private RepeatCancellationsPort port;
    @Mock
    private OpsCaseActionStore store;

    private RepeatCancellationsService service;

    @BeforeEach
    void setUp() {
        service = new RepeatCancellationsService(port, store, 4, 30);
    }

    private static RepeatCancellationsPort.RepeatCanceller canceller(String id, String role, long count) {
        return new RepeatCancellationsPort.RepeatCanceller(id, role, count, NOW);
    }

    @Test
    @DisplayName("uses the configured threshold (min-count, days) when querying the source")
    void thresholdFromConfig() {
        when(port.findRepeatCancellations(4, 30)).thenReturn(RepeatCancellationsPort.Result.available(List.of()));
        when(store.currentStatus(any(), any(), anyInt())).thenReturn(List.of());

        OpsSection<RepeatCancellations.Data> section = service.screen(null, null, 0, 20);

        verify(port).findRepeatCancellations(4, 30);
        assertThat(section.data().thresholdMinCount()).isEqualTo(4);
        assertThat(section.data().thresholdDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("summary counts, top 5 by count, and pending suspension recommendations")
    void screenBuildsData() {
        when(port.findRepeatCancellations(anyInt(), anyInt())).thenReturn(RepeatCancellationsPort.Result.available(
                List.of(canceller("u1", "CUSTOMER", 9), canceller("u2", "PROVIDER", 4),
                        canceller("u3", "CUSTOMER", 7))));
        when(store.latestBySubject(any(), any())).thenReturn(Map.of());
        OpsCaseAction rec = new OpsCaseAction(OpsCaseType.REPEAT_CANCELLATION, "u1", OpsActionType.RECOMMEND_SUSPENSION,
                "Recommend suspension after repeated cancellations.", "support-1", OpsActorArea.SUPPORT_ADMIN, NOW,
                OpsCaseStatus.SUSPENSION_RECOMMENDED);
        when(store.currentStatus(any(), any(), anyInt())).thenReturn(List.of(rec));

        RepeatCancellations.Data data = service.screen(null, null, 0, 20).data();

        assertThat(data.summary().flagged()).isEqualTo(3);
        assertThat(data.summary().customers()).isEqualTo(2);
        assertThat(data.summary().providers()).isEqualTo(1);
        assertThat(data.top5()).extracting(RepeatCancellations.Row::userId).containsExactly("u1", "u3", "u2");
        assertThat(data.suspensionRecommendations()).hasSize(1);
        assertThat(data.suspensionRecommendations().get(0).recommendedByActorId()).isEqualTo("support-1");
    }

    @Test
    @DisplayName("an unavailable source yields available=false with a reason and no data")
    void unavailableSource() {
        when(port.findRepeatCancellations(anyInt(), anyInt()))
                .thenReturn(RepeatCancellationsPort.Result.unavailable("WP05 method missing"));

        OpsSection<RepeatCancellations.Data> section = service.screen(null, null, 0, 20);

        assertThat(section.available()).isFalse();
        assertThat(section.reason()).isEqualTo("WP05 method missing");
    }
}
