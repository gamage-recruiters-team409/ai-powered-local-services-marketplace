package com.gamagerecruiters.marketplace.modules.admin.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminReportResponse;
import com.gamagerecruiters.marketplace.modules.admin.security.SuperAdminAccess;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminReportsService;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;

/**
 * Super Admin Reports controller (SRS 13.3 / 13.5).
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/reports")
@PreAuthorize(SuperAdminAccess.SUPER_ADMIN_AREA_EDIT)
public class AdminReportsController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final AdminReportsService reportsService;

    public AdminReportsController(AdminReportsService reportsService) {
        this.reportsService = reportsService;
    }

    @GetMapping("/{type}")
    public ResponseEntity<?> getReport(
            @PathVariable String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String format) {

        if ("csv".equalsIgnoreCase(format)) {
            AdminReportsService.CsvExport export = reportsService.exportReportCsv(actorId(), type, from, to);
            return ResponseEntity.ok()
                    .contentType(CSV)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.filename() + "\"")
                    .header("X-Export-Rows", String.valueOf(export.rows()))
                    .body(export.bytes());
        }

        return ResponseEntity.ok(reportsService.generateReport(type, from, to, page, size));
    }

    private static String actorId() {
        String actorId = CurrentUser.getUserId();
        if (actorId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User is not authenticated");
        }
        return actorId;
    }
}
