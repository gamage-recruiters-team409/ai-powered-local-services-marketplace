package com.gamagerecruiters.marketplace.modules.admin.dto;

import java.util.List;
import java.util.Map;

public record AdminReportResponse(
        String type,
        boolean available,
        String source,
        String reason,
        List<Map<String, Object>> rows,
        Map<String, Object> totals,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static AdminReportResponse unavailable(String type, String source, String reason) {
        return new AdminReportResponse(type, false, source, reason, List.of(), Map.of(), 0, 0, 0, 0);
    }

    public static AdminReportResponse available(String type, String source,
                                                List<Map<String, Object>> rows,
                                                Map<String, Object> totals,
                                                int page, int size, long totalElements, int totalPages) {
        return new AdminReportResponse(type, true, source, null, rows, totals, page, size, totalElements, totalPages);
    }
}
