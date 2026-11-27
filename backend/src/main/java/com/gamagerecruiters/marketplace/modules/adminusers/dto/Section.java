package com.gamagerecruiters.marketplace.modules.adminusers.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One part of an admin users response whose data comes from another module.
 * <ul>
 *   <li>{@code available = true}: {@code data} holds the real values.</li>
 *   <li>{@code available = false}: there is NO data (never zeros or made-up numbers) and {@code reason} says why:
 *       {@link #NOT_CONNECTED} (the source module has no method for it yet, a stub answered),
 *       {@link #NOT_APPLICABLE} (does not apply to this account, e.g. listings of a customer) or
 *       {@link #SOURCE_ERROR} (the source failed for this request).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Section<T>(boolean available, String reason, T data) {

    public static final String NOT_CONNECTED = "NOT_CONNECTED";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String SOURCE_ERROR = "SOURCE_ERROR";

    public static <T> Section<T> of(T data) {
        return new Section<>(true, null, data);
    }

    public static <T> Section<T> notConnected() {
        return new Section<>(false, NOT_CONNECTED, null);
    }

    public static <T> Section<T> notApplicable() {
        return new Section<>(false, NOT_APPLICABLE, null);
    }

    public static <T> Section<T> sourceError() {
        return new Section<>(false, SOURCE_ERROR, null);
    }
}
