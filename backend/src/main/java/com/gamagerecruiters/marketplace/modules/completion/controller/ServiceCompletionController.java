package com.gamagerecruiters.marketplace.modules.completion.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.completion.dto.MarkCompletedRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.NoShowReportResponse;
import com.gamagerecruiters.marketplace.modules.completion.dto.RaiseIssueRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ReportNoShowRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ServiceCompletionResponse;
import com.gamagerecruiters.marketplace.modules.completion.service.ServiceCompletionService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/bookings/{bookingId}/completion")
public class ServiceCompletionController {

    private final ServiceCompletionService completionService;

    public ServiceCompletionController(ServiceCompletionService completionService) {
        this.completionService = completionService;
    }

    @PostMapping("/start")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ServiceCompletionResponse> startService(@PathVariable String bookingId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(completionService.startService(bookingId, CurrentUser.getUserId()));
    }

    @PostMapping("/mark-completed")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ServiceCompletionResponse> markCompleted(
            @PathVariable String bookingId,
            @Valid @RequestBody MarkCompletedRequest request) {
        return ResponseEntity.ok(completionService.markCompleted(bookingId, CurrentUser.getUserId(), request));
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ServiceCompletionResponse> confirmCompletion(@PathVariable String bookingId) {
        return ResponseEntity.ok(completionService.confirmCompletion(bookingId, CurrentUser.getUserId()));
    }

    @PostMapping("/raise-issue")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ServiceCompletionResponse> raiseIssue(
            @PathVariable String bookingId,
            @Valid @RequestBody RaiseIssueRequest request) {
        return ResponseEntity.ok(completionService.raiseIssue(bookingId, CurrentUser.getUserId(), request));
    }

    @PostMapping("/no-show")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'PROVIDER')")
    public ResponseEntity<NoShowReportResponse> reportNoShow(
            @PathVariable String bookingId,
            @Valid @RequestBody(required = false) ReportNoShowRequest request) {
        return ResponseEntity.ok(completionService.reportNoShow(bookingId, CurrentUser.getUserId(), request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'PROVIDER')")
    public ResponseEntity<ServiceCompletionResponse> getCompletion(@PathVariable String bookingId) {
        return ResponseEntity.ok(completionService.getCompletion(bookingId, CurrentUser.getUserId()));
    }
}
