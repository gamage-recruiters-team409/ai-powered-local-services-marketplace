package com.gamagerecruiters.marketplace.modules.admin.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ApiException;

/**
 * The ONE place for admin-module error codes and for reading error codes coming from the auth module.
 *
 * <p>Errors thrown by the auth module are never mapped by message text: they are re-thrown unchanged, so
 * the response keeps the auth module's own status and "error" code (for example 403 CANNOT_MODIFY_SELF,
 * ROLE_HIERARCHY_VIOLATION, ACTOR_ACCOUNT_INACTIVE, ACCOUNT_DEACTIVATED, 409 LAST_ACTIVE_SUPER_ADMIN,
 * 409 EMAIL_ALREADY_EXISTS). The codes below are the admin module's own.</p>
 */
public final class AdminErrors {

    /** 400: target status other than ACTIVE or SUSPENDED. */
    public static final String STATUS_NOT_ALLOWED = "STATUS_NOT_ALLOWED";

    /** 400: the account already has the requested status. */
    public static final String STATUS_UNCHANGED = "STATUS_UNCHANGED";

    /** 400: a request value failed validation (same code as Bean Validation failures). */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** 400: an admin account can only be created with ADMIN, SUPPORT_ADMIN, MODERATOR or FINANCE_ADMIN. */
    public static final String ROLE_NOT_ALLOWED = "ROLE_NOT_ALLOWED";

    /** 404: the user exists but is not an admin account (customer/provider management is a separate task). */
    public static final String ADMIN_ACCOUNT_NOT_FOUND = "ADMIN_ACCOUNT_NOT_FOUND";

    /** 400: invalid filter value (unknown role/status/action, bad date, text too long). */
    public static final String INVALID_FILTER = "INVALID_FILTER";

    /** 400: invalid paging or sort parameter, or a parameter of the wrong type. */
    public static final String INVALID_PARAMETER = "INVALID_PARAMETER";

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

    static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }
}
