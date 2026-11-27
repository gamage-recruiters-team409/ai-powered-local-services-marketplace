package com.gamagerecruiters.marketplace.modules.operations.port.adapter;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;
import com.gamagerecruiters.marketplace.modules.operations.port.NoShowDisputesPort;

/**
 * Real No-show source backed by the dispute module's {@link DisputeService} (NO_SHOW category, real status).
 *
 * <p>Paginates within the dispute service contract: each call requests at most {@link DisputeRules#MAX_PAGE_SIZE}
 * rows and follows subsequent pages until {@code limit} rows are gathered or the data is exhausted — so more than one
 * page of NO_SHOW disputes can be traversed without ever exceeding the allowed page size (finding F1). A client/param
 * error from the dispute service (4xx, e.g. INVALID_PAGE_REQUEST) is a programming error and is surfaced, not masked as
 * an unavailable dependency; only a genuine source failure reports {@code available = false}.</p>
 */
@Component
public class DisputeServiceNoShowAdapter implements NoShowDisputesPort {

    private static final Logger log = LoggerFactory.getLogger(DisputeServiceNoShowAdapter.class);

    private final DisputeService disputeService;

    public DisputeServiceNoShowAdapter(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @Override
    public Result findNoShowDisputes(int limit) {
        int cap = Math.max(1, limit);
        int pageSize = Math.min(cap, DisputeRules.MAX_PAGE_SIZE);
        List<NoShowDispute> rows = new ArrayList<>();
        int page = 0;
        try {
            while (true) {
                DisputePageResponse<DisputeSummaryResponse> response =
                        disputeService.getDisputesForAdminByCategory(DisputeCategory.NO_SHOW, page, pageSize);
                for (DisputeSummaryResponse dispute : response.content()) {
                    rows.add(toRow(dispute));
                    if (rows.size() >= cap) {
                        return Result.available(rows);
                    }
                }
                page++;
                if (page >= response.totalPages()) {
                    break;
                }
            }
            return Result.available(rows);
        } catch (ApiException e) {
            // Our own invalid query parameters (4xx) are a bug — surface them rather than hiding a programming error.
            if (e.getStatus() != null && e.getStatus().is4xxClientError()) {
                throw e;
            }
            log.warn("event=OPS_NO_SHOW_SOURCE_FAILED status={} code={}", e.getStatus(), e.getErrorCode());
            return Result.unavailable("No-show dispute source failed for this request (dispute module).");
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
