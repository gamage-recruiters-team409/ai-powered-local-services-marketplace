package com.gamagerecruiters.marketplace.modules.admin.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAnalyticsSummaryResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAnalyticsSummaryResponse.AuditActivityItem;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAnalyticsSummaryResponse.DisputeBreakdown;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAnalyticsSummaryResponse.MetricItem;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.security.Roles;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.SettlementState;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.verification.service.VerificationAdminService;

/**
 * Super Admin Analytics summary service (FR-ADM-001 / SRS 13.1).
 * Returns real values for what exists today; unavailable dependencies return available: false.
 */
@Service
public class AdminAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AdminAnalyticsService.class);

    private final MongoTemplate mongoTemplate;
    private final Optional<VerificationAdminService> verificationAdminService;

    @Autowired
    public AdminAnalyticsService(MongoTemplate mongoTemplate,
                                 Optional<VerificationAdminService> verificationAdminService) {
        this.mongoTemplate = mongoTemplate;
        this.verificationAdminService = verificationAdminService;
    }

    public AdminAnalyticsSummaryResponse getSummary() {
        // Real dispute counts
        long underReview = countDisputeStatus(DisputeStatus.UNDER_REVIEW);
        long awaitingResponse = countDisputeStatus(DisputeStatus.AWAITING_RESPONSE);
        long appealed = countDisputeStatus(DisputeStatus.APPEALED);
        long manualReview = countManualReviewDisputes();
        long totalOpenDisputes = underReview + awaitingResponse + appealed;

        DisputeBreakdown breakdown = new DisputeBreakdown(underReview, awaitingResponse, appealed, manualReview);
        MetricItem openDisputes = MetricItem.available(totalOpenDisputes, "DisputeService");

        // Real admin accounts count
        long adminCount = mongoTemplate.count(
                Query.query(Criteria.where("role").in(Roles.ADMIN_ROLES)),
                User.class
        );
        MetricItem adminAccounts = MetricItem.available(adminCount, "AdminAccountService");

        // Pending verifications from WP02
        MetricItem pendingVerifications;
        if (verificationAdminService.isPresent()) {
            try {
                int count = verificationAdminService.get().getPendingVerifications().size();
                pendingVerifications = MetricItem.available(count, "VerificationAdminService");
            } catch (RuntimeException e) {
                log.warn("event=VERIFICATION_ADMIN_UNAVAILABLE cause={}", e.getClass().getSimpleName());
                pendingVerifications = MetricItem.unavailable("WP02 Provider Verification");
            }
        } else {
            pendingVerifications = MetricItem.unavailable("WP02 Provider Verification");
        }

        // Recent audit activity
        Query auditQuery = Query.query(new Criteria())
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(5);
        List<AdminAuditLog> recentLogs = mongoTemplate.find(auditQuery, AdminAuditLog.class);
        List<AuditActivityItem> auditItems = recentLogs.stream()
                .map(l -> new AuditActivityItem(
                        l.getId(),
                        l.getActorId(),
                        l.getAction() != null ? l.getAction().name() : "ADMIN_ACTION",
                        l.getReason(),
                        l.getTargetUserId(),
                        l.getOutcome() != null ? l.getOutcome().name() : "SUCCESS",
                        l.getCreatedAt()
                ))
                .toList();

        // Real counts for Users, Providers, Bookings
        long userCount = mongoTemplate.count(new Query(), User.class);
        MetricItem users = MetricItem.available(userCount, "UserRepository");

        long providerCount = mongoTemplate.count(new Query(), ProviderProfile.class);
        MetricItem providers = MetricItem.available(providerCount, "ProviderProfileRepository");

        long bookingCount = mongoTemplate.count(new Query(), Booking.class);
        MetricItem bookings = MetricItem.available(bookingCount, "BookingRepository");

        // Payment monetary aggregates
        BigDecimal totalBookingValue = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalRefunds = BigDecimal.ZERO;

        try {
            Aggregation agg = Aggregation.newAggregation(
                    Aggregation.match(Criteria.where("status").in(
                            PaymentStatus.CAPTURED, PaymentStatus.RELEASED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED
                    )),
                    Aggregation.group()
                            .sum("amount").as("totalAmount")
                            .sum("commissionAmount").as("totalCommission")
                            .sum("refundedAmount").as("totalRefunds")
            );
            AggregationResults<Document> results = mongoTemplate.aggregate(agg, "payments", Document.class);
            Document doc = results.getUniqueMappedResult();
            if (doc != null) {
                Object a = doc.get("totalAmount");
                Object c = doc.get("totalCommission");
                Object r = doc.get("totalRefunds");
                if (a != null) totalBookingValue = new BigDecimal(a.toString());
                if (c != null) totalCommission = new BigDecimal(c.toString());
                if (r != null) totalRefunds = new BigDecimal(r.toString());
            }
        } catch (Exception e) {
            log.warn("event=PAYMENT_AGGREGATION_FAILED cause={}", e.getMessage());
        }

        MetricItem bookingValue = MetricItem.available(totalBookingValue, "PaymentRepository");
        MetricItem commission = MetricItem.available(totalCommission, "PaymentRepository");
        MetricItem refunds = MetricItem.available(totalRefunds, "PaymentRepository");

        return new AdminAnalyticsSummaryResponse(
                users,
                providers,
                bookings,
                bookingValue,
                commission,
                refunds,
                openDisputes,
                pendingVerifications,
                adminAccounts,
                breakdown,
                auditItems
        );
    }

    private long countDisputeStatus(DisputeStatus status) {
        return mongoTemplate.count(Query.query(new Criteria().andOperator(
                Criteria.where("bookingSyncState").ne(BookingSyncState.PENDING),
                Criteria.where("status").is(status)
        )), Dispute.class);
    }

    private long countManualReviewDisputes() {
        return mongoTemplate.count(Query.query(new Criteria().andOperator(
                Criteria.where("bookingSyncState").ne(BookingSyncState.PENDING),
                Criteria.where("settlement.state").in(SettlementState.MANUAL_REVIEW, SettlementState.REJECTED)
        )), Dispute.class);
    }
}
