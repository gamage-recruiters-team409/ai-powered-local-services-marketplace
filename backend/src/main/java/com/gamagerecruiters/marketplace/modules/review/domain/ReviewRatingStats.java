package com.gamagerecruiters.marketplace.modules.review.domain;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Stored rating aggregate for one provider or one listing (FR-REV-004), from PUBLISHED reviews only.
 * Recalculated by the review module after every add, edit, publish and removal; other modules read it
 * through {@code ReviewRatingFacade} (search, comparison, AI ranking, analytics).
 *
 * <p>{@code id} is {@code "PROVIDER:<providerId>"} or {@code "LISTING:<listingId>"}. {@code computedAt} is the
 * moment the counts were read; a write only replaces an older result (see ReviewRatingStatsService).
 */
@Document(collection = "review_rating_stats")
public class ReviewRatingStats {

    public enum SubjectType {
        PROVIDER,
        LISTING
    }

    @Id
    private String id;
    private SubjectType subjectType;
    private String subjectId;
    private double ratingAvg;
    private long ratingCount;
    /** Star value ("1"–"5") -> number of published reviews. String keys: MongoDB field names. */
    private Map<String, Long> ratingCounts;
    private Instant computedAt;

    public ReviewRatingStats() {
    }

    public static String idOf(SubjectType type, String subjectId) {
        return type.name() + ":" + subjectId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(SubjectType subjectType) {
        this.subjectType = subjectType;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public double getRatingAvg() {
        return ratingAvg;
    }

    public void setRatingAvg(double ratingAvg) {
        this.ratingAvg = ratingAvg;
    }

    public long getRatingCount() {
        return ratingCount;
    }

    public void setRatingCount(long ratingCount) {
        this.ratingCount = ratingCount;
    }

    public Map<String, Long> getRatingCounts() {
        return ratingCounts;
    }

    public void setRatingCounts(Map<String, Long> ratingCounts) {
        this.ratingCounts = ratingCounts;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(Instant computedAt) {
        this.computedAt = computedAt;
    }
}
