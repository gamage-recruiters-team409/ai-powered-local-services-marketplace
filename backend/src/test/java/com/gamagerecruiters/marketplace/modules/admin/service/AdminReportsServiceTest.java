package com.gamagerecruiters.marketplace.modules.admin.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminReportResponse;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;

@ExtendWith(MockitoExtension.class)
class AdminReportsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private MongoTemplate mongoTemplate;

    private AdminReportsService service;

    @BeforeEach
    void setUp() {
        service = new AdminReportsService(mongoTemplate, CLOCK);
    }

    @Test
    @DisplayName("disputes report aggregates volume, outcomes and avg resolution time")
    void disputesReportAggregatesAccurately() {
        Instant created1 = Instant.parse("2026-11-20T08:00:00Z");
        Instant resolved1 = Instant.parse("2026-11-20T14:00:00Z"); // 6 hours

        Dispute d1 = new Dispute();
        org.springframework.test.util.ReflectionTestUtils.setField(d1, "createdAt", created1);
        org.springframework.test.util.ReflectionTestUtils.setField(d1, "status", DisputeStatus.RESOLVED);
        org.springframework.test.util.ReflectionTestUtils.setField(d1, "finalizedAt", resolved1);
        DisputeDecision dec1 = DisputeDecision.of(ResolutionAction.REFUND_FULL, null, false, "admin-1", Role.ADMIN, resolved1, "Refunded");
        org.springframework.test.util.ReflectionTestUtils.setField(d1, "decision", dec1);

        Dispute d2 = new Dispute();
        org.springframework.test.util.ReflectionTestUtils.setField(d2, "createdAt", created1);
        org.springframework.test.util.ReflectionTestUtils.setField(d2, "status", DisputeStatus.UNDER_REVIEW);

        when(mongoTemplate.find(any(), eq(Dispute.class))).thenReturn(List.of(d1, d2));

        AdminReportResponse report = service.generateReport("disputes",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), 0, 20);

        assertThat(report.available()).isTrue();
        assertThat(report.type()).isEqualTo("disputes");
        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().get(0)).containsEntry("date", "2026-11-20");
        assertThat(report.rows().get(0)).containsEntry("opened", 2L);
        assertThat(report.rows().get(0)).containsEntry("resolved", 1L);
        assertThat(report.rows().get(0)).containsEntry("underReview", 1L);
        assertThat(report.rows().get(0)).containsEntry("fullRefund", 1L);
        assertThat(report.rows().get(0)).containsEntry("avgResolutionHours", 6.0);

        assertThat(report.totals()).containsEntry("totalOpened", 2L);
        assertThat(report.totals()).containsEntry("totalResolved", 1L);
        assertThat(report.totals()).containsEntry("totalFullRefund", 1L);
    }

    @Test
    @DisplayName("disputes report CSV export is formula injection safe and has BOM")
    void disputesReportCsvExportSafe() {
        Dispute d1 = new Dispute();
        org.springframework.test.util.ReflectionTestUtils.setField(d1, "createdAt", Instant.parse("2026-11-20T08:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(d1, "status", DisputeStatus.RESOLVED);

        when(mongoTemplate.find(any(), eq(Dispute.class))).thenReturn(List.of(d1));

        AdminReportsService.CsvExport export = service.exportReportCsv("admin-1", "disputes", null, null);

        String csvContent = new String(export.bytes(), StandardCharsets.UTF_8);
        assertThat(csvContent).startsWith("\uFEFF");
        assertThat(csvContent).contains("Date,Opened,Resolved");
        assertThat(csvContent).contains("2026-11-20,1,1,0,0,0,0");
        assertThat(export.filename()).contains("disputes-report-2026-11-27.csv");
    }

    @Test
    @DisplayName("unavailable reports return available=false with dependent source named")
    void unavailableReportsNamed() {
        AdminReportResponse bookings = service.generateReport("bookings-summary", null, null, 0, 20);
        assertThat(bookings.available()).isFalse();
        assertThat(bookings.source()).isEqualTo("WP05 Booking System");

        AdminReportResponse tx = service.generateReport("transactions", null, null, 0, 20);
        assertThat(tx.available()).isFalse();
        assertThat(tx.source()).isEqualTo("WP07 Payment Gateway");
    }

    @Test
    @DisplayName("exporting unavailable report as CSV throws 503 SERVICE_UNAVAILABLE")
    void exportUnavailableReportThrows503() {
        ApiException ex = catchThrowableOfType(
                () -> service.exportReportCsv("admin-1", "transactions", null, null),
                ApiException.class
        );

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ex.getErrorCode()).isEqualTo("REPORT_SOURCE_UNAVAILABLE");
    }
}
