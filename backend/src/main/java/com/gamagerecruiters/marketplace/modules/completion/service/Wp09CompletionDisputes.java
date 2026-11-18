package com.gamagerecruiters.marketplace.modules.completion.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;

/**
 * Opens the dispute case through WP09's public {@link DisputeService#raiseDispute} (Injas). The booking is
 * already DISPUTED at this point, so the dispute module only creates the case (it does not change the booking).
 *
 * <p>Called after the completion transaction has committed. A failure here never undoes the raised issue or the
 * no-show report: it is logged, the response carries no dispute ID, and the party can still open the case from
 * the dispute pages within the dispute window. Evidence is not sent yet (file uploads are pending a TL decision).
 */
@Component
public class Wp09CompletionDisputes implements CompletionDisputes {

    private static final Logger log = LoggerFactory.getLogger(Wp09CompletionDisputes.class);

    private final DisputeService disputeService;

    public Wp09CompletionDisputes(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @Override
    public String openDispute(String bookingId, String actorId, Role actorRole, DisputeCategory category,
                              String description) {
        try {
            DisputeDetailResponse dispute = disputeService.raiseDispute(actorId, actorRole,
                    new RaiseDisputeRequest(bookingId, category, description, List.of()));
            log.info("event=COMPLETION_DISPUTE_OPENED bookingId={} disputeId={} category={}",
                    bookingId, dispute.id(), category);
            return dispute.id();
        } catch (ApiException ex) {
            log.warn("event=COMPLETION_DISPUTE_NOT_OPENED bookingId={} code={}", bookingId, ex.getErrorCode());
            return null;
        } catch (RuntimeException ex) {
            log.warn("event=COMPLETION_DISPUTE_NOT_OPENED bookingId={} cause={}", bookingId, ex.getClass().getSimpleName());
            return null;
        }
    }
}
