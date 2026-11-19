package com.gamagerecruiters.marketplace.modules.review.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.review.domain.Review;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewRatingStats;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewRatingStats.SubjectType;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingAggregate;

/**
 * Keeps the stored rating aggregates (FR-REV-004) for providers and listings.
 *
 * <p>Each recompute reads the current counts of PUBLISHED reviews (one aggregation) and stores them.
 * Writes are guarded by {@code computedAt}: a result only replaces an older one, so when two recomputes
 * run at the same time the one that read later always wins and a slow, older result can never overwrite
 * a newer one. Recomputing from the reviews (instead of +1/-1 counters) also repairs any earlier drift.
 *
 * <p>Recovery (failed recomputes): a failed recompute is logged and does not fail the user's request, because the
 * review itself is already saved. The stored aggregate is never trusted blindly: every read through
 * {@link ReviewRatingFacade} checks the reviews themselves (the source of truth) for a change made after the
 * aggregate was computed ({@code review.updatedAt > computedAt - margin}). If there is one, the aggregate is stale
 * and is recomputed and stored before it is returned. So the invalidation is durable without any extra write
 * (an extra "mark stale" write could fail in the same outage): it is the saved review change itself. The
 * margin also covers a change whose transaction committed just after a recompute had started.
 * If that recompute fails too, the last stored values are returned and the next read tries again.
 */
@Service
public class ReviewRatingStatsService implements ReviewRatingFacade {

    private static final Logger log = LoggerFactory.getLogger(ReviewRatingStatsService.class);

    /** A review change this close to (or after) the stored computedAt makes the aggregate stale. */
    static final Duration RECENT_CHANGE_MARGIN = Duration.ofMinutes(1);

    private final MongoTemplate mongoTemplate;
    private final ReviewRatingQuery ratingQuery;
    private final Clock clock;

    @Autowired
    public ReviewRatingStatsService(MongoTemplate mongoTemplate, ReviewRatingQuery ratingQuery) {
        this(mongoTemplate, ratingQuery, Clock.systemUTC());
    }

    ReviewRatingStatsService(MongoTemplate mongoTemplate, ReviewRatingQuery ratingQuery, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.ratingQuery = ratingQuery;
        this.clock = clock;
    }

    /** Recalculates the provider's and (when known) the listing's aggregates after a review change. */
    public void recompute(String providerId, String listingId) {
        if (providerId != null) {
            recomputeSafely(SubjectType.PROVIDER, providerId);
        }
        if (listingId != null) {
            recomputeSafely(SubjectType.LISTING, listingId);
        }
    }

    @Override
    public RatingAggregate getProviderRating(String providerId) {
        return read(SubjectType.PROVIDER, providerId);
    }

    @Override
    public RatingAggregate getListingRating(String listingId) {
        return read(SubjectType.LISTING, listingId);
    }

    private RatingAggregate read(SubjectType type, String subjectId) {
        ReviewRatingStats stored = mongoTemplate.findById(ReviewRatingStats.idOf(type, subjectId), ReviewRatingStats.class);
        if (stored == null) {
            // First read (e.g. reviews written before this feature): compute, store and return
            stored = recomputeNow(type, subjectId);
        } else if (isStale(type, subjectId, stored)) {
            // A review changed after this aggregate was computed (e.g. its recompute failed): repair it now
            try {
                stored = recomputeNow(type, subjectId);
                log.info("event=REVIEW_RATING_RECOVERED subjectType={} subjectId={}", type, subjectId);
            } catch (RuntimeException ex) {
                log.warn("event=REVIEW_RATING_RECOVERY_FAILED subjectType={} subjectId={} cause={}",
                        type, subjectId, ex.getClass().getSimpleName());
            }
        }
        return new RatingAggregate(subjectId, stored.getRatingAvg(), stored.getRatingCount());
    }

    /** True when any review of the subject was changed after (or just before) the stored computedAt. */
    boolean isStale(SubjectType type, String subjectId, ReviewRatingStats stored) {
        if (stored.getComputedAt() == null) {
            return true;
        }
        String field = type == SubjectType.PROVIDER ? "providerId" : "listingId";
        Query changedSince = new Query(Criteria.where(field).is(subjectId)
                .and("updatedAt").gt(stored.getComputedAt().minus(RECENT_CHANGE_MARGIN)));
        return mongoTemplate.exists(changedSince, Review.class);
    }

    private void recomputeSafely(SubjectType type, String subjectId) {
        try {
            recomputeNow(type, subjectId);
        } catch (RuntimeException ex) {
            log.warn("event=REVIEW_RATING_RECOMPUTE_FAILED subjectType={} subjectId={} cause={}",
                    type, subjectId, ex.getClass().getSimpleName());
        }
    }

    /** Reads the counts and stores them unless a newer result is already stored. Returns what was computed. */
    ReviewRatingStats recomputeNow(SubjectType type, String subjectId) {
        Instant startedAt = clock.instant();
        Map<Integer, Long> counts = type == SubjectType.PROVIDER
                ? ratingQuery.countPublishedByRating(subjectId)
                : ratingQuery.countPublishedByRatingForListing(subjectId);
        ReviewRatingStats stats = build(type, subjectId, counts, startedAt);

        Query olderOrMissing = new Query(Criteria.where("_id").is(stats.getId()).and("computedAt").lt(startedAt));
        Update update = new Update()
                .set("subjectType", stats.getSubjectType())
                .set("subjectId", subjectId)
                .set("ratingAvg", stats.getRatingAvg())
                .set("ratingCount", stats.getRatingCount())
                .set("ratingCounts", stats.getRatingCounts())
                .set("computedAt", startedAt);
        try {
            mongoTemplate.upsert(olderOrMissing, update, ReviewRatingStats.class);
        } catch (DuplicateKeyException storedMeanwhile) {
            // Another recompute stored a result first (the upsert could not insert). Replace it only if it is
            // older than this one; a newer result is kept.
            mongoTemplate.updateFirst(olderOrMissing, update, ReviewRatingStats.class);
        }
        return stats;
    }

    /** Average rounded to one decimal (0.0 without reviews) and counts for every star value 1–5. */
    static ReviewRatingStats build(SubjectType type, String subjectId, Map<Integer, Long> countsByRating, Instant at) {
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        long sum = 0;
        for (int stars = 5; stars >= 1; stars--) {
            long count = countsByRating.getOrDefault(stars, 0L);
            counts.put(String.valueOf(stars), count);
            total += count;
            sum += count * stars;
        }
        ReviewRatingStats stats = new ReviewRatingStats();
        stats.setId(ReviewRatingStats.idOf(type, subjectId));
        stats.setSubjectType(type);
        stats.setSubjectId(subjectId);
        stats.setRatingCount(total);
        stats.setRatingAvg(total == 0 ? 0.0 : Math.round((double) sum / total * 10.0) / 10.0);
        stats.setRatingCounts(counts);
        stats.setComputedAt(at);
        return stats;
    }
}
