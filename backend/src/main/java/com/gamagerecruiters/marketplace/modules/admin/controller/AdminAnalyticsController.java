package com.gamagerecruiters.marketplace.modules.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAnalyticsSummaryResponse;
import com.gamagerecruiters.marketplace.modules.admin.security.SuperAdminAccess;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAnalyticsService;

/**
 * Analytics and dashboard metrics controller (FR-ADM-001 / SRS 13.1).
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/admin/analytics")
@PreAuthorize(SuperAdminAccess.SUPER_ADMIN_AREA_EDIT)
public class AdminAnalyticsController {

    private final AdminAnalyticsService analyticsService;

    public AdminAnalyticsController(AdminAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/summary")
    public ResponseEntity<AdminAnalyticsSummaryResponse> getSummary() {
        return ResponseEntity.ok(analyticsService.getSummary());
    }
}
