package com.gamagerecruiters.marketplace.modules.operations.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One entry in the operational handling log ({@code ops_case_actions}, WP09). Append-only: the current status of a case
 * is the {@link #caseStatus} of its most recent action for the same {@code (caseType, subjectId)} (default OPEN when
 * none). Stores ids and the admin's note only — never emails, phone numbers or other personal details.
 */
@Document(collection = OpsCaseAction.COLLECTION)
public class OpsCaseAction {

    public static final String COLLECTION = "ops_case_actions";

    @Id
    private String id;

    private OpsCaseType caseType;

    /** The booking id (overdue / late cases) or user id (repeat cancellations) the action is about. */
    private String subjectId;

    private OpsActionType action;

    /** The admin's required note (10–1000 characters). */
    private String note;

    /** The signed-in admin who performed the action. */
    private String actorId;

    private OpsActorArea actorArea;

    private Instant createdAt;

    /** The case status after this action (latest action wins). */
    private OpsCaseStatus caseStatus;

    public OpsCaseAction() {
    }

    public OpsCaseAction(OpsCaseType caseType, String subjectId, OpsActionType action, String note, String actorId,
                         OpsActorArea actorArea, Instant createdAt, OpsCaseStatus caseStatus) {
        this.caseType = caseType;
        this.subjectId = subjectId;
        this.action = action;
        this.note = note;
        this.actorId = actorId;
        this.actorArea = actorArea;
        this.createdAt = createdAt;
        this.caseStatus = caseStatus;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public OpsCaseType getCaseType() {
        return caseType;
    }

    public void setCaseType(OpsCaseType caseType) {
        this.caseType = caseType;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public OpsActionType getAction() {
        return action;
    }

    public void setAction(OpsActionType action) {
        this.action = action;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public OpsActorArea getActorArea() {
        return actorArea;
    }

    public void setActorArea(OpsActorArea actorArea) {
        this.actorArea = actorArea;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public OpsCaseStatus getCaseStatus() {
        return caseStatus;
    }

    public void setCaseStatus(OpsCaseStatus caseStatus) {
        this.caseStatus = caseStatus;
    }
}
