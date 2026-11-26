package com.gamagerecruiters.marketplace.modules.operations.port.adapter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;
import com.gamagerecruiters.marketplace.modules.operations.port.NoShowDisputesPort;

/** Real No-show source backed by the dispute module's {@link DisputeService} (NO_SHOW category, real status). */
@Component
public class DisputeServiceNoShowAdapter implements NoShowDisputesPort {

    private static final Logger log = LoggerFactory.getLogger(DisputeServiceNoShowAdapter.class);

    private final DisputeService disputeService;

    public DisputeServiceNoShowAdapter(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @Override
    public Result findNoShowDisputes(int limit) {
        try {
            List<NoShowDispute> rows = disputeService
                    .getDisputesForAdminByCategory(DisputeCategory.NO_SHOW, 0, Math.max(1, limit))
                    .content().stream()
                    .map(DisputeServiceNoShowAdapter::toRow)
                    .toList();
            return Result.available(rows);
        } catch (RuntimeException e) {
            log.warn("event=OPS_NO_SHOW_SOURCE_FAILED cause={}", e.getClass().getSimpleName());
            return Result.unavailable("No-show dispute source failed for this request (dispute module).");
        }
    }

    private static NoShowDispute toRow(DisputeSummaryResponse d) {
        return new NoShowDispute(d.id(), d.bookingId(), d.customerId(), d.providerId(),
                d.status() == null ? null : d.status().name(), d.createdAt());
    }
}
