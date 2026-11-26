package com.gamagerecruiters.marketplace.modules.operations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
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

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsSection;
import com.gamagerecruiters.marketplace.modules.operations.dto.OverdueBookings;
import com.gamagerecruiters.marketplace.modules.operations.port.OverdueBookingsPort;

@ExtendWith(MockitoExtension.class)
class OverdueBookingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    @Mock
    private OverdueBookingsPort port;
    @Mock
    private OpsCaseActionStore store;

    private OverdueBookingsService service;

    @BeforeEach
    void setUp() {
        service = new OverdueBookingsService(port, store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OverdueBookingsPort.OverdueBooking booking(String id, long hoursAgo) {
        Instant slotEnd = NOW.minus(Duration.ofHours(hoursAgo));
        return new OverdueBookingsPort.OverdueBooking(id, "REF-" + id, "Cleaning " + id, "cust-" + id, "prov-" + id,
                slotEnd.minus(Duration.ofHours(1)), slotEnd);
    }

    @Test
    @DisplayName("builds rows, days overdue, summary and distribution from the real source")
    void screenBuildsData() {
        when(port.findOverdue(anyInt())).thenReturn(OverdueBookingsPort.Result.available(List.of(
                booking("A", 10),   // 0 days
                booking("B", 30),   // 1 day
                booking("C", 50),   // 2 days
                booking("D", 100))));  // 4 days
        when(store.latestBySubject(any(), any())).thenReturn(Map.of());
        when(store.countResolvedToday(any(), any(), any())).thenReturn(0L);

        OpsSection<OverdueBookings.Data> section = service.screen(null, null, 0, 20);

        assertThat(section.available()).isTrue();
        OverdueBookings.Data data = section.data();
        assertThat(data.summary().overdueNow()).isEqualTo(4);
        assertThat(data.summary().over24h()).isEqualTo(3); // B, C, D
        assertThat(data.distribution().oneDay()).isEqualTo(2);   // A(0), B(1)
        assertThat(data.distribution().twoDays()).isEqualTo(1);  // C
        assertThat(data.distribution().threeDays()).isZero();
        assertThat(data.distribution().fourPlusDays()).isEqualTo(1); // D
        assertThat(data.rows().content()).hasSize(4);
        assertThat(data.rows().content().get(0).caseStatus()).isEqualTo(OpsCaseStatus.OPEN);
    }

    @Test
    @DisplayName("delay bucket and search filter the rows but not the summary")
    void filtersRows() {
        when(port.findOverdue(anyInt())).thenReturn(OverdueBookingsPort.Result.available(List.of(
                booking("A", 10), booking("C", 50), booking("D", 100))));
        when(store.latestBySubject(any(), any())).thenReturn(Map.of());
        when(store.countResolvedToday(any(), any(), any())).thenReturn(0L);

        OpsSection<OverdueBookings.Data> byBucket = service.screen(null, "2", 0, 20);
        assertThat(byBucket.data().rows().content()).extracting(OverdueBookings.Row::bookingId).containsExactly("C");
        assertThat(byBucket.data().summary().overdueNow()).isEqualTo(3); // summary unaffected by filter

        OpsSection<OverdueBookings.Data> bySearch = service.screen("REF-D", null, 0, 20);
        assertThat(bySearch.data().rows().content()).extracting(OverdueBookings.Row::bookingId).containsExactly("D");
    }

    @Test
    @DisplayName("an unavailable source yields available=false with a reason and no data")
    void unavailableSource() {
        when(port.findOverdue(anyInt()))
                .thenReturn(OverdueBookingsPort.Result.unavailable("WP05 method missing"));

        OpsSection<OverdueBookings.Data> section = service.screen(null, null, 0, 20);

        assertThat(section.available()).isFalse();
        assertThat(section.reason()).isEqualTo("WP05 method missing");
        assertThat(section.data()).isNull();
    }

}
