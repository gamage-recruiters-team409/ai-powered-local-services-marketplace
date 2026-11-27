package com.gamagerecruiters.marketplace.modules.operations.dto;

import java.util.List;

/** DTO for the Support dashboard (WP09). Each part carries its own availability. */
public final class OperationsDashboard {

    private OperationsDashboard() {
    }

    public record Response(
            OpsSection<Long> overdueCount,
            OpsSection<Long> lateCount,
            OpsSection<Long> flaggedUsersCount,
            OpsSection<OverdueBookings.Distribution> overdueDistribution,
            OpsSection<List<DayCount>> latePerDay,
            OpsSection<List<OverdueBookings.Row>> mostOverdue) {
    }
}
