package com.gamagerecruiters.marketplace.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceConflictException extends ApiException {

    public ResourceConflictException(String message) {
        super(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", message);
    }

    public ResourceConflictException(String errorCode, String message) {
        super(HttpStatus.CONFLICT, errorCode, message);
    }
}
