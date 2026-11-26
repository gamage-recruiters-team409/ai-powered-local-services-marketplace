package com.gamagerecruiters.marketplace.modules.operations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.dto.OverdueBookings;

@ExtendWith(MockitoExtension.class)
class OperationsCsvExporterTest {

    @Mock
    private OverdueBookingsService overdueService;
    @Mock
    private LateCasesService lateCasesService;
    @Mock
    private RepeatCancellationsService repeatService;

    @Test
    @DisplayName("cell() neutralises formula injection and quotes separators")
    void cellEscaping() {
        assertThat(OperationsCsvExporter.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(OperationsCsvExporter.cell("+1")).isEqualTo("'+1");
        assertThat(OperationsCsvExporter.cell("-2")).isEqualTo("'-2");
        assertThat(OperationsCsvExporter.cell("@x")).isEqualTo("'@x");
        assertThat(OperationsCsvExporter.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(OperationsCsvExporter.cell("she said \"hi\"")).isEqualTo("\"she said \"\"hi\"\"\"");
        assertThat(OperationsCsvExporter.cell("plain")).isEqualTo("plain");
        assertThat(OperationsCsvExporter.cell(null)).isEmpty();
    }

    @Test
    @DisplayName("overdue export writes a header and escapes a malicious ref")
    void overdueExportEscapes() {
        OperationsCsvExporter exporter = new OperationsCsvExporter(overdueService, lateCasesService, repeatService);
        when(overdueService.allRows(any())).thenReturn(List.of(new OverdueBookings.Row(
                "b1", "=cmd()", "Deep, clean", "c1", "p1", Instant.parse("2026-11-20T10:00:00Z"), 3,
                OpsCaseStatus.OPEN, null)));

        OperationsCsvExporter.Export export = exporter.overdue("admin-1", null);
        String csv = new String(export.content(), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("bookingRef,service,customerId,providerId,serviceDate,daysOverdue,caseStatus,"
                + "lastAction");
        assertThat(csv).contains("'=cmd()");          // formula neutralised
        assertThat(csv).contains("\"Deep, clean\"");  // comma quoted
        assertThat(export.truncated()).isFalse();
    }

    @Test
    @DisplayName("exports are capped at the row limit and flagged truncated")
    void exportTruncates() {
        OperationsCsvExporter exporter = new OperationsCsvExporter(overdueService, lateCasesService, repeatService);
        List<OverdueBookings.Row> many = IntStream.range(0, 5001)
                .mapToObj(i -> new OverdueBookings.Row("b" + i, "REF" + i, "svc", "c", "p",
                        Instant.parse("2026-11-20T10:00:00Z"), 1, OpsCaseStatus.OPEN, null))
                .toList();
        when(overdueService.allRows(any())).thenReturn(many);

        OperationsCsvExporter.Export export = exporter.overdue("admin-1", null);

        assertThat(export.rows()).isEqualTo(5000);
        assertThat(export.truncated()).isTrue();
    }
}
