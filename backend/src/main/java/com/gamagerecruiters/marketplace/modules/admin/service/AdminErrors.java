package com.gamagerecruiters.marketplace.modules.admin.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ApiException;

/**
 * The ONE place for admin-module error codes and for reading error codes coming from the auth module.
 *
 * <p>Agreed status codes: 403 for data-independent rules (self status change), 409 only for the last
 * active SUPER_ADMIN, 400 for disallowed target statuses and validation. Errors thrown by the auth
 * module are never mapped by message text: they are re-thrown unchanged, so the response keeps the
 * auth module's own status and "error" code. When the auth module's codes change (for example a 409 for
 * the last active SUPER_ADMIN), nothing here needs to change.</p>
 */
public final class AdminErrors {

    /** 400: target status other than ACTIVE or SUSPENDED. */
    public static final String STATUS_NOT_ALLOWED = "STATUS_NOT_ALLOWED";

    /** 400: the account already has the requested status. */
    public static final String STATUS_UNCHANGED = "STATUS_UNCHANGED";

    /** 400: reason missing or outside 10..500 characters after trimming. */
    public static final String INVALID_REASON = "INVALID_REASON";

    /** 403: an admin tried to change their own status. */
    public static final String SELF_STATUS_CHANGE = "SELF_STATUS_CHANGE";

    /** 404: the user exists but is not an admin account (customer/provider management is a separate task). */
    public static final String ADMIN_ACCOUNT_NOT_FOUND = "ADMIN_ACCOUNT_NOT_FOUND";

    /** 400: invalid audit-log filter or paging parameter. */
    public static final String INVALID_FILTER = "INVALID_FILTER";

    /** Audit failure code for an AccessDeniedException thrown by the auth module. */
    static final String ACCESS_DENIED = "ACCESS_DENIED";

    /** Audit failure code for any unexpected error. */
    static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";

    private AdminErrors() {
    }

    /** The error code to record on a FAILED audit record, read from the exception type (never its message). */
    public static String failureCodeOf(Throwable error) {
        if (error instanceof ApiException apiException && apiException.getErrorCode() != null) {
            return apiException.getErrorCode();
        }
        if (error instanceof AccessDeniedException) {
            return ACCESS_DENIED;
        }
        return UNEXPECTED_ERROR;
    }

    static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }
}
