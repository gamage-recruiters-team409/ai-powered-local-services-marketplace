package com.gamagerecruiters.marketplace.modules.announcement.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.announcement.domain.Announcement;
import com.gamagerecruiters.marketplace.modules.announcement.domain.AnnouncementStatus;

/**
 * All writes after creation are ONE conditional findAndModify/remove whose filter contains the expected status (and,
 * for admin edits, the version that was read). So an edit or delete racing with the scheduled publisher, or with
 * another admin, can never overwrite or remove a document that changed in between: the caller gets empty/false and
 * answers 409. Every write increments the optimistic-locking version.
 */
@Component
public class AnnouncementStore {

    static final String F_ID = "_id";
    static final String F_VERSION = "version";
    static final String F_STATUS = "status";
    static final String F_SCHEDULED_AT = "scheduledAt";
    static final String F_PIN = "pinToHeader";

    private static final FindAndModifyOptions RETURN_NEW = FindAndModifyOptions.options().returnNew(true);

    private final MongoTemplate mongoTemplate;

    public AnnouncementStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public Announcement insert(Announcement announcement) {
        return mongoTemplate.insert(announcement);
    }

    public Optional<Announcement> findById(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, Announcement.class));
    }

    public List<Announcement> find(Query query, Pageable pageable) {
        return mongoTemplate.find(Query.of(query).with(pageable), Announcement.class);
    }

    public long count(Query query) {
        return mongoTemplate.count(query, Announcement.class);
    }

    private static Criteria unchanged(String id, AnnouncementStatus status, long version) {
        return Criteria.where(F_ID).is(id).and(F_STATUS).is(status).and(F_VERSION).is(version);
    }

    /**
     * Replaces every editable field of a DRAFT or SCHEDULED announcement (including a new status), only if its status
     * and version are still the ones that were read. Empty if it changed meanwhile.
     */
    public Optional<Announcement> replaceUnpublished(Announcement read, Announcement changed) {
        Update update = new Update()
                .set("title", changed.getTitle())
                .set("message", changed.getMessage())
                .set("audience", changed.getAudience())
                .set(F_STATUS, changed.getStatus())
                .set("sendPush", changed.isSendPush())
                .set(F_PIN, changed.isPinToHeader())
                .set("deliveryState", changed.getDeliveryState())
                .set("updatedBy", changed.getUpdatedBy())
                .set("updatedAt", changed.getUpdatedAt())
                .inc(F_VERSION, 1);
        setOrUnset(update, F_SCHEDULED_AT, changed.getScheduledAt());
        setOrUnset(update, "publishedAt", changed.getPublishedAt());
        setOrUnset(update, "pinExpiresAt", changed.getPinExpiresAt());
        Query query = Query.query(unchanged(read.getId(), read.getStatus(), read.getVersion()));
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update, RETURN_NEW, Announcement.class));
    }

    /** Title/message correction of a PUBLISHED announcement, only if status and version are unchanged. */
    public Optional<Announcement> updatePublishedText(Announcement read, String title, String message,
                                                      String updatedBy, Instant now) {
        Update update = new Update()
                .set("title", title)
                .set("message", message)
                .set("updatedBy", updatedBy)
                .set("updatedAt", now)
                .inc(F_VERSION, 1);
        Query query = Query.query(unchanged(read.getId(), AnnouncementStatus.PUBLISHED, read.getVersion()));
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update, RETURN_NEW, Announcement.class));
    }

    /**
     * Deletes a DRAFT/SCHEDULED announcement only while it still has the status AND version that were read, so an
     * edit made by another admin in between (same status, newer version) is never deleted. True if deleted.
     */
    public boolean deleteUnpublished(Announcement read) {
        if (read.getStatus() == AnnouncementStatus.PUBLISHED) {
            return false;
        }
        Query query = Query.query(unchanged(read.getId(), read.getStatus(), read.getVersion()));
        return mongoTemplate.remove(query, Announcement.class).getDeletedCount() == 1;
    }

    /**
     * Atomically claims ONE due SCHEDULED announcement (scheduledAt <= now) with the given pin flag and publishes it.
     * findAndModify on status = SCHEDULED: of several publisher instances only one can claim a given announcement,
     * so it is never published twice. {@code pinExpiresAt} is set only for pinned ones.
     */
    public Optional<Announcement> claimDue(Instant now, boolean pinned, Instant pinExpiresAt) {
        Criteria criteria = Criteria.where(F_STATUS).is(AnnouncementStatus.SCHEDULED).and(F_SCHEDULED_AT).lte(now);
        criteria = pinned ? criteria.and(F_PIN).is(true) : criteria.and(F_PIN).ne(true);
        Update update = new Update()
                .set(F_STATUS, AnnouncementStatus.PUBLISHED)
                .set("publishedAt", now)
                .set("updatedAt", now)
                .inc(F_VERSION, 1);
        if (pinned) {
            update.set("pinExpiresAt", pinExpiresAt);
        }
        return Optional.ofNullable(mongoTemplate.findAndModify(Query.query(criteria), update, RETURN_NEW,
                Announcement.class));
    }

    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }
}
