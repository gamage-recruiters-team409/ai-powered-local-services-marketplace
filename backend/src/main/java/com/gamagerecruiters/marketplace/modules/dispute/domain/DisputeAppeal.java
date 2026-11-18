package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * The single appeal against an admin decision, embedded in the dispute, plus the reviewing admin's verdict.
 * {@code reviewedBy} and {@code reviewNote} are internal and never shown to the parties.
 */
public class DisputeAppeal {

    private String appealedBy;
    private Role appealedByRole;
    private String grounds;
    private List<String> evidence = new ArrayList<>();
    private Instant filedAt;
    private Instant reviewDueAt;
    private AppealVerdict verdict;
    private String reviewedBy;
    private Role reviewedByRole;
    private Instant reviewedAt;
    private String reviewNote;

    public String getAppealedBy() {
        return appealedBy;
    }

    public void setAppealedBy(String appealedBy) {
        this.appealedBy = appealedBy;
    }

    public Role getAppealedByRole() {
        return appealedByRole;
    }

    public void setAppealedByRole(Role appealedByRole) {
        this.appealedByRole = appealedByRole;
    }

    public String getGrounds() {
        return grounds;
    }

    public void setGrounds(String grounds) {
        this.grounds = grounds;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence == null ? new ArrayList<>() : new ArrayList<>(evidence);
    }

    public Instant getFiledAt() {
        return filedAt;
    }

    public void setFiledAt(Instant filedAt) {
        this.filedAt = filedAt;
    }

    public Instant getReviewDueAt() {
        return reviewDueAt;
    }

    public void setReviewDueAt(Instant reviewDueAt) {
        this.reviewDueAt = reviewDueAt;
    }

    public AppealVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(AppealVerdict verdict) {
        this.verdict = verdict;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Role getReviewedByRole() {
        return reviewedByRole;
    }

    public void setReviewedByRole(Role reviewedByRole) {
        this.reviewedByRole = reviewedByRole;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }
}
