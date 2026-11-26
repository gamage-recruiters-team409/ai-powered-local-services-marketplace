package com.gamagerecruiters.marketplace.modules.operations.service;

import java.time.Clock;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AccountActionResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUserAccountService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActionType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActorArea;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseAction;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OperationsRules;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsActionResult;

/**
 * Records operational case actions (WP09). Enforces the note rule, the per-case-type and per-role action rules, and
 * runs the Admin Users suspend flow for an ADMIN SUSPEND. Notifications are never sent here (record only — WP06 has no
 * user-warning type yet). Logs ids only, never the note.
 */
@Service
public class OpsCaseActionService {

    public static final String SUSPEND_REASON_CODE = "REPEATED_CANCELLATIONS";

    private static final Logger log = LoggerFactory.getLogger(OpsCaseActionService.class);

    private final OpsCaseActionStore store;
    private final OpsCaseEligibilityService eligibility;
    private final AdminUserAccountService adminUserAccountService;
    private final Clock clock;

    @Autowired
    public OpsCaseActionService(OpsCaseActionStore store, OpsCaseEligibilityService eligibility,
                                AdminUserAccountService adminUserAccountService) {
        this(store, eligibility, adminUserAccountService, Clock.systemUTC());
    }

    OpsCaseActionService(OpsCaseActionStore store, OpsCaseEligibilityService eligibility,
                         AdminUserAccountService adminUserAccountService, Clock clock) {
        this.store = store;
        this.eligibility = eligibility;
        this.adminUserAccountService = adminUserAccountService;
        this.clock = clock;
    }

    /**
     * Validates and records an action. {@code actorId} / {@code actorRole} are the signed-in admin (never request
     * values). Throws 400 for a bad note / action, 403 when a SUPPORT_ADMIN attempts an ADMIN-only action, and
     * propagates the Admin Users suspend errors (including 409) for a SUSPEND.
     */
    public OpsActionResult record(OpsCaseType caseType, String subjectId, String actionName, String note,
                                  String actorId, Role actorRole) {
        OpsActorArea actorArea = OpsActorArea.fromRole(actorRole)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "OPS_FORBIDDEN",
                        "This admin area may not perform operations actions"));
        OpsActionType action = parseAction(actionName);
        String cleanNote = validateNote(note, action);

        if (!action.appliesTo(caseType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OPS_ACTION_NOT_ALLOWED_FOR_CASE",
                    "Action " + action + " is not allowed for " + caseType + " cases");
        }
        if (action.adminOnly() && actorArea != OpsActorArea.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OPS_SUPPORT_CANNOT_PERFORM_ACTION",
                    "Only the ADMIN area may perform " + action);
        }

        // Verify the subject is a real, eligible case against the authoritative source before any mutation (fail
        // closed when the source is unavailable). Guards SUSPEND against accounts that are not repeat-cancellation cases.
        eligibility.assertEligible(caseType, subjectId);

        boolean suspensionApplied = false;
        Boolean listingVisibilitySynced = null;
        if (action == OpsActionType.SUSPEND) {
            // Runs the existing Admin Users suspend flow (its audit, 409 handling and listing-visibility cascade).
            // The AccountActionResponse carries the authoritative listing-visibility sync status.
            AccountActionResponse suspendResponse =
                    adminUserAccountService.suspend(actorId, actorRole, subjectId, SUSPEND_REASON_CODE, cleanNote);
            suspensionApplied = suspendResponse.changed();
            listingVisibilitySynced = suspendResponse.listingVisibilitySynced();
        }

        OpsCaseAction recorded;
        try {
            recorded = store.insert(new OpsCaseAction(caseType, subjectId, action, cleanNote, actorId,
                    actorArea, clock.instant(), action.resultingStatus()));
        } catch (RuntimeException e) {
            if (!suspensionApplied) {
                throw e; // No side-effect to reconcile: propagate as-is.
            }
            // Suspension succeeded but ops log failed: retry once before accepting partial success.
            log.error("event=OPS_RECORD_FAILED_AFTER_SUSPEND caseType={} subjectId={} actorId={} cause={}",
                    caseType, subjectId, actorId, e.getClass().getSimpleName());
            try {
                recorded = store.insert(new OpsCaseAction(caseType, subjectId, action, cleanNote, actorId,
                        actorArea, clock.instant(), action.resultingStatus()));
            } catch (RuntimeException retryEx) {
                log.error("event=OPS_RECORD_RETRY_FAILED caseType={} subjectId={} actorId={} cause={}",
                        caseType, subjectId, actorId, retryEx.getClass().getSimpleName());
                // Return partial success: the suspension is authoritative (Admin Users audit proves it happened),
                // and the caller must not interpret this as a no-op. The opsRecordPending flag signals that the
                // operations log needs reconciliation. Never roll back a legitimately applied suspension.
                return new OpsActionResult(caseType, subjectId, action, action.resultingStatus(),
                        clock.instant(), true, listingVisibilitySynced, true);
            }
        }

        log.info("event=OPS_CASE_ACTION_RECORDED caseType={} subjectId={} action={} area={} actorId={} status={}",
                caseType, subjectId, action, actorArea, actorId, recorded.getCaseStatus());

        return new OpsActionResult(caseType, subjectId, action, recorded.getCaseStatus(), recorded.getCreatedAt(),
                suspensionApplied, listingVisibilitySynced, false);
    }

    private static OpsActionType parseAction(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OPS_ACTION_REQUIRED", "An action is required");
        }
        try {
            return OpsActionType.valueOf(actionName.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OPS_INVALID_ACTION", "Unknown action: " + actionName);
        }
    }

    private static String validateNote(String note, OpsActionType action) {
        String trimmed = note == null ? "" : note.strip();
        int max = action == OpsActionType.SUSPEND ? 500 : OperationsRules.NOTE_MAX;
        if (trimmed.length() < OperationsRules.NOTE_MIN || trimmed.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OPS_INVALID_NOTE",
                    "Note must be " + OperationsRules.NOTE_MIN + "–" + max + " characters");
        }
        return trimmed;
    }
}
