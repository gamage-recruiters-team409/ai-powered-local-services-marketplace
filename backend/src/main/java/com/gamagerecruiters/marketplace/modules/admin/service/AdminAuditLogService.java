package com.gamagerecruiters.marketplace.modules.admin.service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAuditLogResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.admin.repository.AdminAuditLogRepository;
import com.gamagerecruiters.marketplace.modules.admin.security.SuperAdminAccess;

/**
 * Owns the admin audit log. Records are inserted as PENDING and their outcome is set exactly once.
 * There is no method to edit any other field or to delete a record.
 *
 * <p>The write methods are called by the admin workflows after their own SUPER_ADMIN check and are not
 * guarded here. {@link #search} is guarded directly so no other caller can read the log without
 * SUPER_ADMIN.</p>
 */
@Service
public class AdminAuditLogService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_ID_FILTER_LENGTH = 100;

    private final AdminAuditLogRepository repository;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    @Autowired
    public AdminAuditLogService(AdminAuditLogRepository repository, MongoTemplate mongoTemplate) {
        this(repository, mongoTemplate, Clock.systemUTC());
    }

    AdminAuditLogService(AdminAuditLogRepository repository, MongoTemplate mongoTemplate, Clock clock) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /** Inserts a new PENDING record. Fails (and the action must not run) if it cannot be written. */
    public AdminAuditLog recordPending(AdminAuditLog pending) {
        if (pending.getOutcome() != AuditOutcome.PENDING) {
            throw new IllegalArgumentException("Only PENDING audit records can be inserted");
        }
        return repository.insert(pending);
    }

    /**
     * Inserts an AUDIT_RECONCILIATION_REQUIRED record for {@code original}, whose action was applied but which
     * could not be completed. Insert only, so it does not depend on the update that failed.
     */
    public AdminAuditLog recordReconciliation(AdminAuditLog original, String targetUserId, String reasonCode) {
        return repository.insert(AdminAuditLog.reconciliation(original, targetUserId, reasonCode, clock.instant()));
    }

    /** @throws AuditCompletionException if the record was not set to SUCCESS */
    public void markSuccess(String auditLogId) {
        completeOnce(auditLogId, AuditOutcome.SUCCESS, null, null);
    }

    /**
     * SUCCESS for an account creation: also records the id of the account that was created.
     *
     * @throws AuditCompletionException if the record was not set to SUCCESS
     */
    public void markCreated(String auditLogId, String createdUserId) {
        completeOnce(auditLogId, AuditOutcome.SUCCESS, null, createdUserId);
    }

    /** @throws AuditCompletionException if the record was not set to FAILED */
    public void markFailed(String auditLogId, String failureCode) {
        completeOnce(auditLogId, AuditOutcome.FAILED, failureCode, null);
    }

    /**
     * Sets the outcome only while the record is still PENDING (atomic conditional update), so a record
     * can be completed once and never changed again.
     *
     * <p>Strict, not idempotent: exactly one record must change. Each workflow completes its record once,
     * so a record that is already completed or missing at that point is an anomaly (a second writer or a
     * lost record), and the caller must be told so it can write a reconciliation record.</p>
     */
    private void completeOnce(String auditLogId, AuditOutcome outcome, String failureCode, String targetUserId) {
        Query stillPending = Query.query(Criteria.where(AdminAuditLog.FIELD_ID).is(auditLogId)
                .and(AdminAuditLog.FIELD_OUTCOME).is(AuditOutcome.PENDING));
        Update update = new Update()
                .set(AdminAuditLog.FIELD_OUTCOME, outcome)
                .set(AdminAuditLog.FIELD_COMPLETED_AT, clock.instant());
        if (failureCode != null) {
            update.set(AdminAuditLog.FIELD_FAILURE_CODE, failureCode);
        }
        if (targetUserId != null) {
            update.set(AdminAuditLog.FIELD_TARGET_USER_ID, targetUserId);
        }
        long modified;
        try {
            modified = mongoTemplate.updateFirst(stillPending, update, AdminAuditLog.class).getModifiedCount();
        } catch (RuntimeException e) {
            throw new AuditCompletionException(auditLogId, outcome, AuditCompletionException.Reason.DATABASE_ERROR, e);
        }
        if (modified == 0) {
            throw new AuditCompletionException(auditLogId, outcome, AuditCompletionException.Reason.NOT_PENDING, null);
        }
        if (modified != 1) {
            throw new AuditCompletionException(auditLogId, outcome, AuditCompletionException.Reason.UNEXPECTED_COUNT, null);
        }
    }

    /**
     * Newest first, then by record id in the same direction, so records with the same createdAt keep a stable
     * order across pages. Every filter is optional; dates are ISO-8601 instants, e.g. 2026-11-01T00:00:00Z.
     */
    @PreAuthorize(SuperAdminAccess.SUPER_ADMIN_AREA_EDIT)
    public AdminPageResponse<AdminAuditLogResponse> search(String actorId, String targetUserId, String action,
                                                           String from, String to, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw AdminErrors.badRequest(AdminErrors.INVALID_FILTER,
                    "page must be 0 or more and size must be between 1 and " + MAX_PAGE_SIZE);
        }

        Criteria criteria = new Criteria();
        if (hasText(actorId)) {
            criteria.and(AdminAuditLog.FIELD_ACTOR_ID).is(checkIdFilter("actorId", actorId));
        }
        if (hasText(targetUserId)) {
            criteria.and(AdminAuditLog.FIELD_TARGET_USER_ID).is(checkIdFilter("targetUserId", targetUserId));
        }
        if (hasText(action)) {
            criteria.and(AdminAuditLog.FIELD_ACTION).is(parseAction(action));
        }
        Instant fromInstant = hasText(from) ? parseInstant("from", from) : null;
        Instant toInstant = hasText(to) ? parseInstant("to", to) : null;
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw AdminErrors.badRequest(AdminErrors.INVALID_FILTER, "from must not be after to");
        }
        if (fromInstant != null || toInstant != null) {
            Criteria createdAt = criteria.and(AdminAuditLog.FIELD_CREATED_AT);
            if (fromInstant != null) {
                createdAt.gte(fromInstant);
            }
            if (toInstant != null) {
                createdAt.lte(toInstant);
            }
        }

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, AdminAuditLog.FIELD_CREATED_AT, AdminAuditLog.FIELD_ID));
        List<AdminAuditLog> content = mongoTemplate.find(new Query(criteria).with(pageable), AdminAuditLog.class);
        Page<AdminAuditLog> result = PageableExecutionUtils.getPage(content, pageable,
                () -> mongoTemplate.count(new Query(criteria), AdminAuditLog.class));
        return AdminPageResponse.from(result.map(AdminAuditLogResponse::from));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String checkIdFilter(String name, String value) {
        String trimmed = value.strip();
        if (trimmed.length() > MAX_ID_FILTER_LENGTH) {
            throw AdminErrors.badRequest(AdminErrors.INVALID_FILTER, name + " is too long");
        }
        return trimmed;
    }

    private static AdminAuditAction parseAction(String action) {
        try {
            return AdminAuditAction.valueOf(action.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AdminErrors.badRequest(AdminErrors.INVALID_FILTER, "Unknown audit action");
        }
    }

    private static Instant parseInstant(String name, String value) {
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw AdminErrors.badRequest(AdminErrors.INVALID_FILTER,
                    name + " must be an ISO-8601 instant, e.g. 2026-11-01T00:00:00Z");
        }
    }
}
