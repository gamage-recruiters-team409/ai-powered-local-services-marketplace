package com.gamagerecruiters.marketplace.modules.announcement.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.gamagerecruiters.marketplace.common.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Announcement-only handlers, same ErrorResponse shape as everywhere else: bad query parameters (for example
 * {@code ?page=abc}, otherwise a 500) and any optimistic-locking failure (409 ANNOUNCEMENT_UPDATE_CONFLICT).
 */
@RestControllerAdvice(assignableTypes = AdminAnnouncementController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AnnouncementExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "INVALID_PARAMETER",
                "Invalid value for parameter '" + ex.getName() + "'", request.getRequestURI()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                              HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(HttpStatus.CONFLICT.value(),
                "ANNOUNCEMENT_UPDATE_CONFLICT", "The announcement was changed by another request. Reload it and try again",
                request.getRequestURI()));
    }
}
