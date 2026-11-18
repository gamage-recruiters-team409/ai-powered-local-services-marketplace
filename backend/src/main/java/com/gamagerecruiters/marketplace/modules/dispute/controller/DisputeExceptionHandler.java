package com.gamagerecruiters.marketplace.modules.dispute.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.gamagerecruiters.marketplace.common.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Dispute-only handler for bad query parameters (for example {@code ?page=abc}), which the shared
 * GlobalExceptionHandler would otherwise report as a 500. Same ErrorResponse shape as everywhere else.
 */
@RestControllerAdvice(assignableTypes = {DisputeController.class, AdminDisputeController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DisputeExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request
    ) {
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_PARAMETER",
                "Invalid value for parameter '" + ex.getName() + "'",
                request.getRequestURI()
        );
        return ResponseEntity.badRequest().body(response);
    }
}
