package com.gamagerecruiters.marketplace.modules.adminusers.domain;

import java.util.Locale;
import java.util.Optional;

/** Why an account is suspended. OTHER requires an administrator note. */
public enum SuspensionReason {
    REPEATED_CANCELLATIONS,
    POLICY_VIOLATION,
    SUSPECTED_FRAUD,
    ABUSIVE_BEHAVIOUR,
    OFF_PLATFORM_CONTACT,
    OTHER;

    public static Optional<SuspensionReason> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The audit log stores one free-text reason. A suspension is stored as {@code CODE} or {@code CODE: note}, so the
     * account page can show the reason label and the note separately; a reactivation stores the note only.
     */
    public static String auditText(SuspensionReason code, String note) {
        return note == null || note.isEmpty() ? code.name() : code.name() + ": " + note;
    }

    /** Reads back {@link #auditText}: the code, if the text starts with a known one. */
    public static Optional<SuspensionReason> codeOf(String auditText) {
        if (auditText == null) {
            return Optional.empty();
        }
        int colon = auditText.indexOf(": ");
        return parseExact(colon < 0 ? auditText : auditText.substring(0, colon));
    }

    /** The note part of {@link #auditText} (the whole text when it has no known code). */
    public static String noteOf(String auditText) {
        if (auditText == null) {
            return null;
        }
        if (parseExact(auditText).isPresent()) {
            return null;
        }
        int colon = auditText.indexOf(": ");
        if (colon > 0 && parseExact(auditText.substring(0, colon)).isPresent()) {
            return auditText.substring(colon + 2);
        }
        return auditText;
    }

    private static Optional<SuspensionReason> parseExact(String value) {
        for (SuspensionReason reason : values()) {
            if (reason.name().equals(value)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
