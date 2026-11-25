package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDisputeStatsPort;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;

/**
 * UserDisputeStatsPort read directly from the dispute module's {@code disputes} collection (WP09, same work
 * package). Read-only: counts and the newest disputes where the user is the customer or the provider. Uses the
 * existing customerId_1_createdAt_-1 and providerId_1_createdAt_-1 indexes. Only the listed fields are loaded
 * (never the description, evidence or decision notes).
 */
@Component
public class DisputeCollectionStatsAdapter implements UserDisputeStatsPort {

    private final MongoTemplate mongoTemplate;

    public DisputeCollectionStatsAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<DisputeStats> stats(String userId, int recentLimit) {
        long lifetime = mongoTemplate.count(Query.query(party(userId)), Dispute.class);
        long open = mongoTemplate.count(
                Query.query(new Criteria().andOperator(party(userId),
                        Criteria.where("status").in(DisputeStatus.ACTIVE))),
                Dispute.class);

        Query recentQuery = Query.query(party(userId))
                .with(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "_id")))
                .limit(recentLimit);
        recentQuery.fields().include("bookingId", "customerId", "providerId", "status", "category", "createdAt");
        List<RecentDispute> recent = mongoTemplate.find(recentQuery, Dispute.class).stream()
                .map(d -> new RecentDispute(d.getId(), d.getBookingId(),
                        d.getStatus() == null ? null : d.getStatus().name(),
                        d.getCategory() == null ? null : d.getCategory().name(),
                        userId.equals(d.getCustomerId()) ? Role.CUSTOMER.name() : Role.PROVIDER.name(),
                        d.getCreatedAt()))
                .toList();
        return Optional.of(new DisputeStats(lifetime, open, recent));
    }

    private static Criteria party(String userId) {
        return new Criteria().orOperator(Criteria.where("customerId").is(userId), Criteria.where("providerId").is(userId));
    }
}
