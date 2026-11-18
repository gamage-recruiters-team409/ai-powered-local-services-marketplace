package com.gamagerecruiters.marketplace.modules.dispute.mapper;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.PartyResponse;
import com.gamagerecruiters.marketplace.modules.dispute.domain.StatusHistoryEntry;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.PartyResponseView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse.StatusHistoryView;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;

public final class DisputeMapper {

    private DisputeMapper() {
    }

    public static DisputeDetailResponse toDetail(Dispute dispute) {
        List<StatusHistoryView> history = dispute.getStatusHistory().stream()
                .map(DisputeMapper::toView)
                .toList();
        return new DisputeDetailResponse(
                dispute.getId(),
                dispute.getBookingId(),
                dispute.getCustomerId(),
                dispute.getProviderId(),
                dispute.getRaisedBy(),
                dispute.getRaisedByRole(),
                dispute.getCategory(),
                dispute.getDescription(),
                List.copyOf(dispute.getEvidence()),
                dispute.getStatus(),
                toView(dispute.getResponse()),
                dispute.getResponseDueAt(),
                dispute.getCreatedAt(),
                dispute.getUpdatedAt(),
                history);
    }

    public static DisputeSummaryResponse toSummary(Dispute dispute) {
        return new DisputeSummaryResponse(
                dispute.getId(),
                dispute.getBookingId(),
                dispute.getRaisedByRole(),
                dispute.getCategory(),
                dispute.getStatus(),
                dispute.getResponseDueAt(),
                dispute.getCreatedAt(),
                dispute.getUpdatedAt());
    }

    private static PartyResponseView toView(PartyResponse response) {
        if (response == null) {
            return null;
        }
        return new PartyResponseView(
                response.getResponderId(),
                response.getText(),
                List.copyOf(response.getEvidence()),
                response.getRespondedAt());
    }

    private static StatusHistoryView toView(StatusHistoryEntry entry) {
        return new StatusHistoryView(
                entry.getFromStatus(),
                entry.getToStatus(),
                entry.getActorId(),
                entry.getActorRole(),
                entry.getReason(),
                entry.getTimestamp());
    }
}
