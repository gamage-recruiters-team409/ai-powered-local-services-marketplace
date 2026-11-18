package com.gamagerecruiters.marketplace.modules.completion.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.completion.domain.CompletionConfirmationType;
import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;

/**
 * Conditional updates of a completion record (one MongoDB findAndModify each).
 *
 * <p>The rule that allows the change is part of the query, and only the fields owned by the
 * action are written. A request that read an older state therefore cannot overwrite a decision
 * that another request has already committed: its update simply matches nothing.
 * Each method returns the updated record, or empty when the condition no longer holds.
 */
@Component
public class CompletionAtomicUpdates {

    private final MongoTemplate mongoTemplate;

    public CompletionAtomicUpdates(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Started and not yet marked completed → stores the note, photos and markedCompletedAt. */
    public Optional<ServiceCompletion> markCompleted(String bookingId, String note, List<String> photoUrls, Instant now) {
        Query query = new Query(Criteria.where("bookingId").is(bookingId)
                .and("startedAt").ne(null)
                .and("markedCompletedAt").is(null));
        Update update = new Update()
                .set("photoUrls", photoUrls)
                .set("markedCompletedAt", now)
                .set("updatedAt", now);
        if (note == null) {
            update.unset("completionNote");
        } else {
            update.set("completionNote", note);
        }
        return modify(query, update);
    }

    /**
     * Marked completed, not confirmed and no issue raised → stores confirmedAt and the type.
     * {@code markedCompletedNotAfter}: when set (auto-confirm), the record must have been marked
     * completed at or before this time, i.e. the 48-hour window has passed.
     */
    public Optional<ServiceCompletion> confirm(String bookingId, CompletionConfirmationType type, Instant now,
                                               Instant markedCompletedNotAfter) {
        Criteria criteria = Criteria.where("bookingId").is(bookingId)
                .and("confirmedAt").is(null)
                .and("issueRaisedAt").is(null);
        criteria = markedCompletedNotAfter == null
                ? criteria.and("markedCompletedAt").ne(null)
                : criteria.and("markedCompletedAt").lte(markedCompletedNotAfter);
        Update update = new Update()
                .set("confirmedAt", now)
                .set("confirmationType", type.name())
                .set("updatedAt", now);
        return modify(new Query(criteria), update);
    }

    /**
     * Marked completed at or after {@code markedCompletedNotBefore} (inside the 48-hour window),
     * not confirmed and no issue raised yet → stores issueRaisedAt.
     */
    public Optional<ServiceCompletion> raiseIssue(String bookingId, Instant now, Instant markedCompletedNotBefore) {
        Query query = new Query(Criteria.where("bookingId").is(bookingId)
                .and("markedCompletedAt").gte(markedCompletedNotBefore)
                .and("confirmedAt").is(null)
                .and("issueRaisedAt").is(null));
        Update update = new Update()
                .set("issueRaisedAt", now)
                .set("updatedAt", now);
        return modify(query, update);
    }

    /** Stores the WP09 dispute ID once (only when none is stored yet). */
    public Optional<ServiceCompletion> recordDisputeId(String bookingId, String disputeId, Instant now) {
        Query query = new Query(Criteria.where("bookingId").is(bookingId).and("disputeId").is(null));
        Update update = new Update()
                .set("disputeId", disputeId)
                .set("updatedAt", now);
        return modify(query, update);
    }

    private Optional<ServiceCompletion> modify(Query query, Update update) {
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), ServiceCompletion.class));
    }
}
