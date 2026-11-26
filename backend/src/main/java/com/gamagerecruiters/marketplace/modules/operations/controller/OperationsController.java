package com.gamagerecruiters.marketplace.modules.operations.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OperationsRules;
import com.gamagerecruiters.marketplace.modules.operations.dto.LateCases;
import com.gamagerecruiters.marketplace.modules.operations.dto.OperationsDashboard;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsActionRequest;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsActionResult;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsSection;
import com.gamagerecruiters.marketplace.modules.operations.dto.OverdueBookings;
import com.gamagerecruiters.marketplace.modules.operations.dto.RepeatCancellations;
import com.gamagerecruiters.marketplace.modules.operations.security.OperationsAccess;
import com.gamagerecruiters.marketplace.modules.operations.service.OperationsCsvExporter;
import com.gamagerecruiters.marketplace.modules.operations.service.OperationsDashboardService;
import com.gamagerecruiters.marketplace.modules.operations.service.LateCasesService;
import com.gamagerecruiters.marketplace.modules.operations.service.OpsCaseActionService;
import com.gamagerecruiters.marketplace.modules.operations.service.OverdueBookingsService;
import com.gamagerecruiters.marketplace.modules.operations.service.RepeatCancellationsService;

/**
 * Support Admin operations (WP09). Reads: SUPPORT_ADMIN and ADMIN areas, SUPER_ADMIN read-only (others 403, no token
 * 401). Writes: SUPPORT_ADMIN and ADMIN; SUSPEND is ADMIN-only (enforced in the service). The actor is always the
 * signed-in admin, never a request value. No notifications are sent (record only).
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/operations")
@PreAuthorize(OperationsAccess.VIEW)
public class OperationsController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final OverdueBookingsService overdueService;
    private final LateCasesService lateCasesService;
    private final RepeatCancellationsService repeatService;
    private final OperationsDashboardService dashboardService;
    private final OperationsCsvExporter csvExporter;
    private final OpsCaseActionService actionService;

    public OperationsController(OverdueBookingsService overdueService, LateCasesService lateCasesService,
                                RepeatCancellationsService repeatService, OperationsDashboardService dashboardService,
                                OperationsCsvExporter csvExporter, OpsCaseActionService actionService) {
        this.overdueService = overdueService;
        this.lateCasesService = lateCasesService;
        this.repeatService = repeatService;
        this.dashboardService = dashboardService;
        this.csvExporter = csvExporter;
        this.actionService = actionService;
    }

    // ---- Overdue bookings ----

    @GetMapping("/overdue-bookings")
    public ResponseEntity<OpsSection<OverdueBookings.Data>> overdueBookings(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String delayBucket,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + OperationsRules.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(overdueService.screen(search, delayBucket, page, size));
    }

    @PostMapping("/overdue-bookings/{bookingId}/actions")
    @PreAuthorize(OperationsAccess.EDIT)
    public ResponseEntity<OpsActionResult> overdueAction(@PathVariable String bookingId,
                                                         @RequestBody OpsActionRequest request) {
        return ResponseEntity.ok(actionService.record(OpsCaseType.OVERDUE_BOOKING, bookingId, request.action(),
                request.note(), currentUserId(), CurrentUser.getRole()));
    }

    @GetMapping("/overdue-bookings/export.csv")
    public ResponseEntity<byte[]> overdueExport(@RequestParam(required = false) String search,
                                                 @RequestParam(required = false) String delayBucket) {
        return csv(csvExporter.overdue(currentUserId(), search, delayBucket));
    }

    // ---- Late cases ----

    @GetMapping("/late-cases")
    public ResponseEntity<LateCases.Response> lateCases(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String urgency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + OperationsRules.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(lateCasesService.screen(search, urgency, page, size));
    }

    @PostMapping("/late-cases/{caseId}/actions")
    @PreAuthorize(OperationsAccess.EDIT)
    public ResponseEntity<OpsActionResult> lateCaseAction(@PathVariable String caseId,
                                                          @RequestBody OpsActionRequest request) {
        return ResponseEntity.ok(actionService.record(OpsCaseType.LATE_CASE, caseId, request.action(),
                request.note(), currentUserId(), CurrentUser.getRole()));
    }

    @GetMapping("/late-cases/export.csv")
    public ResponseEntity<byte[]> lateCasesExport(@RequestParam(required = false) String search) {
        return csv(csvExporter.lateCases(currentUserId(), search));
    }

    // ---- Repeat cancellations ----

    @GetMapping("/repeat-cancellations")
    public ResponseEntity<OpsSection<RepeatCancellations.Data>> repeatCancellations(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + OperationsRules.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(repeatService.screen(search, role, page, size));
    }

    @PostMapping("/repeat-cancellations/{userId}/actions")
    @PreAuthorize(OperationsAccess.EDIT)
    public ResponseEntity<OpsActionResult> repeatCancellationAction(@PathVariable String userId,
                                                                    @RequestBody OpsActionRequest request) {
        return ResponseEntity.ok(actionService.record(OpsCaseType.REPEAT_CANCELLATION, userId, request.action(),
                request.note(), currentUserId(), CurrentUser.getRole()));
    }

    @GetMapping("/repeat-cancellations/export.csv")
    public ResponseEntity<byte[]> repeatCancellationsExport(@RequestParam(required = false) String search,
                                                            @RequestParam(required = false) String role) {
        return csv(csvExporter.repeatCancellations(currentUserId(), search, role));
    }

    // ---- Dashboard ----

    @GetMapping("/dashboard")
    public ResponseEntity<OperationsDashboard.Response> dashboard() {
        return ResponseEntity.ok(dashboardService.dashboard());
    }

    private ResponseEntity<byte[]> csv(OperationsCsvExporter.Export export) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .header("X-Export-Rows", String.valueOf(export.rows()))
                .header("X-Export-Truncated", String.valueOf(export.truncated()))
                .header("X-Export-Source-Limited", String.valueOf(export.sourceLimited()))
                .body(export.content());
    }

    private static String currentUserId() {
        String userId = CurrentUser.getUserId();
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User is not authenticated");
        }
        return userId;
    }
}
