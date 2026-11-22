package com.gamagerecruiters.marketplace.modules.review.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.review.domain.ModerationEntry;
import com.gamagerecruiters.marketplace.modules.review.domain.ModerationFlag;
import com.gamagerecruiters.marketplace.modules.review.domain.ProviderReply;
import com.gamagerecruiters.marketplace.modules.review.domain.RemovalReason;
import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;

/**
 * Atomic conditional updates for reviews (concurrency protection).
 *
 * <p>Each method is one MongoDB {@code findAndModify}: the business conditions are part of the
 * query, and only the fields that the action owns are written ({@code $set}). So a customer edit
 * and a provider reply sent at the same time can never overwrite each other's fields, and a stale
 * edit cannot slip in after the provider has replied. No new field or migration is needed.
 */
@Component
public class ReviewAtomicUpdates {

    private final MongoTemplate mongoTemplate;

    public ReviewAtomicUpdates(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Applies a customer edit only if, at that moment, the review still belongs to the customer,
     * is not REMOVED, has no provider reply, and was created at or after {@code editableFrom}
     * (a review without createdAt never matches). It also writes the result of the automated checks on the
     * new text (FR-REV-003): the new status (PUBLISHED, or AWAITING_MODERATION when flagged), the flags,
     * and an AUTO_FLAGGED history entry when {@code autoFlagEntry} is not null. The moderation revision goes up,
     * so a moderator decision based on the old text is rejected.
     *
     * @return the updated review, or empty when any condition no longer holds
     */
    public Optional<Review> applyCustomerEdit(String reviewId, String customerId, int rating, String comment,
                                              Instant editableFrom, Instant now, ReviewStatus newStatus,
                                              List<ModerationFlag> flags, ModerationEntry autoFlagEntry) {
        Query query = new Query(Criteria.where("id").is(reviewId)
                .and("customerId").is(customerId)
                .and("status").ne(ReviewStatus.REMOVED.name())
                .and("providerReply").is(null)
                .and("createdAt").gte(editableFrom));

        Update update = new Update()
                .set("rating", rating)
                .set("edited", true)
                .set("editedAt", now)
                .set("updatedAt", now)
                .set("status", newStatus)
                .set("moderationFlags", flags)
                .inc("moderationRevision", 1);
        if (autoFlagEntry != null) {
            update.push("moderationHistory", autoFlagEntry);
        }
        if (comment == null) {
            update.unset("comment");
        } else {
            update.set("comment", comment);
        }

        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Review.class));
    }

    /**
     * Adds or replaces the provider's single reply only if the review still belongs to the
     * provider and is PUBLISHED. Only {@code providerReply} and {@code updatedAt} are written,
     * so the customer's rating and comment are never touched.
     *
     * @return the updated review, or empty when any condition no longer holds
     */
    public Optional<Review> applyProviderReply(String reviewId, String providerId, ProviderReply reply, Instant now) {
        Query query = new Query(Criteria.where("id").is(reviewId)
                .and("providerId").is(providerId)
                .and("status").is(ReviewStatus.PUBLISHED.name()));

        Update update = new Update()
                .set("providerReply", reply)
                .set("updatedAt", now);

        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Review.class));
    }

    /**
     * Counts a new user report, only while the review is PUBLISHED (FR-REV-003). The moderation revision goes up,
     * so a moderator who has not seen this report cannot dismiss it unknowingly.
     *
     * @return the updated review, or empty when the review is no longer published
     */
    public Optional<Review> addOpenReport(String reviewId, Instant now) {
        Query query = new Query(Criteria.where("id").is(reviewId).and("status").is(ReviewStatus.PUBLISHED.name()));
        Update update = new Update().inc("openReportCount", 1).inc("moderationRevision", 1).set("updatedAt", now);
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Review.class));
    }

    /**
     * Applies a moderator decision only if the review is still at {@code expectedRevision} (the revision the
     * moderator saw) and in {@code expectedStatus} (and, when {@code requireOpenReports}, still has open reports).
     * Every decision, new report and customer edit increases the revision, so two decisions based on the same
     * state can never both succeed, including decisions that keep the status (dismissing reports on a PUBLISHED
     * review). A moderator can still decide again after reloading the review (new revision).
     * Sets the new status and reason, clears the open report counter, increases the revision and appends the
     * history entry.
     *
     * @return the updated review, or empty when the review changed in the meantime
     */
    public Optional<Review> applyModerationDecision(String reviewId, long expectedRevision, ReviewStatus expectedStatus,
                                                    boolean requireOpenReports, ReviewStatus newStatus,
                                                    RemovalReason removalReason, ModerationEntry entry, Instant now) {
        Criteria criteria = Criteria.where("id").is(reviewId).and("status").is(expectedStatus.name());
        criteria = expectedRevision == 0
                // Reviews stored before the field existed have no moderationRevision: they are revision 0
                ? criteria.and("moderationRevision").in(0L, null)
                : criteria.and("moderationRevision").is(expectedRevision);
        if (requireOpenReports) {
            criteria = criteria.and("openReportCount").gt(0);
        }
        Update update = new Update()
                .set("status", newStatus)
                .set("openReportCount", 0)
                .set("updatedAt", now)
                .inc("moderationRevision", 1)
                .push("moderationHistory", entry);
        if (removalReason != null) {
            update.set("removalReason", removalReason).set("statusReason", removalReason.getLabel());
        }
        return Optional.ofNullable(mongoTemplate.findAndModify(new Query(criteria), update,
                FindAndModifyOptions.options().returnNew(true), Review.class));
    }
}
