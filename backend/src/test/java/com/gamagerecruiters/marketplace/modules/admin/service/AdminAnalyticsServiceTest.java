package com.gamagerecruiters.marketplace.modules.admin.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAnalyticsSummaryResponse;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.verification.dto.AdminVerificationQueueItemResponse;
import com.gamagerecruiters.marketplace.modules.verification.service.VerificationAdminService;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private VerificationAdminService verificationAdminService;

    private AdminAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AdminAnalyticsService(mongoTemplate, Optional.of(verificationAdminService));
    }

    @Test
    @DisplayName("getSummary returns real values for dispute, admin accounts, verifications and audit activity")
    void summaryReturnsRealValues() {
        // Dispute counts: 3 UNDER_REVIEW, 2 AWAITING_RESPONSE, 1 APPEALED -> 6 total open
        when(mongoTemplate.count(any(), eq(Dispute.class)))
                .thenReturn(3L) // underReview
                .thenReturn(2L) // awaitingResponse
                .thenReturn(1L) // appealed
                .thenReturn(1L); // manualReview

        // Admin accounts count: 5, total users: 14280
        when(mongoTemplate.count(any(), eq(User.class))).thenReturn(5L).thenReturn(14280L);

        // Providers count: 1842
        when(mongoTemplate.count(any(), eq(ProviderProfile.class))).thenReturn(1842L);

        // Bookings count: 628
        when(mongoTemplate.count(any(), eq(Booking.class))).thenReturn(628L);

        // Payment aggregates
        Document doc = new Document();
        doc.put("totalAmount", new BigDecimal("18400000"));
        doc.put("totalCommission", new BigDecimal("2700000"));
        doc.put("totalRefunds", new BigDecimal("141000"));
        AggregationResults<Document> aggResults = new AggregationResults<>(List.of(doc), doc);
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("payments"), eq(Document.class))).thenReturn(aggResults);

        // Verification pending count: 4
        AdminVerificationQueueItemResponse item = new AdminVerificationQueueItemResponse(
                "p1", "v1", "Plumber", com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType.INDIVIDUAL,
                "Colombo", "Colombo", com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus.SUBMITTED,
                Instant.now(), 2L
        );
        when(verificationAdminService.getPendingVerifications()).thenReturn(List.of(item, item, item, item));

        // Audit logs
        AdminAuditLog log1 = AdminAuditLog.pending("corr-1", "admin-1", Role.SUPER_ADMIN,
                AdminAuditAction.PLATFORM_SETTINGS_UPDATED, "setting:booking", null, null, null, "diff", Instant.now());
        org.springframework.test.util.ReflectionTestUtils.setField(log1, "outcome", AuditOutcome.SUCCESS);
        when(mongoTemplate.find(any(), eq(AdminAuditLog.class))).thenReturn(List.of(log1));

        AdminAnalyticsSummaryResponse summary = service.getSummary();

        assertThat(summary.openDisputes().available()).isTrue();
        assertThat(summary.openDisputes().value()).isEqualTo(6L);
        assertThat(summary.disputeBreakdown().underReview()).isEqualTo(3L);
        assertThat(summary.disputeBreakdown().manualReview()).isEqualTo(1L);

        assertThat(summary.adminAccounts().available()).isTrue();
        assertThat(summary.adminAccounts().value()).isEqualTo(5L);

        assertThat(summary.pendingVerifications().available()).isTrue();
        assertThat(summary.pendingVerifications().value()).isEqualTo(4);

        assertThat(summary.recentAuditActivity()).hasSize(1);
        assertThat(summary.recentAuditActivity().get(0).action()).isEqualTo("PLATFORM_SETTINGS_UPDATED");

        // Connected real dependencies
        assertThat(summary.users().available()).isTrue();
        assertThat(summary.users().value()).isEqualTo(14280L);
        assertThat(summary.providers().available()).isTrue();
        assertThat(summary.providers().value()).isEqualTo(1842L);
        assertThat(summary.bookings().available()).isTrue();
        assertThat(summary.bookings().value()).isEqualTo(628L);
        assertThat(summary.bookingValue().available()).isTrue();
        assertThat(summary.bookingValue().value()).isEqualTo(new BigDecimal("18400000"));
        assertThat(summary.commission().available()).isTrue();
        assertThat(summary.commission().value()).isEqualTo(new BigDecimal("2700000"));
        assertThat(summary.refunds().available()).isTrue();
        assertThat(summary.refunds().value()).isEqualTo(new BigDecimal("141000"));
    }
}
