package com.gamagerecruiters.marketplace.modules.operations.dto;

/**
 * Body of a case action POST. {@code action} is an {@code OpsActionType} name; {@code note} is required (10–1000 chars).
 * The actor is always the signed-in admin, never a request value. {@code reviewOn} is intentionally absent (no timed
 * suspension — TL 2026-11-26).
 */
public record OpsActionRequest(String action, String note) {
}
