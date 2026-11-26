package com.gamagerecruiters.marketplace.modules.operations.service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseAction;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;

/**
 * Persistence for the operational handling log ({@code ops_case_actions}). The log is append-only; the current status
 * of a case is the {@link OpsCaseStatus} of its most recent action (default {@link OpsCaseStatus#OPEN} when none).
 * "Latest" is decided by {@code createdAt} then {@code _id}, so concurrent actions on the same case still yield one
 * consistent current status.
 */
@Repository
public class OpsCaseActionStore {

    static final String F_CASE_TYPE = "caseType";
    static final String F_SUBJECT_ID = "subjectId";
    static final String F_CASE_STATUS = "caseStatus";
    static final String F_CREATED_AT = "createdAt";
    static final String F_ID = "_id";

    private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, F_CREATED_AT, F_ID);

    private final MongoTemplate mongoTemplate;

    public OpsCaseActionStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public OpsCaseAction insert(OpsCaseAction action) {
        return mongoTemplate.insert(action);
    }

    /** The most recent action for one case, or empty when none has been recorded. */
    public Optional<OpsCaseAction> latest(OpsCaseType caseType, String subjectId) {
        Query query = Query.query(Criteria.where(F_CASE_TYPE).is(caseType).and(F_SUBJECT_ID).is(subjectId))
                .with(LATEST_FIRST)
                .limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(query, OpsCaseAction.class));
    }

    /**
     * The most recent action per subject for a set of subjects of one case type. Subjects with no action are absent
     * from the map (their current status is {@link OpsCaseStatus#OPEN}).
     */
    public Map<String, OpsCaseAction> latestBySubject(OpsCaseType caseType, Collection<String> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return Map.of();
        }
        Query query = Query.query(Criteria.where(F_CASE_TYPE).is(caseType).and(F_SUBJECT_ID).in(subjectIds))
                .with(LATEST_FIRST);
        Map<String, OpsCaseAction> latest = new HashMap<>();
        for (OpsCaseAction action : mongoTemplate.find(query, OpsCaseAction.class)) {
            // Sorted latest-first: the first time we see a subject is its latest action.
            latest.putIfAbsent(action.getSubjectId(), action);
        }
        return latest;
    }

    /** Distinct cases of a type whose latest-recorded RESOLVED action falls in [{@code from}, {@code to}). */
    public long countResolvedToday(OpsCaseType caseType, Instant from, Instant to) {
        Query query = Query.query(Criteria.where(F_CASE_TYPE).is(caseType)
                .and(F_CASE_STATUS).is(OpsCaseStatus.RESOLVED)
                .and(F_CREATED_AT).gte(from).lt(to));
        return mongoTemplate.findDistinct(query, F_SUBJECT_ID, OpsCaseAction.class, String.class).size();
    }

    /**
     * Cases of a type whose current (latest) status is {@code status}, newest action first, de-duplicated by subject.
     * Used to show pending "Suspension recommended" cases to Admin.
     */
    public List<OpsCaseAction> currentStatus(OpsCaseType caseType, OpsCaseStatus status, int limit) {
        Query query = Query.query(Criteria.where(F_CASE_TYPE).is(caseType).and(F_CASE_STATUS).is(status))
                .with(LATEST_FIRST);
        Map<String, OpsCaseAction> bySubject = new LinkedHashMap<>();
        for (OpsCaseAction action : mongoTemplate.find(query, OpsCaseAction.class)) {
            // Keep only cases where this status is still the latest action overall.
            latest(caseType, action.getSubjectId())
                    .filter(l -> l.getCaseStatus() == status)
                    .ifPresent(l -> bySubject.putIfAbsent(action.getSubjectId(), l));
            if (bySubject.size() >= limit) {
                break;
            }
        }
        return List.copyOf(bySubject.values());
    }
}
