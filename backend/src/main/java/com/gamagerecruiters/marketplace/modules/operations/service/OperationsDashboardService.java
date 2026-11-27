package com.gamagerecruiters.marketplace.modules.operations.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.operations.dto.DayCount;
import com.gamagerecruiters.marketplace.modules.operations.dto.LateCases;
import com.gamagerecruiters.marketplace.modules.operations.dto.OperationsDashboard;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsSection;
import com.gamagerecruiters.marketplace.modules.operations.dto.OverdueBookings;
import com.gamagerecruiters.marketplace.modules.operations.dto.RepeatCancellations;

/** Composes the Support dashboard from the three screen services. Each part carries its own availability. */
@Service
public class OperationsDashboardService {

    private static final int MOST_OVERDUE = 5;

    private final OverdueBookingsService overdueService;
    private final LateCasesService lateCasesService;
    private final RepeatCancellationsService repeatService;

    public OperationsDashboardService(OverdueBookingsService overdueService, LateCasesService lateCasesService,
                                      RepeatCancellationsService repeatService) {
        this.overdueService = overdueService;
        this.lateCasesService = lateCasesService;
        this.repeatService = repeatService;
    }

    public OperationsDashboard.Response dashboard() {
        OpsSection<OverdueBookings.Data> overdue = overdueService.screen(null, null, 0, MOST_OVERDUE);
        OpsSection<Long> overdueCount = overdue.available()
                ? OpsSection.of(overdue.data().summary().overdueNow())
                : OpsSection.unavailable(overdue.reason());
        OpsSection<OverdueBookings.Distribution> distribution = overdue.available()
                ? OpsSection.of(overdue.data().distribution())
                : OpsSection.unavailable(overdue.reason());
        OpsSection<List<OverdueBookings.Row>> mostOverdue = overdue.available()
                ? OpsSection.of(overdue.data().rows().content())
                : OpsSection.unavailable(overdue.reason());

        OpsSection<LateCases.LateStartsData> late = lateCasesService.lateStartsSection(null, null, 0, MOST_OVERDUE);
        OpsSection<Long> lateCount = late.available()
                ? OpsSection.of(late.data().summary().open())
                : OpsSection.unavailable(late.reason());
        OpsSection<List<DayCount>> latePerDay = late.available()
                ? OpsSection.of(late.data().perDay())
                : OpsSection.unavailable(late.reason());

        OpsSection<RepeatCancellations.Data> repeat = repeatService.screen(null, null, 0, 1);
        OpsSection<Long> flaggedUsers = repeat.available()
                ? OpsSection.of(repeat.data().summary().flagged())
                : OpsSection.unavailable(repeat.reason());

        return new OperationsDashboard.Response(overdueCount, lateCount, flaggedUsers, distribution, latePerDay,
                mostOverdue);
    }
}
