package com.gamagerecruiters.marketplace.modules.adminusers.port.adapter;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAuditLogService;
import com.gamagerecruiters.marketplace.modules.admin.service.AuditCompletionException;
import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort;

/**
 * AccountActionAuditPort through AdminAuditLogService, with the same flow as the Super Admin account screens:
 * PENDING record with a new correlationId (action ADMIN_STATUS_CHANGED, reason, from/to status), completed once.
 * If an applied change cannot be marked SUCCESS it is never repeated or rolled back: an insert-only
 * AUDIT_RECONCILIATION_REQUIRED record and an ERROR log (ids and codes only) are written instead.
 */
@Component
public class AdminAuditLogAccountActionAdapter implements AccountActionAuditPort {

    static final String AUDIT_COMPLETION_FAILED = "AUDIT_COMPLETION_FAILED";

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLogAccountActionAdapter.class);

    private final AdminAuditLogService auditLogService;
    private final Clock clock = Clock.systemUTC();

    public AdminAuditLogAccountActionAdapter(AdminAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public Ticket begin(StatusChange change) {
        AdminAuditLog pending = auditLogService.recordPending(AdminAuditLog.pending(
                UUID.randomUUID().toString(), change.actorId(), change.actorRole(),
                AdminAuditAction.ADMIN_STATUS_CHANGED, change.targetUserId(), change.targetRole(),
                change.fromStatus(), change.toStatus(), change.reason(), clock.instant()));
        return new AuditTicket(pending);
    }

    @Override
    public Optional<List<AdminAction>> recentForTarget(String targetUserId, int limit) {
        return Optional.of(auditLogService.recentStatusChangesForTarget(targetUserId, limit).stream()
                .map(r -> new AdminAction(r.getId(), r.getActorId(), r.getAction().name(), r.getFromStatus(),
                        r.getToStatus(), r.getReason(), r.getOutcome() == null ? null : r.getOutcome().name(),
                        r.getCreatedAt()))
                .toList());
    }

    private final class AuditTicket implements Ticket {

        private final AdminAuditLog pending;

        private AuditTicket(AdminAuditLog pending) {
            this.pending = pending;
        }

        @Override
        public String auditLogId() {
            return pending.getId();
        }

        @Override
        public void succeeded() {
            try {
                auditLogService.markSuccess(pending.getId());
            } catch (RuntimeException e) {
                String reconciliationId = null;
                try {
                    reconciliationId = auditLogService.recordReconciliation(pending, pending.getTargetUserId(),
                            AUDIT_COMPLETION_FAILED).getId();
                } catch (RuntimeException insertError) {
                    log.error("event=AUDIT_RECONCILIATION_INSERT_FAILED correlationId={} auditLogId={} actorId={} "
                                    + "targetUserId={} cause={}", pending.getCorrelationId(), pending.getId(),
                            pending.getActorId(), pending.getTargetUserId(), causeType(insertError));
                }
                log.error("event=AUDIT_COMPLETION_FAILED correlationId={} auditLogId={} actorId={} targetUserId={} "
                                + "reconciliationId={} cause={}", pending.getCorrelationId(), pending.getId(),
                        pending.getActorId(), pending.getTargetUserId(), reconciliationId, causeType(e));
            }
        }

        @Override
        public void failed(String failureCode) {
            try {
                auditLogService.markFailed(pending.getId(), failureCode);
            } catch (RuntimeException e) {
                log.error("event=AUDIT_FAILED_MARK_FAILED auditLogId={} failureCode={} cause={}",
                        pending.getId(), failureCode, causeType(e));
            }
        }
    }

    private static String causeType(RuntimeException e) {
        if (e instanceof AuditCompletionException completion) {
            return completion.getReason().name();
        }
        return e.getClass().getSimpleName();
    }
}
