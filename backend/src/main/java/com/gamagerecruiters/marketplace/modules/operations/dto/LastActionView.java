package com.gamagerecruiters.marketplace.modules.operations.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActionType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActorArea;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseAction;

/** The most recent action on a case, shown in list rows. Null when no action has been recorded. */
public record LastActionView(OpsActionType action, OpsActorArea actorArea, Instant at) {

    public static LastActionView from(OpsCaseAction action) {
        if (action == null) {
            return null;
        }
        return new LastActionView(action.getAction(), action.getActorArea(), action.getCreatedAt());
    }
}
