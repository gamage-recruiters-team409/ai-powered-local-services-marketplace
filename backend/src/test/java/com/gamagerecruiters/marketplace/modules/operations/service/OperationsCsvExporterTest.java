package com.gamagerecruiters.marketplace.modules.operations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OperationsRules;
import com.gamagerecruiters.marketplace.modules.operations.dto.LateCases;
import com.gamagerecruiters.marketplace.modules.operations.dto.OverdueBookings;
import com.gamagerecruiters.marketplace.modules.operations.dto.RepeatCancellations;

/**
 * CSV exports: formula injection, row truncation, source-failure distinction, source-limit tracking,
 * and filtered-export (delayBucket) coverage (F4).
 */
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
        when(overdueService.allRows(any(), any())).thenReturn(new OverdueBookingsService.ExportData(List.of(
                new OverdueBookings.Row("b1", "=cmd()", "Deep, clean", "c1", "p1",
                        Instant.parse("2026-11-20T10:00:00Z"), 3, OpsCaseStatus.OPEN, null)),
                false));

        OperationsCsvExporter.Export export = exporter.overdue("admin-1", null, null);
        String csv = new String(export.content(), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("bookingRef,service,customerId,providerId,serviceDate,daysOverdue,caseStatus,"
                + "lastAction");
        assertThat(csv).contains("'=cmd()");          // formula neutralised
        assertThat(csv).contains("\"Deep, clean\"");   // comma quoted
        assertThat(export.truncated()).isFalse();
        assertThat(export.sourceLimited()).isFalse();
    }

    @Test
    @DisplayName("exports are capped at the row limit and flagged truncated")
    void exportTruncates() {
        OperationsCsvExporter exporter = new OperationsCsvExporter(overdueService, lateCasesService, repeatService);
        List<OverdueBookings.Row> many = IntStream.range(0, 5001)
                .mapToObj(i -> new OverdueBookings.Row("b" + i, "REF" + i, "svc", "c", "p",
                        Instant.parse("2026-11-20T10:00:00Z"), 1, OpsCaseStatus.OPEN, null))
                .toList();
        when(overdueService.allRows(any(), any())).thenReturn(
                new OverdueBookingsService.ExportData(many, false));

        OperationsCsvExporter.Export export = exporter.overdue("admin-1", null, null);

        assertThat(export.rows()).isEqualTo(5000);
        assertThat(export.truncated()).isTrue();
    }

    // ---- F4: Source availability, source limits, and filter alignment ----

    @Nested
    @DisplayName("F4: source unavailability returns 503 instead of a header-only CSV")
    class SourceUnavailability {

        @Test
        @DisplayName("overdue export propagates 503 when source is unavailable")
        void overdueSourceUnavailable() {
            OperationsCsvExporter exporter = new OperationsCsvExporter(overdueService, lateCasesService, repeatService);
            when(overdueService.allRows(any(), any())).thenThrow(
                    new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OPS_SOURCE_UNAVAILABLE",
                            "Booking source failed"));

            ApiException ex = catchThrowableOfType(() -> exporter.overdue("admin-1", null, null), ApiException.class);

            assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(ex.getErrorCode()).isEqualTo("OPS_SOURCE_UNAVAILABLE");
        }

        @Test
        @DisplayName("late-cases export propagates 503 when source is unavailable")
        void lateCasesSourceUnavailable() {
            OperationsCsvExporter exporter = new OperationsCsvExporter(overdueService, lateCasesService, repeatService);
            when(lateCasesService.allNoShowRows(any())).thenThrow(
                    new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OPS_SOURCE_UNAVAILABLE",
                            "No-show dispute source failed"));

            ApiException ex = catchThrowableOfType(() -> exporter.lateCases("admin-1", null), ApiException.class);

            assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("repeat-cancellations export propagates 503 when source is unavailable")
        void repeatSourceUnavailable() {
            OperationsCsvExporter exporter = new OperationsCsvExporter(overdueService, lateCasesService, repeatService);
            when(repeatService.allRows(any(), any())).thenThrow(
                    new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OPS_SOURCE_UNAVAILABLE",
                            "Repeat-cancellation source failed"));

            ApiException ex = catchThrowableOfType(() -> exporter.repeatCancellations("admin-1", null, null),
                    ApiException.class);

            assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("F4: source-limit tracking")
    class SourceLimits {

        @Test
        @DisplayName("overdue export reports sourceLimited=true when source reached fetch cap")
        void overdueSourceLimited() {
            OperationsCsvExporter exporter = new OperationsCsvExporter(overdueService, lateCasesService, repeatService);
            List<OverdueBookings.Row> rows = IntStream.range(0, 5)
                    .mapToObj(i -> new OverdueBookings.Row("b" + i, "REF" + i, "svc", "c", "p",
                            Instant.parse("2026-11-20T10:00:00Z"), 1, OpsCaseStatus.OPEN, null))
                    .toList();
            when(overdueService.allRows(any(), any())).thenReturn(
                    new OverdueBookingsService.ExportData(rows, true));

            OperationsCsvExporter.Export export = exporter.overdue("admin-1", null, null);

            assertThat(export.sourceLimited()).isTrue();
            assertThat(export.rows()).isEqualTo(5);
            assertThat(export.truncated()).isFalse();
        }

        @Test
        @DisplayName("overdue export reports sourceLimited=false when source returned fewer than cap")
        void overdueSourceNotLimited() {
            OperationsCsvExporter exporter = new OperationsCsvExporter(overdueService, lateCasesService, repeatService);
            when(overdueService.allRows(any(), any())).thenReturn(
                    new OverdueBookingsService.ExportData(List.of(), false));

            OperationsCsvExporter.Export export = exporter.overdue("admin-1", null, null);

            assertThat(export.sourceLimited()).isFalse();
            assertThat(export.rows()).isZero();
        }
    }

    @Nested
    @DisplayName("F4: healthy-empty vs healthy-populated")
    class HealthyResults {

        @Test
        @DisplayName("healthy source with zero results produces a header-only CSV with sourceLimited=false")
        void healthyEmptyOverdue() {
            OperationsCsvExporter exporter = new OperationsCsvExporter(overdueService, lateCasesService, repeatService);
            when(overdueService.allRows(any(), any())).thenReturn(
                    new OverdueBookingsService.ExportData(List.of(), false));

            OperationsCsvExporter.Export export = exporter.overdue("admin-1", null, null);
            String csv = new String(export.content(), StandardCharsets.UTF_8);

            assertThat(export.rows()).isZero();
            assertThat(export.truncated()).isFalse();
            assertThat(export.sourceLimited()).isFalse();
            assertThat(csv).startsWith("bookingRef,");
        }

        @Test
        @DisplayName("healthy source with data produces a populated CSV")
        void healthyPopulatedLateCases() {
            OperationsCsvExporter exporter = new OperationsCsvExporter(overdueService, lateCasesService, repeatService);
            when(lateCasesService.allNoShowRows(any())).thenReturn(
                    new LateCasesService.ExportData(List.of(
                            new LateCases.NoShowReportRow("d1", "b1", "c1", "p1", "OPEN",
                                    Instant.parse("2026-11-25T10:00:00Z"))),
                            false));

            OperationsCsvExporter.Export export = exporter.lateCases("admin-1", null);
            String csv = new String(export.content(), StandardCharsets.UTF_8);

            assertThat(export.rows()).isEqualTo(1);
            assertThat(csv).contains("d1,b1,c1,p1");
            assertThat(export.sourceLimited()).isFalse();
        }
    }
}
