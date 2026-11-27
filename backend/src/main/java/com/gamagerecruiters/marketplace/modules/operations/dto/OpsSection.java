package com.gamagerecruiters.marketplace.modules.operations.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One part of an operations response that depends on an external source (WP09). When {@code available = false} there
 * are NO rows and NO numbers — only a human-readable {@code reason} naming the missing dependency. Mirrors the admin
 * users {@code Section} pattern but carries a free-text reason so it can name the exact missing WP05 method.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpsSection<T>(boolean available, String reason, T data) {

    public static <T> OpsSection<T> of(T data) {
        return new OpsSection<>(true, null, data);
    }

    public static <T> OpsSection<T> unavailable(String reason) {
        return new OpsSection<>(false, reason, null);
    }
}
