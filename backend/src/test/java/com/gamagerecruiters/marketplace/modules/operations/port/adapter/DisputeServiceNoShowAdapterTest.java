package com.gamagerecruiters.marketplace.modules.operations.port.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAiSummaryData;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.SubmitDisputeResponseRequest;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;
import com.gamagerecruiters.marketplace.modules.operations.port.NoShowDisputesPort;

/**
 * Contract-level test for the No-show adapter (finding F1): the fake DisputeService enforces the REAL dispute
 * pagination rule (size must be 1..{@link DisputeRules#MAX_PAGE_SIZE}, else 400 INVALID_PAGE_REQUEST), so any request
 * that exceeds the page size would make the adapter fail — proving it stays within the contract while still traversing
 * more than one page.
 */
class DisputeServiceNoShowAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-26T04:00:00Z");

    /** Mirrors DisputeServiceImpl: paginates a backing list and rejects size > MAX_PAGE_SIZE exactly like the real one. */
    private static final class ContractDisputeService implements DisputeService {
        private final List<DisputeSummaryResponse> backing;
        private RuntimeException failWith;

        ContractDisputeService(List<DisputeSummaryResponse> backing) {
            this.backing = backing;
        }

        @Override
        public DisputePageResponse<DisputeSummaryResponse> getDisputesForAdminByCategory(DisputeCategory category,
                                                                                         int page, int size) {
            if (failWith != null) {
                throw failWith;
            }
            if (page < 0 || size < 1 || size > DisputeRules.MAX_PAGE_SIZE) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST",
                        "page must be 0 or more and size must be between 1 and " + DisputeRules.MAX_PAGE_SIZE);
            }
            int from = Math.min(page * size, backing.size());
            int to = Math.min(from + size, backing.size());
            int totalPages = (int) Math.ceil(backing.size() / (double) size);
            return new DisputePageResponse<>(backing.subList(from, to), page, size, backing.size(), totalPages);
        }

        // Unused by the adapter.
        @Override public DisputeDetailResponse raiseDispute(String a, Role r, RaiseDisputeRequest req) { throw ue(); }
        @Override public DisputePageResponse<DisputeSummaryResponse> getMyDisputes(String u, int p, int s) { throw ue(); }
        @Override public DisputePageResponse<DisputeSummaryResponse> getMyDisputes(String u, Set<DisputeStatus> st, int p, int s) { throw ue(); }
        @Override public DisputeDetailResponse getDisputeForParty(String u, String d) { throw ue(); }
        @Override public DisputeDetailResponse submitResponse(String a, Role r, String d, SubmitDisputeResponseRequest req) { throw ue(); }
        @Override public DisputePageResponse<DisputeSummaryResponse> getDisputesForAdmin(String st, int p, int s) { throw ue(); }
        @Override public DisputeDetailResponse getDisputeForAdmin(String d) { throw ue(); }
        @Override public DisputeAiSummaryData getDisputeForAiSummary(String d, String a, Role r) { throw ue(); }
        @Override public DisputeAiSummaryData getDisputeForAiSummary(String d) { throw ue(); }

        private static UnsupportedOperationException ue() { return new UnsupportedOperationException(); }
    }

    private static List<DisputeSummaryResponse> noShowDisputes(int count) {
        List<DisputeSummaryResponse> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            DisputeStatus status = (i % 2 == 0) ? DisputeStatus.UNDER_REVIEW : DisputeStatus.AWAITING_RESPONSE;
            list.add(new DisputeSummaryResponse("dsp-" + i, "bk-" + i, "cust-" + i, "prov-" + i, Role.CUSTOMER,
                    DisputeCategory.NO_SHOW, status, null, null, NOW, NOW));
        }
        return list;
    }

    @Test
    @DisplayName("F1: traverses more than one page within the contract and keeps real statuses")
    void traversesBeyondFirstPageWithinContract() {
        var service = new ContractDisputeService(noShowDisputes(150));
        var adapter = new DisputeServiceNoShowAdapter(service);

        NoShowDisputesPort.Result result = adapter.findNoShowDisputes(200);

        assertThat(result.available()).isTrue();
        assertThat(result.disputes()).hasSize(150); // more than one 100-row page, gathered without exceeding the cap
        assertThat(result.disputes().get(0).status()).isEqualTo("UNDER_REVIEW"); // real dispute status retained
        assertThat(result.disputes().get(1).status()).isEqualTo("AWAITING_RESPONSE");
    }

    @Test
    @DisplayName("F1: caps at the requested limit")
    void respectsLimit() {
        var adapter = new DisputeServiceNoShowAdapter(new ContractDisputeService(noShowDisputes(150)));

        NoShowDisputesPort.Result result = adapter.findNoShowDisputes(100);

        assertThat(result.available()).isTrue();
        assertThat(result.disputes()).hasSize(100);
    }

    @Test
    @DisplayName("F1: a genuine source failure (5xx) reports unavailable")
    void sourceFailureReportsUnavailable() {
        var service = new ContractDisputeService(noShowDisputes(10));
        service.failWith = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DB_DOWN", "database unavailable");
        var adapter = new DisputeServiceNoShowAdapter(service);

        NoShowDisputesPort.Result result = adapter.findNoShowDisputes(200);

        assertThat(result.available()).isFalse();
        assertThat(result.reason()).contains("dispute module");
    }

    @Test
    @DisplayName("F1: an internal param error (4xx) is surfaced, not masked as unavailable")
    void paramErrorSurfaced() {
        var service = new ContractDisputeService(noShowDisputes(10));
        service.failWith = new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST", "bad size");
        var adapter = new DisputeServiceNoShowAdapter(service);

        ApiException ex = catchThrowableOfType(() -> adapter.findNoShowDisputes(200), ApiException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_PAGE_REQUEST");
    }
}
