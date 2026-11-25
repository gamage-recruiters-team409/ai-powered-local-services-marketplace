package com.gamagerecruiters.marketplace.modules.adminusers.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.gamagerecruiters.marketplace.common.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

/** Bad query parameters (for example {@code ?page=abc}) answer 400 INVALID_PARAMETER instead of 500. */
@RestControllerAdvice(assignableTypes = AdminUsersController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminUsersExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "INVALID_PARAMETER",
                "Invalid value for parameter '" + ex.getName() + "'", request.getRequestURI()));
    }
}
