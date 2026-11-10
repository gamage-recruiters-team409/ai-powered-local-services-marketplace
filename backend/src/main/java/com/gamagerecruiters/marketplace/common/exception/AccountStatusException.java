package com.gamagerecruiters.marketplace.common.exception;

import org.springframework.http.HttpStatus;

public class AccountStatusException extends ApiException {

    public AccountStatusException(String errorCode, String message) {
        super(HttpStatus.FORBIDDEN, errorCode, message);
    }
}
